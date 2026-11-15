package io.synapse.core.provider.doubao

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TEE 设备证明链里**可在纯 JVM 上验证**的部分。
 *
 * TEE 本身（AndroidKeyStore）在 JVM 上不存在，所以这里不去假装能测它，而是把
 * 「能测的一切」测死：DER/OID 编码、CSR 的签名可验性、challenge 派生、
 * 请求头装配纪律、响应裁决解析，以及「TEE 不可用时必须安静降级成空头」。
 *
 * CSR 的验签是真的重放一遍：拆出 `CertificationRequestInfo` 的原始 DER，
 * 用公钥独立验一遍签名，再故意改一个字节确认会失败 —— 这样「手写 ASN.1 写对了」
 * 就不是靠肉眼，而是靠密码学证明。
 */
class DoubaoDeviceAttestationTest {

    // ------------------------------------------------------------------ DER / OID

    @Test
    fun `OID 编码与 RFC 一致`() {
        // 2.5.29.19 → 40*2+5 = 85 = 0x55, 29 = 0x1D, 19 = 0x13
        assertHex("0603551D13", Der.oid("2.5.29.19"))
        // 1.2.840.10045.4.3.2 → 2A 86 48 CE 3D 04 03 02（共 8 字节）
        assertHex("06082A8648CE3D040302", Der.oid("1.2.840.10045.4.3.2"))
        // 1.2.840.113549.1.9.14 → 42 86 48 86 F7 0D 01 09 0E
        assertHex("06092A864886F70D01090E", Der.oid("1.2.840.113549.1.9.14"))
    }

    @Test
    fun `DER 长度段在 128 字节处切换成长形式`() {
        val short = Der.octetString(ByteArray(127))
        assertEquals(0x7F, short[1].toInt() and 0xFF)
        val long = Der.octetString(ByteArray(200))
        assertEquals(0x81, long[1].toInt() and 0xFF)
        assertEquals(200, long[2].toInt() and 0xFF)
    }

    // ------------------------------------------------------------------ CSR

    @Test
    fun `CSR 是可验签的合法 PKCS10`() {
        val keyPair = ecKeyPair()
        val der = Pkcs10.build(keyPair, "synapse_device_key")

        val outer = DerReader(der).read()
        assertEquals(0x30, outer.tag)

        val inner = DerReader(outer.content)
        val requestInfo = inner.read()
        val algorithm = inner.read()
        val signatureField = inner.read()

        assertEquals(0x30, requestInfo.tag) // CertificationRequestInfo SEQUENCE
        assertEquals(0x30, algorithm.tag) // AlgorithmIdentifier SEQUENCE
        assertEquals(0x03, signatureField.tag) // signature BIT STRING
        assertEquals(0, signatureField.content[0].toInt())
        assertTrue(inner.isAtEnd, "CSR 顶层只能有三个元素")

        // 签名算法必须是 ecdsa-with-SHA256 且**不带 parameters**（RFC 5754）
        assertHex("300A06082A8648CE3D040302", algorithm.raw)

        val signature = signatureField.content.copyOfRange(1, signatureField.content.size)
        val requestInfoDer = Der.tlv(0x30, requestInfo.content)
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(keyPair.public)
            update(requestInfoDer)
        }
        assertTrue(verifier.verify(signature), "自签名必须能用自己的公钥验过")

