package io.synapse.core.pool

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 账号导出文件的二进制编解码。
 *
 * ## 为什么要有这一层
 *
 * 旧格式是明文 JSON，`secret` 字段里就是完整的 accessToken / refreshToken。
 * 三条泄漏路径：复制到剪贴板被别的应用读、存进下载目录被文件管理器直接打开、
 * 发给别人时被中间环节看到。所以默认改成加密的二进制文件。
 *
 * ## 格式
 *
 * ```
 * offset  size  内容
 * 0       4     魔数 "AGW1"（识别文件类型；不认识的直接拒绝）
 * 4       1     标志位：0 = 明文 JSON 正文，1 = AES-GCM 加密
 * 5       16    salt（仅加密时有效；PBKDF2 派生密钥用）
 * 21      12    iv  （仅加密时有效）
 * 33      N     正文（明文 JSON，或 AES-GCM 密文+认证标签）
 * ```
 *
 * 固定头部 33 字节：明文模式下 salt/iv 填 0，保证两种模式文件长度计算统一，
 * 解析时不用先猜模式再换偏移。
 *
 * ## 加密参数
 *
 * - 密钥派生：PBKDF2WithHmacSHA256，10 000 次迭代（手机上约 100ms）
 * - 加密：AES-256-GCM，16 字节认证标签（篡改文件会解密失败而不是拿到垃圾数据）
 *
 * 口令**不落盘、不进日志**：它只在导出/导入的这一次调用里存在。
 */
object AccountTransferCodec {

    /** 文件魔数：不匹配就不是本应用的文件。 */
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'G'.code.toByte(), 'W'.code.toByte(), '1'.code.toByte())

    /** 头部长度：魔数 4 + 标志 1 + salt 16 + iv 12。 */
    private const val HEADER_SIZE = 33

    private const val FLAG_PLAINTEXT = 0.toByte()
    private const val FLAG_ENCRYPTED = 1.toByte()

    private const val SALT_SIZE = 16
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 10_000
    private const val KEY_BITS = 256

    /**
     * 编码成二进制文件内容。
     *
     * [password] 为空 → 明文正文（用户主动选择「不加密」）；
     * 非空 → AES-GCM 加密。
     *
     * 注意：明文模式下头部仍然带魔数，导入端据此走不同分支，
     * 不会把明文文件误当成加密文件去解密。
     */
    fun encode(json: String, password: String?): ByteArray {
        val body = json.toByteArray(Charsets.UTF_8)
        if (password.isNullOrEmpty()) {
            return MAGIC + FLAG_PLAINTEXT + ByteArray(SALT_SIZE) + ByteArray(IV_SIZE) + body
        }
        val salt = ByteArray(SALT_SIZE).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(body)
        return MAGIC + FLAG_ENCRYPTED + salt + iv + encrypted
    }

    /**
     * 解码二进制文件内容。
     *
     * 抛出的 [IllegalArgumentException] 消息是给用户看的原话。
     *
     * [password] 只在文件是加密模式时才用得上；
     * 加密文件但没给口令 → 提示「需要口令」；
     * 口令错或文件被改过 → GCM 认证失败，提示「口令错误或文件已损坏」。
     */
    fun decode(bytes: ByteArray, password: String?): String {
        if (bytes.size < HEADER_SIZE) {
            throw IllegalArgumentException("文件太小，不是本应用导出的账号文件")
        }
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) {
                throw IllegalArgumentException("不是本应用导出的账号文件（文件头不匹配）")
            }
        }
        val flag = bytes[4]
        val body = bytes.copyOfRange(HEADER_SIZE, bytes.size)
        return when (flag) {
            FLAG_PLAINTEXT -> String(body, Charsets.UTF_8)
            FLAG_ENCRYPTED -> {
                if (password.isNullOrEmpty()) {
                    throw IllegalArgumentException("这个文件已加密，请先输入导出口令")
                }
                val salt = bytes.copyOfRange(5, 5 + SALT_SIZE)
                val iv = bytes.copyOfRange(21, 21 + IV_SIZE)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(TAG_BITS, iv))
                try {
                    String(cipher.doFinal(body), Charsets.UTF_8)
                } catch (e: Exception) {
                    // GCM 认证失败：要么口令错，要么文件被改过。两者对用户是同一件事。
                    throw IllegalArgumentException("口令错误，或文件已损坏")
                }
            }
            else -> throw IllegalArgumentException("文件格式不认识（标志位 $flag）")
        }
    }

    /** 文件是不是加密的（界面用来预先提示「需要口令」）。 */
    fun isEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_SIZE && bytes[4] == FLAG_ENCRYPTED

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }
}
