package io.synapse.core.provider.doubao

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import javax.security.auth.x500.X500Principal

/** 设备不支持 AndroidKeyStore（例如在纯 JVM 测试环境里）。 */
class TeeUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * AndroidKeyStore（TEE / StrongBox）门面。
 *
 * ## 这是「一比一复刻」的真正核心
 *
 * 目标应用 `key/TeeApi` 的密钥生成参数（逐条抄自反编译）：
 *
 * ```text
 * KeyGenParameterSpec.Builder(alias, PURPOSE_SIGN /* = 4 */)
 *     .setDigests(["SHA-256"])
 *     .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
 *     .setCertificateSubject(X500Principal("CN=" + alias))
 *     .setCertificateSerialNumber(BigInteger.ONE)
 *     .setCertificateNotBefore(Date(0))                  // 1970-01-01
 *     .setCertificateNotAfter(Date(0x23d19d43c00))       // 2048-01-01
 *     .setUserAuthenticationRequired(false)
 *     .build()
 * ```
 *
 * 另有**第二把密钥**（alias 固定 `bd_ticket_guard_key_attestation`）在此基础上多一行
 * `setAttestationChallenge(sha256(teePub + "#" + reePub))`：
 * 带 challenge 生成的密钥，`getCertificateChain()` 才会返回**带 Key Attestation 扩展
 * （OID 1.3.6.1.4.1.11129.2.1.17）的证书链** —— 服务端据此判定「这把公钥真的住在
 * 设备的 TEE 里，且 challenge 与本次会话绑定」。
 *
 * ## 为什么全用反射
 *
 * `android.security.keystore.*` 不在 JDK 里，直接 import 会让 `:core` 这个纯 JVM 模块
 * 编译不过（它同时要跑本机单测）。反射访问让「同一份字节码」既能在 Android 上真正
 * 生成 TEE 密钥，又能在 JVM 上安全地报告「不可用」。
 *
 * ## 私钥不出芯片
 *
 * `getKey(alias, null)` 拿到的是 provider 的**句柄**（`AndroidKeyStorePrivateKey`）：
 * `getEncoded()` 抛 `UnsupportedOperationException`，但 `Signature.initSign()` 可用。
 * 所以 [sign] 的签名运算发生在 TEE 内部，私钥永远不会以明文出现在应用内存里。
 */
class TeeKeyStore {

    companion object {
        const val PROVIDER = "AndroidKeyStore"

        /** `KeyProperties.PURPOSE_SIGN`。 */
        const val PURPOSE_SIGN = 4

        /** 目标应用 attestation 密钥的固定别名（各 App 的 KeyStore 按 UID 隔离，同名不冲突）。 */
        const val ATTESTATION_ALIAS = "bd_ticket_guard_key_attestation"

        /** Key Attestation 扩展 OID（Google 定义）。 */
        const val OID_KEY_ATTESTATION = "1.3.6.1.4.1.11129.2.1.17"

        /** `0x23d19d43c00` = 2048-01-01T00:00:00Z，与目标应用的证书有效期一致。 */
        private val NOT_AFTER = Date(2461449600000L)
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(PROVIDER).apply { load(null, null) }
    }

    /** AndroidKeyStore 是否可用（纯 JVM 上返回 false，不抛异常）。 */
    fun isAvailable(): Boolean = runCatching { keyStore.getProvider().name }.isSuccess

    private fun requireAvailable(): KeyStore =
        if (isAvailable()) keyStore
        else throw TeeUnavailableException("当前环境没有 AndroidKeyStore（$PROVIDER）")

    // ------------------------------------------------------------------ 查询

    fun containsAlias(alias: String): Boolean = requireAvailable().containsAlias(alias)

    fun deleteAlias(alias: String): Unit = requireAvailable().deleteEntry(alias)

    /** 主密钥公钥；不存在返回 null。 */
    fun publicKey(alias: String): PublicKey? =
        runCatching { requireAvailable().getCertificate(alias)?.publicKey }.getOrNull()