        // 篡改一个字节后必须验不过（证明验的是真数据，不是恒定返回 true）
        val tampered = requestInfoDer.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        val badVerifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(keyPair.public)
            update(tampered)
        }
        assertFalse(badVerifier.verify(signature))
    }

    @Test
    fun `CSR 的 subject 是 CN 且 attributes 带 extensionRequest`() {
        val der = Pkcs10.build(ecKeyPair(), "synapse_device_key")
        val outer = DerReader(der).read()
        val inner = DerReader(outer.content)
        inner.read() // requestInfo
        inner.read() // algorithm
        inner.read() // signature

        val requestInfo = DerReader(outer.content).read()
        val cri = DerReader(requestInfo.content)
        assertEquals(0x02, cri.read().tag, "第一个字段是 version INTEGER")
        val subject = cri.read()
        val publicKey = cri.read()
        val attributes = cri.read()

        assertEquals(0x30, subject.tag)
        assertEquals(0x30, publicKey.tag)
        assertEquals(0xA0, attributes.tag, "attributes 是 [0] IMPLICIT")

        val attrHex = hex(attributes.content)
        assertTrue(attrHex.contains("2A864886F70D01090E"), "缺 extensionRequest OID")
        assertTrue(attrHex.contains("551D13"), "缺 basicConstraints OID")
    }

    @Test
    fun `CSR 的 PEM 头尾与目标应用一致`() {
        val der = Pkcs10.build(ecKeyPair(), "cn")
        val pem = Pkcs10.toPem(der)
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE REQUEST-----\n"))
        assertTrue(pem.endsWith("\n-----END CERTIFICATE REQUEST-----"))

        val body = pem.removePrefix("-----BEGIN CERTIFICATE REQUEST-----\n")
            .removeSuffix("\n-----END CERTIFICATE REQUEST-----")
            .trim()
            .lines()
        assertTrue(body.all { it.length <= 64 }, "PEM 每行不超过 64 列")
        assertEquals(hex(der), hex(Base64.getDecoder().decode(body.joinToString(""))))
    }

    // ------------------------------------------------------------------ challenge

    @Test
    fun `challenge 是 teePub 井号 reePub 的 SHA256`() {
        val expected = MessageDigest.getInstance("SHA-256")
            .digest("TEE#REE".toByteArray(Charsets.UTF_8))
        assertEquals(hex(expected), hex(DoubaoDeviceAttestation.computeChallenge("TEE", "REE")))
        assertEquals(32, DoubaoDeviceAttestation.computeChallenge("a", "b").size)
        // 分隔符固定是 #：只改 REE 一侧必须得出不同摘要
        assertFalse(
            hex(expected) == hex(DoubaoDeviceAttestation.computeChallenge("TEE", "REE1")),
        )
    }

    // ------------------------------------------------------------------ 协议装配

    @Test
    fun `verify 请求头缺真值时省略而不是发空值`() {
        val client = DoubaoDeviceAttestation()
        val prepared = DoubaoDeviceAttestation.Prepared(
            teePublicKey = "TEE",
            attestCerts = "CERTS",
            certChainSize = 3,
            hasKeyAttestation = true,
            challengeBase64 = "C",
        )
        val headers = client.buildVerifyHeaders(prepared, reePublicKey = "", clientCert = "")
        assertEquals("CERTS", headers[DoubaoDeviceAttestation.HEADER_ATTEST_CERTS])
        assertFalse(headers.containsKey(DoubaoDeviceAttestation.HEADER_REE_PUBLIC_KEY))
        assertFalse(headers.containsKey(DoubaoDeviceAttestation.HEADER_CLIENT_CERT))
        assertEquals("t=0;n=0", headers["x-tt-request-tag"])

        val full = client.buildVerifyHeaders(prepared, reePublicKey = "REE", clientCert = "CC")
        assertEquals("REE", full[DoubaoDeviceAttestation.HEADER_REE_PUBLIC_KEY])
        assertEquals("CC", full[DoubaoDeviceAttestation.HEADER_CLIENT_CERT])
    }

    @Test
    fun `裁决只看响应头且大小写不敏感`() {
        val client = DoubaoDeviceAttestation()
        val ok = client.parseVerifyResult(
            mapOf(
                "bd-ticket-guard-verify-public-key-result" to "0",
                "X-Tt-Logid" to "log-1",
            ),
        )
        assertTrue(ok.ok)
        assertEquals("log-1", ok.logId)

        // 真实链路里两端写法都出现过，必须都能认
        val upper = client.parseVerifyResult(
            mapOf(
                "Bd-Ticket-Guard-Verify-Public-Key-Result" to " 7 ",
                "x-tt-logid" to "log-2",
            ),
        )
        assertEquals(7, upper.errorCode)
        assertFalse(upper.ok)
        assertNull(upper.reason)
    }

    @Test
    fun `缺裁决位与非整数裁决位都要被识别成失败`() {
        val client = DoubaoDeviceAttestation()
        val missing = client.parseVerifyResult(mapOf("X-Tt-Logid" to "x"))
        assertNull(missing.errorCode)
        assertFalse(missing.ok)
        assertNotNull(missing.reason)

        val weird = client.parseVerifyResult(
            mapOf("bd-ticket-guard-verify-public-key-result" to "not-a-number"),
        )
        assertNull(weird.errorCode)
        assertNotNull(weird.reason)
    }

    // ------------------------------------------------------------------ 降级纪律

    @Test
    fun `JVM 上没有 AndroidKeyStore 时必须安静降级成空头`() {
        val tee = TeeKeyStore()
        assertFalse(tee.isAvailable(), "纯 JVM 不该有 AndroidKeyStore")
        assertFailsWith<TeeUnavailableException> { tee.containsAlias("x") }
        assertFailsWith<TeeUnavailableException> { tee.sign("x", byteArrayOf(1)) }

        val client = DoubaoDeviceAttestation(tee = tee)
        assertFalse(client.available())
        assertNull(client.prepare("REE"))
        // 关键纪律：拿不到真值就返回空头，绝不发假 guard 头
        assertTrue(client.asGuardHeaders(reePublicKey = { "REE" }).headers().isEmpty())
    }

    // ------------------------------------------------------------------ 工具

    private fun ecKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun assertHex(expected: String, actual: ByteArray) = assertEquals(expected, hex(actual))

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    /** 最小 DER 读取器：只够把 CSR 拆成三段。 */
    private class DerReader(private val bytes: ByteArray) {
        private var pos = 0
        val isAtEnd: Boolean get() = pos >= bytes.size

        class Item(val tag: Int, val raw: ByteArray, val content: ByteArray)

        fun read(): Item {
            val start = pos
            val tag = bytes[pos++].toInt() and 0xFF
            var len = bytes[pos++].toInt() and 0xFF
            if (len >= 0x80) {
                val n = len and 0x7F
                len = 0
                repeat(n) { len = (len shl 8) or (bytes[pos++].toInt() and 0xFF) }
            }
            val contentStart = pos
            pos += len
            return Item(tag, bytes.copyOfRange(start, pos), bytes.copyOfRange(contentStart, contentStart + len))
        }
    }
}
