package io.synapse.core.provider.doubao

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature
import java.util.Base64
import javax.security.auth.x500.X500Principal

/**
 * PKCS#10（CSR）手写 DER 编码器。
 *
 * ## 为什么要手写
 *
 * JDK 的 `sun.security.pkcs10.PKCS10` 是内部 API（Android 上没有），BouncyCastle 的
 * `PKCS10CertificationRequestBuilder` 会额外拖入 bcpkix 依赖。而**我们要复刻的一方**
 * 本身就是手写 ASN.1（`key/TeeApi.d(KeyPair, String)`：拼 `CertificationRequestInfo`
 * → 用 TEE 私钥 `SHA256withECDSA` 签 → 套 `-----BEGIN CERTIFICATE REQUEST-----`），
 * 所以手写才是「一比一」。
 *
 * ## 结构（RFC 2986）
 *
 * ```text
 * CertificationRequest ::= SEQUENCE {
 *     certificationRequestInfo CertificationRequestInfo,
 *     signatureAlgorithm         AlgorithmIdentifier,   -- 1.2.840.10045.4.3.2
 *     signature                  BIT STRING             -- TEE 内产物
 * }
 * ```
 *
 * 其中 `AlgorithmIdentifier` 按 **RFC 5754** 对 `ecdsa-with-SHA256` 要求
 * **省略 parameters 字段**（不能写 NULL）。这一点是纯 Java 实现最容易踩的坑：
 * 很多示例代码跟 `sha256WithRSAEncryption` 一样补了个 NULL，DER 校验严格的
 * 服务端会直接拒。
 */
internal object Der {

    /** DER 长度段：短形式 / 0x81 / 0x82 / 0x83。 */
    private fun length(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n ushr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())
    }

    /** 通用 TLV。 */
    fun tlv(tag: Int, content: ByteArray): ByteArray {
        val len = length(content.size)
        val out = ByteArray(1 + len.size + content.size)
        out[0] = tag.toByte()
        len.copyInto(out, 1)
        content.copyInto(out, 1 + len.size)
        return out
    }

    fun concat(items: List<ByteArray>): ByteArray {
        val buf = ByteArrayOutputStream()
        items.forEach { buf.write(it) }
        return buf.toByteArray()
    }

    /** SEQUENCE（0x30）。 */
    fun sequence(vararg items: ByteArray): ByteArray = tlv(0x30, concat(items.toList()))

    /** SET（0x31）。 */
    fun set(vararg items: ByteArray): ByteArray = tlv(0x31, concat(items.toList()))

    /** INTEGER（0x02），大端最小补码表示。 */
    fun integer(v: BigInteger): ByteArray = tlv(0x02, v.toByteArray())

    fun integer(v: Int): ByteArray = integer(BigInteger.valueOf(v.toLong()))

    /** BOOLEAN（0x01）。 */
    fun boolean(v: Boolean): ByteArray = tlv(0x01, byteArrayOf(if (v) 0xFF.toByte() else 0x00))

    /** OCTET STRING（0x04）。 */
    fun octetString(b: ByteArray): ByteArray = tlv(0x04, b)

    /** BIT STRING（0x03），未用位恒为 0。 */
    fun bitString(b: ByteArray): ByteArray = tlv(0x03, byteArrayOf(0) + b)

    /** context-specific `[n]`（0xA0|n），IMPLICIT 上下文的载体。 */
    fun implicit(n: Int, content: ByteArray): ByteArray = tlv(0xA0 or n, content)

    /**
     * OBJECT IDENTIFIER（0x06）。
     *
     * 首字节 = `40 * 第一段 + 第二段`；其余每段 7 位一组、低位在前、最高位表示续接。
     * OID 2.5.29.19 → `55 1D 13`，2.5.29.19 与 1.2.840.113549.1.9.14 都在单测里锁死。
     */
    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.trim().toInt() }
        require(parts.size >= 2) { "OID 至少两段：$dotted" }
        val buf = ByteArrayOutputStream()
        buf.write(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) {
            var v = parts[i]
            val stack = ArrayDeque<Int>()
            stack.addFirst(v and 0x7F)
            v = v ushr 7
            while (v > 0) {
                stack.addFirst((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            stack.forEach { buf.write(it) }
        }
        return tlv(0x06, buf.toByteArray())
    }
}

/**
 * 生成豆包同款 CSR（PEM）。签名私钥来自 TEE，本类只管「编码 + 签名」，
 * 不碰 KeyStore —— 这样在纯 JVM 单测里也能用软密钥对完整验证一遍。
 */
object Pkcs10 {

    /** ecdsa-with-SHA256（RFC 5754）。 */
    const val OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"

    /** basicConstraints（RFC 5280）。豆包的 CSR 只带这一个扩展。 */
    const val OID_BASIC_CONSTRAINTS = "2.5.29.19"

    /** extensionRequest（PKCS#9）。 */
    const val OID_EXTENSION_REQUEST = "1.2.840.113549.1.9.14"

    private const val PEM_BEGIN = "-----BEGIN CERTIFICATE REQUEST-----"
    private const val PEM_END = "-----END CERTIFICATE REQUEST-----"

    /**
     * 组装并签名 `CertificationRequest`。
     *
     * [keyPair] 的私钥若来自 AndroidKeyStore，[KeyPair.private] 是一个**句柄**：
     * `getEncoded()` 会抛异常，但 `Signature.initSign()` 能用 —— 签名在 TEE 内完成，
     * 私钥永不离开安全芯片（这正是复刻的关键）。
     */
    fun build(keyPair: KeyPair, subjectCn: String): ByteArray {
        val subject = X500Principal("CN=$subjectCn").encoded
        val publicKeyInfo = keyPair.public.encoded
            ?: throw IllegalStateException("公钥无法编码（不是有效的 EC 公钥）")

        // basicConstraints: cA = FALSE（豆包只放这一条，且非 critical）
        val basicConstraints = Der.sequence(Der.boolean(false))
        val extension = Der.sequence(
            Der.oid(OID_BASIC_CONSTRAINTS),
            Der.octetString(basicConstraints),
        )
        // attributes [0] IMPLICIT SET OF Attribute
        val attribute = Der.sequence(
            Der.oid(OID_EXTENSION_REQUEST),
            Der.set(extension),
        )
        val attributes = Der.implicit(0, attribute)

        val requestInfo = Der.sequence(
            Der.integer(0), // version
            subject,
            publicKeyInfo,
            attributes,
        )

        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(keyPair.private)
            update(requestInfo)
        }.sign()

        // 注意：ecdsa-with-SHA256 的 AlgorithmIdentifier 不带 parameters（RFC 5754）
        val algorithm = Der.sequence(Der.oid(OID_ECDSA_WITH_SHA256))

        return Der.sequence(requestInfo, algorithm, Der.bitString(signature))
    }

    /** DER → PEM（64 字符/行 MIME 换行），与豆包 `-----BEGIN CERTIFICATE REQUEST-----` 一致。 */
    fun toPem(der: ByteArray): String = buildString {
        append(PEM_BEGIN)
        append('\n')
        append(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der))
        append('\n')
        append(PEM_END)
    }
}