    /** TEE 私钥句柄；不存在返回 null。注意：`getEncoded()` 不可用，但能用来签名。 */
    fun privateKey(alias: String): PrivateKey? =
        runCatching { requireAvailable().getKey(alias, null) as? PrivateKey }.getOrNull()

    /**
     * 证书链；不存在返回空表。
     *
     * 带 `setAttestationChallenge` 生成的密钥，这里返回的是
     * `[叶子(含 Key Attestation 扩展), 中间 CA…, 根]`；不带 challenge 时通常只有一张自签证书。
     */
    fun certificateChain(alias: String): List<X509Certificate> {
        val chain = runCatching { requireAvailable().getCertificateChain(alias) }.getOrNull()
            ?: return emptyList()
        return chain.mapNotNull { it as? X509Certificate }
    }

    /** 叶子证书里的 Key Attestation 扩展原始字节；没有返回 null。 */
    fun keyAttestationExtension(alias: String = ATTESTATION_ALIAS): ByteArray? =
        certificateChain(alias).firstOrNull()?.getExtensionValue(OID_KEY_ATTESTATION)

    // ------------------------------------------------------------------ 生成

    /**
     * 生成（或复用）主签名密钥。已存在直接返回公钥，不重建 —— 重建会让服务端
     * 已登记的公钥失配（目标应用也只在 `enableAttestationRegenKeyIfFailed` 时才重建）。
     */
    fun ensureSigningKeyPair(alias: String): KeyPair {
        publicKey(alias)?.let { return KeyPair(it, privateKey(alias)) }
        return generate(alias, attestationChallenge = null)
    }

    /**
     * 生成（或复用）attestation 密钥。
     *
     * [challenge] 变了必须重建：证书里的 challenge 与当前会话绑定，沿用旧值会被判重放
     * （目标应用对应日志 `Key attestation, need regenerate, delete old key`）。
     */
    fun ensureAttestationKeyPair(challenge: ByteArray, alias: String = ATTESTATION_ALIAS): KeyPair {
        if (containsAlias(alias)) {
            val current = keyAttestationExtension(alias)
            if (current != null && current.contentEquals(challenge)) {
                publicKey(alias)?.let { return KeyPair(it, privateKey(alias)) }
            }
            deleteAlias(alias)
        }
        return generate(alias, attestationChallenge = challenge)
    }

    private fun generate(alias: String, attestationChallenge: ByteArray?): KeyPair {
        val spec = buildKeyGenParameterSpec(alias, attestationChallenge)
        val generator = KeyPairGenerator.getInstance("EC", PROVIDER)
        generator.initialize(spec)
        generator.generateKeyPair()
        // 统一回到 KeyStore 句柄：从 generate 返回的 KeyPair 在部分实现里不能直接 initSign
        val pub = publicKey(alias) ?: throw TeeUnavailableException("密钥已生成但取不到公钥：$alias")
        return KeyPair(pub, privateKey(alias))
    }

    /**
     * 反射构造 `KeyGenParameterSpec`（逐条对应目标应用的 `TeeApi.g`）。
     *
     * 注意 `KeyGenParameterSpec.Builder.setAttestationChallenge` 只在 API 24+ 存在；
     * 本项目 minSdk 26，安全。
     */
    private fun buildKeyGenParameterSpec(alias: String, attestationChallenge: ByteArray?): AlgorithmParameterSpec {
        val builderClass = Class.forName("android.security.keystore.KeyGenParameterSpec\$Builder")
        val builder = builderClass
            .getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance(alias, PURPOSE_SIGN)

        builderClass.getMethod("setDigests", Array<String>::class.java)
            .invoke(builder, arrayOf("SHA-256"))
        builderClass.getMethod("setAlgorithmParameterSpec", AlgorithmParameterSpec::class.java)
            .invoke(builder, ECGenParameterSpec("secp256r1"))
        builderClass.getMethod("setCertificateSubject", X500Principal::class.java)
            .invoke(builder, X500Principal("CN=$alias"))
        builderClass.getMethod("setCertificateSerialNumber", BigInteger::class.java)
            .invoke(builder, BigInteger.ONE)
        builderClass.getMethod("setCertificateNotBefore", Date::class.java)
            .invoke(builder, Date(0L))
        builderClass.getMethod("setCertificateNotAfter", Date::class.java)
            .invoke(builder, NOT_AFTER)
        builderClass.getMethod("setUserAuthenticationRequired", Boolean::class.javaPrimitiveType)
            .invoke(builder, false)
        if (attestationChallenge != null) {
            builderClass.getMethod("setAttestationChallenge", ByteArray::class.java)
                .invoke(builder, attestationChallenge)
        }
        return builderClass.getMethod("build").invoke(builder) as AlgorithmParameterSpec
    }

