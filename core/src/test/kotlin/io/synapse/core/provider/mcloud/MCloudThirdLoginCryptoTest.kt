package io.synapse.core.provider.mcloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * thirdlogin（短信登录换凭证）专用信封的回归测试。
 *
 * 明文/密文均来自 2026-11-01 用算法助手 Hook 移动云盘 App 真机登录抓到的
 * `CommonEncryptDecryInterceptor.encryptIntercept`（加密前明文）与
 * `RealInterceptorChain.proceed`（加密后密文）—— 是不可再生的实测样本，
 * 改加密实现时这组断言就是唯一防线。
 */
class MCloudThirdLoginCryptoTest {

    /** Hook 实测的 thirdlogin 请求明文（186 字节，字段字母序 + extInfo 非字母序）。 */
    private val capturedPlain =
        """{"clienttype":"414","cpid":"58","dycpwd":"657407","extInfo":{"ifOpenAccount":"1",""" +
            """"additionalSpaceSelected":"1"},"loginMode":"1","msisdn":"18529186660","pintype":"8",""" +
            """"version":"30132508"}"""

    /** Hook 实测的 thirdlogin 请求密文（384 hex = 192 字节）。 */
    private val capturedHex =
        "08eb28cad1fca313a7cca31379d63b946ddf6f17127fffedb8034b3eabcfce9d2b8d8598f82fa16c987f6bbc5ce2ed94018fa5" +
            "4efe0bc3c72ee407f8815a6fab2bb75a2ba92a3742650228d6df2d3743a3293a90ba6b1c381cb1cae0a579bcb802d8a8dd9132" +
            "d883fab6b4b7a4beb6def3ddf9ebfb1f084d8f0243ca32586ea5aa4822b5de824854ce614ecda21eac3b4e08eefc1b83072eda" +
            "8aa0a4664092dc115bfc0e392a7dac8762628cf8b38b17a5480ecf5510c7bd6afc715e2a4abcdb"

    @Test
    fun `encrypt reproduces captured ciphertext byte for byte`() {
        val out = MCloudCrypto.encryptThirdLogin(capturedPlain.toByteArray(Charsets.UTF_8))
        assertEquals(capturedHex, out, "加密输出必须与 Hook 到的真机密文逐字节一致")
    }

    @Test
    fun `decrypt round-trips the captured payload`() {
        val inner = MCloudCrypto.decryptThirdLoginInner(capturedHex)
        assertNotNull(inner, "内层解密应命中可读 JSON")
        assertEquals(capturedPlain, inner.trim(), "解密应还原出原始明文")
    }

    @Test
    fun `encrypt uses AES-128 ECB with libdd rlAesKey`() {
        // 密钥来源：libdd.so 数据段 rlAesKey（算法助手 v7 从 base+0x3bb5a0 dump）
        assertEquals("gB^gGdQHX1l+XteY", MCloudConstants.KEY_RL_AES)
        assertEquals(16, MCloudConstants.KEY_RL_AES.toByteArray(Charsets.US_ASCII).size)
    }

    @Test
    fun `payload length stays stable for real login inputs`() {
        // msisdn 11 位 + dycpwd 6 位时 payload 恒 186 字节 → PKCS7 填 6 → 旋转量恒 5。
        // 字段增删会破坏这个不变量，测试在这里钉住。
        val bytes = capturedPlain.toByteArray(Charsets.UTF_8)
        assertEquals(186, bytes.size)
        val encrypted = MCloudCrypto.encryptThirdLogin(bytes)
        assertEquals(384, encrypted.length, "hex 输出恒 384 字符")
        assertTrue(encrypted.all { it in "0123456789abcdef" }, "body 必须是全小写 hex")
    }
}
