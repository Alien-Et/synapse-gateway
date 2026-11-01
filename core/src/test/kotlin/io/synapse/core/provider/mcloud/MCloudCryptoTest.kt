package io.synapse.core.provider.mcloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 国密加解密的回归测试。
 *
 * 这里的断言全部来自对 mCloud 13.2.5 的真实抓包与 smali 逆向，任何一条挂掉都意味着
 * 登录链路会整段失效（上游只回一句「参数错误」或空响应，非常难查）。
 */
class MCloudCryptoTest {

    /** `GET /adaptor/encrypt/publicKey?appId=10000000003` 的真实返回。 */
    private val appPubKey =
        "0438e805f1bbef2bb4d46dd4d514cce4c2df25820311a2edb4d891d0e98c3b524e3a89c724a7e5cf4a2d697383db405fa" +
            "f7a83bbc258afe1da8f9edff1feec3e1a"

    @Test
    fun `SM2 密文长度必须是 113 字节并带 04 前缀`() {
        // 抓包铁证：yun-encrypt-key 一直是 113 字节、04 开头
        // = 04(1) + C1(64) + C3(32) + C2(16)，对应明文正好 16 字符
        val hex = MCloudCrypto.sm2EncryptHex("0123456789abcdef", appPubKey)
        assertEquals(113, hex.length / 2, "SM2 密文字节数不对，说明曲线/模式与上游不一致")
        assertTrue(hex.startsWith("04"), "C1 点必须以 04 开头，实际：${hex.take(4)}")
    }

    @Test
    fun `SM2 每次加密结果都不同（随机 k）`() {
        val a = MCloudCrypto.sm2EncryptHex("0123456789abcdef", appPubKey)
        val b = MCloudCrypto.sm2EncryptHex("0123456789abcdef", appPubKey)
        assertNotEquals(a, b, "SM2 有随机因子，两次密文必须不同")
    }

    @Test
    fun `SM4-ECB 能加密再解回原文`() {
        val key = "AbCdEfGh12345678".toByteArray(Charsets.US_ASCII)
        val plain = """{"phone":"13800138000","type":2}"""
        val hex = MCloudCrypto.sm4EncryptHex(key, plain.toByteArray(Charsets.UTF_8))
        // 密文长度必须是 16 的整数倍（PKCS7 会补齐整块）
        assertEquals(0, hex.length / 2 % 16, "SM4 密文长度必须是 16 的倍数")
        assertEquals(plain, MCloudCrypto.sm4DecryptHex(key, hex))
    }

    @Test
    fun `SM4 固定向量：空串也必须补出一整块`() {
        val key = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
        val hex = MCloudCrypto.sm4EncryptHex(key, ByteArray(0))
        assertEquals(16, hex.length / 2, "PKCS7 对空数据要补满一块")
    }

    @Test
    fun `encryptGuomi 产出可直接放进请求的结构`() {
        val env = MCloudCrypto.encryptGuomi("""{"a":1}""", appPubKey)
        assertEquals(113, env.keyHeader.length / 2, "yun-encrypt-key 长度必须 113")
        assertTrue(env.keyHeader.startsWith("04"))
        assertEquals(0, env.body.length / 2 % 16, "body 必须是 SM4 整块密文")
        assertEquals(16, env.keyHex.length, "密钥必须正好 16 字符")
        // 用本次密钥能把 body 解回去，证明 key 与 body 是配套的
        assertEquals(
            """{"a":1}""",
            MCloudCrypto.sm4DecryptHex(env.keyHex.toByteArray(Charsets.US_ASCII), env.body),
        )
    }

    @Test
    fun `两次 encryptGuomi 用不同密钥`() {
        val a = MCloudCrypto.encryptGuomi("""{"a":1}""", appPubKey)
        val b = MCloudCrypto.encryptGuomi("""{"a":1}""", appPubKey)
        assertNotEquals(a.keyHex, b.keyHex, "每次请求必须换新密钥，复用一个会被风控拦")
        assertNotEquals(a.body, b.body)
    }

    @Test
    fun `RSA 加密后能用同一对密钥解回原文`() {
        val kp = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pubB64 = java.util.Base64.getEncoder().encodeToString(kp.public.encoded)
        val ct = MCloudCrypto.encryptRsa("18529186660", pubB64)
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, kp.private)
        assertEquals("18529186660", String(cipher.doFinal(java.util.Base64.getDecoder().decode(ct))))
    }

    @Test
    fun `AES-CBC 随机 IV 前置能往返`() {
        val key = "7fd8071cabc748a9"
        val plain = """{"k":"v"}"""
        val enc = MCloudCrypto.encryptAesIv(plain, key)
        // 两次结果不同（随机 IV）
        assertNotEquals(enc, MCloudCrypto.encryptAesIv(plain, key))
        assertEquals(plain, MCloudCrypto.decryptAesIv(enc, key))
    }

    @Test
    fun `坏公钥要抛异常而不是静默返回空`() {
        val failed = runCatching { MCloudCrypto.sm2EncryptHex("x", "not-a-key") }.isFailure
        assertTrue(failed, "非法公钥必须抛错，否则会把空密文发给上游")
    }

    @Test
    fun sm4Base64RoundTrip() {
        // thirdlogin 实测：请求体是 hex，响应体是 base64——同一把会话密钥，两种编码
        val key = "abcdef0123456789".toByteArray(Charsets.US_ASCII)
        val plain = "{\"success\":true,\"code\":\"0000\"}"
        val encHex = MCloudCrypto.sm4EncryptHex(key, plain.toByteArray(Charsets.UTF_8))
        assertEquals(plain, MCloudCrypto.sm4DecryptHex(key, encHex))
        val encB64 = java.util.Base64.getEncoder().encodeToString(
            org.bouncycastle.util.encoders.Hex.decode(encHex),
        )
        assertEquals(plain, MCloudCrypto.sm4DecryptBase64(key, encB64))
    }
}