    // ------------------------------------------------------------------ 签名 / 导出

    /**
     * EC 公钥 → **未压缩点**字节（`0x04 || X(32) || Y(32)`，共 65 字节）。
     *
     * 上游 HTTP 头用的是**裸点**而不是 X.509 SPKI：抓包里
     * `bd-ticket-guard-ree-public-key` 解码后正好 65 字节、首字节 `0x04`，
     * `bd-ticket-guard-client-cert`（`pub.` 体）解两层后也是 65 字节裸点。
     * 注意它与 [certificateToPem] 那条链上的 SPKI 形态**不可混用**：
     * 同一条曲线 SPKI 约 91 字节、base64 后 124 字符，形状一眼可辨。
     */
    fun ecPoint(publicKey: PublicKey): ByteArray {
        val w = runCatching { (publicKey as java.security.interfaces.ECPublicKey).w }.getOrNull()
        if (w != null) return byteArrayOf(0x04) + fixed32(w.affineX) + fixed32(w.affineY)
        // 回退：未压缩点的 X.509 SPKI 尾部 65 字节就是裸点
        val enc = publicKey.encoded
        require(enc != null && enc.size >= 65 && enc[enc.size - 65] == 0x04.toByte()) {
            "公钥不是未压缩 EC 点，取不到裸点"
        }
        return enc.copyOfRange(enc.size - 65, enc.size)
    }

    /** BigInteger → 定长 32 字节（P-256 的 X / Y 坐标）。 */
    private fun fixed32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == 32 -> raw
            raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size) // 去掉符号位补的 0x00
            else -> ByteArray(32 - raw.size) + raw
        }
    }

    /** SHA256withECDSA 签名（TEE 内运算）。 */
    fun sign(alias: String, data: ByteArray): ByteArray {
        val key = privateKey(alias) ?: throw TeeUnavailableException("私钥不存在：$alias")
        return Signature.getInstance("SHA256withECDSA").apply {
            initSign(key)
            update(data)
        }.sign()
    }

    /** 单张证书 → PEM（64 列 MIME 换行），与目标应用 `math/c.c("CERTIFICATE", …)` 同形。 */
    fun certificateToPem(cert: X509Certificate): String = buildString {
        append("-----BEGIN CERTIFICATE-----\n")
        append(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded))
        append("\n-----END CERTIFICATE-----")
    }

    /**
     * 证书链 → `bd-ticket-guard-attest-certs` 头的值。
     *
     * 目标应用 `TeeKeyHelper.s()`：把链里每张证书转 PEM、用 `,` 拼接成**一个大字符串**，
     * 再整体做一次 `Base64(NO_WRAP)`。所以这个头的值是「base64 了的 PEM 链」，
     * 不是把每张证书单独 base64 —— 解的时候要先 base64 解码再按 PEM 拆。
     */
    fun certificateChainHeaderValue(alias: String = ATTESTATION_ALIAS): String {
        val pemChain = certificateChain(alias).joinToString(",") { certificateToPem(it) }
        return Base64.getEncoder().encodeToString(pemChain.toByteArray(Charsets.UTF_8))
    }
}
