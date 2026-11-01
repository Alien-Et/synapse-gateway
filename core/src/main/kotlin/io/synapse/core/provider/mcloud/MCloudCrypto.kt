package io.synapse.core.provider.mcloud

import org.bouncycastle.asn1.gm.GMNamedCurves
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.engines.SM2Engine
import org.bouncycastle.crypto.engines.SM4Engine
import org.bouncycastle.crypto.modes.CBCBlockCipher
import org.bouncycastle.crypto.paddings.PKCS7Padding
import org.bouncycastle.crypto.paddings.PaddedBufferedBlockCipher
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.util.encoders.Hex
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 移动云盘会员中心的加解密。
 *
 * ## 为什么需要这个
 * 会员中心（vip.yun.139.com 的 m4c 接口）里，`account`/`passwd` 字段都是 RSA 密文
 * （见 [encryptRsa]）；而 App 端的 user-njs 接口走的是另一套 —— 用
 * **国密**：SM2 包一个随机 SM4 密钥，再用 SM4-ECB 加密业务 JSON。两套都在用，
 * 这里都实现，谁需要谁调。
 *
 * ## 国密协议（逆向自 mCloud 13.2.5）
 * 来自 `mcsapi/ose/imonitoringrecord/utils/GuoMiHelper.encrySm2`：
 * ```
 * 1. K = 16 字节随机密钥
 * 2. sm2Hex = "04" + hex(SM2Engine(C1C3C2).encrypt(pubKey, K 的 16 字符表示))
 *    -> 放到请求头 yun-encrypt-key
 * 3. body = hex(SM4-ECB/PKCS7(K, 业务JSON))
 * 4. 请求头 yun-encrypt-index = pubIndex（如 20_5）
 * ```
 * 实测 `yun-encrypt-key` 长 113 字节（`04` + C1(64) + C3(32) + C2(16)），
 * 与「SM2 加密 16 字符」的长度完全一致，这是判断协议正确性的硬指标。
 *
 * BouncyCastle 在 Android 上需要显式注册 Provider，否则 `Cipher.getInstance("SM4/ECB/PKCS7Padding")`
 * 会抛 `NoSuchAlgorithmException`。这里只在需要时注册一次。
 */
object MCloudCrypto {

    /** BouncyCastle Provider 名，用于按需注册。 */
    private const val BC = "BC"

    private val random = SecureRandom()
    private var providerReady = false

    private fun ensureProvider() {
        if (providerReady) return
        synchronized(this) {
            if (providerReady) return
            if (java.security.Security.getProvider(BC) == null) {
                java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
            }
            providerReady = true
        }
    }

    // ------------------------------------------------------------------ RSA（会员中心）

    /**
     * RSA/ECB/PKCS1Padding 加密，输出 Base64。
     *
     * 会员中心的 `account` / `passwd` 字段用它；公钥串是 X.509（Base64，无 PEM 头尾），
     * 两个不同接口用两个不同公钥，混用会被判「参数解密失败」。
     *
     * 结果按公钥缓存解析后的 key：一次登录要加密好几个字段，重复解析纯浪费。
     */
    fun encryptRsa(plain: String, publicKeyBase64: String): String {
        val key = publicKeyOf(publicKeyBase64)
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return java.util.Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    private val rsaKeyCache = HashMap<String, java.security.PublicKey>()

    private fun publicKeyOf(base64: String): java.security.PublicKey = synchronized(rsaKeyCache) {
        rsaKeyCache.getOrPut(base64) {
            val der = java.util.Base64.getMimeDecoder().decode(base64)
            java.security.KeyFactory.getInstance("RSA")
                .generatePublic(java.security.spec.X509EncodedKeySpec(der))
        }
    }

    // ------------------------------------------------------------------ 国密（App 端接口）

    /**
     * 一次国密加密的产物，直接对应三个请求要素。
     *
     * @property keyHeader  `yun-encrypt-key` 头的值（SM2 密文的 hex）
     * @property body       请求体（SM4 密文的 hex）
     * @property keyHex     本次用的 16 字符密钥；只在需要调试时用，不要打日志
     */
    data class GuomiEnvelope(
        val keyHeader: String,
        val body: String,
        val keyHex: String,
    )

    /**
     * 复刻 `GuoMiHelper.encrySm2`：加密一段业务 JSON。
     *
     * [publicKeyHex] 来自 `GET /adaptor/encrypt/publicKey?appId=...` 的 `pubKey` 字段，
     * 是未压缩 EC 点（65 字节，`04` 开头）。每次请求都生成新密钥 —— 上游按次校验，
     * 复用一个密钥会被风控拦掉。
     */
    fun encryptGuomi(plainJson: String, publicKeyHex: String): GuomiEnvelope {
        // 1) 16 字节随机密钥；用可打印字符表示（上游就是这么取的，长度必须正好 16 字符）
        val keyChars = randomKeyChars16()
        // 2) SM2(C1C3C2) 加密密钥
        val keyHeader = sm2EncryptHex(keyChars, publicKeyHex)
        // 3) SM4-ECB/PKCS7 加密数据
        val body = sm4EncryptHex(keyChars.toByteArray(Charsets.US_ASCII), plainJson.toByteArray(Charsets.UTF_8))
        return GuomiEnvelope(keyHeader = keyHeader, body = body, keyHex = keyChars)
    }

    /**
     * SM2 加密，返回带 `04` 前缀的 hex。
     *
     * 上游用 BouncyCastle 的 `SM2Engine(Mode.C1C3C2)`，**它输出的 C1 是包含 `04` 的完整点**；
     * 而 sm-crypto 之类库会省略掉这个字节。为了和上游逐字节对齐，这里显式补回 `04`。
     */
    fun sm2EncryptHex(plain: String, publicKeyHex: String): String {
        ensureProvider()
        val bytes = plain.toByteArray(Charsets.US_ASCII)
        val params = ecPublicKeyOf(publicKeyHex)
        val engine = SM2Engine(SM2Engine.Mode.C1C3C2)
        engine.init(true, org.bouncycastle.crypto.params.ParametersWithRandom(params, random))
        val out = engine.processBlock(bytes, 0, bytes.size)
        val hex = Hex.toHexString(out)
        return if (hex.startsWith("04")) hex else "04$hex"
    }

    /** 解析未压缩 EC 公钥（`04||X||Y`）到 BouncyCastle 参数。 */
    private val ecKeyCache = HashMap<String, ECPublicKeyParameters>()

    private fun ecPublicKeyOf(publicKeyHex: String): ECPublicKeyParameters = synchronized(ecKeyCache) {
        ecKeyCache.getOrPut(publicKeyHex) {
            val x9 = GMNamedCurves.getByName("sm2p256v1")
                ?: throw IllegalStateException("BouncyCastle 缺少 sm2p256v1 曲线")
            val domain = ECDomainParameters(x9.curve, x9.g, x9.n)
            val point = x9.curve.decodePoint(Hex.decode(publicKeyHex))
            ECPublicKeyParameters(point, domain)
        }
    }

    /**
     * SM4-ECB/PKCS7 加密，返回 hex。
     *
     * 上游走 `PaddedBufferedBlockCipher(SM4Engine, PKCS7Padding)`；这里用同一条 BouncyCastle
     * 路径而不是 JCE 的 `SM4/ECB/PKCS7Padding`，避免不同 Provider 在末块填充上的细微差异。
     */
    fun sm4EncryptHex(key: ByteArray, data: ByteArray): String {
        val cipher = PaddedBufferedBlockCipher(SM4Engine(), PKCS7Padding())
        cipher.init(true, KeyParameter(key))
        val out = ByteArray(cipher.getOutputSize(data.size))
        var len = cipher.processBytes(data, 0, data.size, out, 0)
        len += cipher.doFinal(out, len)
        return Hex.toHexString(out.copyOf(len))
    }

    /** SM4-ECB/PKCS7 解密（响应体是同样格式，联调时用）。 */
    fun sm4DecryptHex(key: ByteArray, hex: String): String {
        val cipher = PaddedBufferedBlockCipher(SM4Engine(), PKCS7Padding())
        cipher.init(false, KeyParameter(key))
        val input = Hex.decode(hex)
        val out = ByteArray(cipher.getOutputSize(input.size))
        var len = cipher.processBytes(input, 0, input.size, out, 0)
        len += cipher.doFinal(out, len)
        return String(out, 0, len, Charsets.UTF_8)
    }

    /**
     * SM4-ECB/PKCS7 解密 **Base64** 编码的密文。
     *
     * `thirdlogin` 这类 user 域接口的响应是 base64 而不是 hex——和请求体是 hex 不同，
     * 这是上游两种编码混用的坑，解密前必须先识别编码。
     */
    fun sm4DecryptBase64(key: ByteArray, base64: String): String {
        val cipher = PaddedBufferedBlockCipher(SM4Engine(), PKCS7Padding())
        cipher.init(false, KeyParameter(key))
        val input = java.util.Base64.getDecoder().decode(base64)
        val out = ByteArray(cipher.getOutputSize(input.size))
        var len = cipher.processBytes(input, 0, input.size, out, 0)
        len += cipher.doFinal(out, len)
        return String(out, 0, len, Charsets.UTF_8)
    }

    /**
     * SM4-CBC/PKCS7 加密，返回 `IV(16) || 密文`。
     *
     * 上游的 AES 老方案（`AESUtil.encryptIV`）就是这个形态；国密分支实测也可能是 CBC，
     * 与 ECB 混用会直接触发 `Given final block not properly padded`（密钥/模式不符）。
     */
    fun sm4EncryptCbcIvPrefix(key: ByteArray, data: ByteArray): ByteArray {
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val cipher = PaddedBufferedBlockCipher(CBCBlockCipher.newInstance(SM4Engine()), PKCS7Padding())
        cipher.init(true, ParametersWithIV(KeyParameter(key), iv))
        val out = ByteArray(cipher.getOutputSize(data.size))
        var len = cipher.processBytes(data, 0, data.size, out, 0)
        len += cipher.doFinal(out, len)
        return iv + out.copyOfRange(0, len)
    }

    /** SM4-ECB/PKCS7 解密原始字节（不关心外层是 hex 还是 base64，调用方自己解码）。 */
    fun sm4DecryptEcb(key: ByteArray, data: ByteArray): String {
        val cipher = PaddedBufferedBlockCipher(SM4Engine(), PKCS7Padding())
        cipher.init(false, KeyParameter(key))
        return decryptBlock(cipher, key, data)
    }

    /** SM4-CBC/PKCS7 解密，假定密文前 16 字节是 IV。 */
    fun sm4DecryptCbcIvPrefix(key: ByteArray, data: ByteArray): String {
        if (data.size <= 16) return ""
        val iv = data.copyOfRange(0, 16)
        val body = data.copyOfRange(16, data.size)
        val cipher = PaddedBufferedBlockCipher(CBCBlockCipher.newInstance(SM4Engine()), PKCS7Padding())
        cipher.init(false, ParametersWithIV(KeyParameter(key), iv))
        return decryptBlockRaw(cipher, body)
    }

    /** AES-ECB/PKCS7 解密原始字节；密钥长度 16/24/32 分别对应 AES-128/192/256。 */
    fun aesDecryptEcb(key: ByteArray, data: ByteArray): String {
        val cipher = PaddedBufferedBlockCipher(AESEngine(), PKCS7Padding())
        cipher.init(false, KeyParameter(key))
        return decryptBlockRaw(cipher, data)
    }

    /** AES-CBC/PKCS7 解密，假定密文前 16 字节是 IV。 */
    fun aesDecryptCbcIvPrefix(key: ByteArray, data: ByteArray): String {
        if (data.size <= 16) return ""
        val iv = data.copyOfRange(0, 16)
        val body = data.copyOfRange(16, data.size)
        val cipher = PaddedBufferedBlockCipher(CBCBlockCipher.newInstance(AESEngine()), PKCS7Padding())
        cipher.init(false, ParametersWithIV(KeyParameter(key), iv))
        return decryptBlockRaw(cipher, body)
    }

    private fun decryptBlock(cipher: PaddedBufferedBlockCipher, key: ByteArray, data: ByteArray): String =
        decryptBlockRaw(cipher, data)

    private fun decryptBlockRaw(cipher: PaddedBufferedBlockCipher, data: ByteArray): String {
        val out = ByteArray(cipher.getOutputSize(data.size))
        var len = cipher.processBytes(data, 0, data.size, out, 0)
        len += cipher.doFinal(out, len)
        return String(out, 0, len, Charsets.UTF_8)
    }

    /**
     * 生成 16 个可打印 ASCII 字符当 SM4 密钥。
     *
     * 上游 `SM4Util.generateKey(16)` 生成的是 16 字节，但后续把它当**字符串**参与
     * SM2 加密（长度必须正好 16 字符），所以这里直接生成可打印字符，
     * 保证「16 字符 == 16 字节」在 UTF-8 下成立。
     */
    private fun randomKeyChars16(): String {
        val pool = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder(16)
        repeat(16) { sb.append(pool[random.nextInt(pool.length)]) }
        return sb.toString()
    }

    // ------------------------------------------------------------------ AES（老方案，保留兼容）

    /**
     * `AESUtil.encryptIV` 的等价实现：随机 IV 拼在密文前面再 Base64。
     *
     * 布局是 `Base64(IV(16) || AES-CBC-PKCS5(明文))`。
     *
     * 密钥长度决定算法档位，不能写死成 AES-128：
     * - 会员中心业务数据用 `2olBaQGYnEKoStYomsd1n7ax`（24 字符 → **AES-192**）
     * - `AESUtil` 里加密手机号用的是 `nKdf317moJiFTrNq`（16 字符 → AES-128）
     * 写死 AES-128 会在 24 字符密钥上抛 `Invalid key length`——这条坑实测踩过。
     */
    fun encryptAesIv(plain: String, key: String): String {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        require(keyBytes.size == 16 || keyBytes.size == 24 || keyBytes.size == 32) {
            "AES 密钥长度必须是 16/24/32 字节，实际 ${keyBytes.size}"
        }
        val iv = ByteArray(16).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return androidBase64Encode(iv + ct)
    }

    /** 与 [encryptAesIv] 对称的解密。 */
    fun decryptAesIv(encoded: String, key: String): String {
        val all = androidBase64Decode(encoded)
        val iv = all.copyOfRange(0, 16)
        val ct = all.copyOfRange(16, all.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ thirdlogin（短信登录）专用信封

    /**
     * thirdlogin 请求体加密（Hook 实测逆向，已用已知明密文对逐块验证）。
     *
     * ## 算法
     * 1. `M = AES-128-ECB-PKCS7(KEY_RL_AES, payload)`
     * 2. `ct = M[末尾 r 字节] + M[前 len-r 字节]`，其中 `r = PKCS7 填充长度 - 1`
     *    （payload 186 字节 → 填 6 字节 → r=5；观察样本 `08eb28cad1` = 尾块密文后 5 字节）
     * 3. body = `ct` 的 **hex**（不是 base64！同会话里 getSmsCode 等普通接口是
     *    `AES-192-KEY_BIZ-CBC-IV前缀` + base64，thirdlogin 是唯一走 hex 的）
     *
     * ## 为什么长这样
     * libdd.so 里 `Java_..._D_aesEncode` → `encryArray` 是 AES-128-ECB + EVP_aes_128_ecb，
     * 密钥取全局变量 `rlAesKey`。App 端 thirdlogin 的密文把「最后一块的后 r 字节」
     * 循环挪到了最前面（旋转量恰为填充长度-1），属实现层特性，按实测量复现。
     */
    fun encryptThirdLogin(payload: ByteArray, keyHex: String = MCloudConstants.KEY_RL_AES): String {
        val key = keyHex.toByteArray(Charsets.US_ASCII)
        require(key.size == 16) { "thirdlogin 密钥必须是 16 字节，实际 ${key.size}" }
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val m = cipher.doFinal(payload)

        val padLen = 16 - (payload.size % 16)
        val r = padLen - 1
        val rotated = m.copyOfRange(m.size - r, m.size) + m.copyOfRange(0, m.size - r)
        return Hex.toHexString(rotated)
    }

    /**
     * thirdlogin 响应 `data`（hex 密文）解密。与 [encryptThirdLogin] 对称：
     * 先按旋转量 `r` 还原块顺序再 AES-128-ECB 解密。响应明文长度未知，
     * 所以对 `r ∈ 0..15` 穷举，解出可读 JSON 即命中。
     */
    fun decryptThirdLoginInner(hexBody: String, keyHex: String = MCloudConstants.KEY_RL_AES): String? {
        val data = runCatching { Hex.decode(hexBody.trim()) }.getOrNull() ?: return null
        if (data.isEmpty() || data.size % 16 != 0) return null
        val key = keyHex.toByteArray(Charsets.US_ASCII)
        for (r in 0..15) {
            // 加密是「尾部 r 字节旋转到头部」：ct = M[len-r:] + M[:len-r]，
            // 逆操作即 m = ct[r:] + ct[:r]
            val m = data.copyOfRange(r, data.size) + data.copyOfRange(0, r)
            val plain = runCatching {
                val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
                String(cipher.doFinal(m), Charsets.UTF_8)
            }.getOrNull() ?: continue
            if (plain.trimStart().startsWith("{")) return plain
        }
        return null
    }

    // 用 java.util.Base64 而不是 android.util.Base64，保证 :core 能在纯 JVM 上跑测试
    private fun androidBase64Encode(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    private fun androidBase64Decode(text: String): ByteArray =
        java.util.Base64.getMimeDecoder().decode(text)
}
