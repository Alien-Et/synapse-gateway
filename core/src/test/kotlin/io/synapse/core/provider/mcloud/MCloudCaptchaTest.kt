package io.synapse.core.provider.mcloud

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 滑块（阿里云验证码 3.0）相关的回归测试。
 *
 * 所有字符串都来自 mCloud 13.2.5 的**真实抓包**，不是编的：
 * 改动这里之前先确认抓包还能对上，否则就是改坏了。
 */
class MCloudCaptchaTest {

    /** 未过滑块时上游的原样响应。 */
    private val needCaptchaJson = """
        {"success":false,"code":"200059554","message":"请先获取滑块拼图验证码","data":{"captchaType":2}}
    """.trimIndent()

    /** token 过期/复用时的响应（本次实测拿到 F019）。 */
    private val tokenRejectedJson = """
        {"success":false,"code":"200059553","message":"阿里云滑块验证失败，错误码: F019","data":{"captchaType":2}}
    """.trimIndent()

    /** 真实拿到过的 puzzleVerfycode（base64 后的 JSON）。 */
    private val realPuzzleToken =
        "eyJjZXJ0aWZ5SWQiOiJtNzdGeWh1V3RwIiwic2NlbmVJZCI6IjFybXc0MjFwIiwiaXNTaWduIjp0cnVlLCJzZWN1" +
            "cml0eVRva2VuIjoiNm9PbzdlNzJuQTYxdVZMaVpWS2tMY1ZRb25BeC83SU1UcGFBaEFtLzJ6YWt3ZVoybW43Znlr" +
            "YWNTWEQ5azN0bE9WNkZzZUp4V2E2K29UNUxSY1Z4TTlKUDRDWTRQemFkTkp6Z3pEOCt6SEc0R0h5QjgxNHRJMXdI" +
            "RVl3THZUb2QifQ=="

    @Test
    fun detectNeedCaptcha() {
        val c = MCloudCaptcha.detectChallenge(JsonParser.parseString(needCaptchaJson).asJsonObject)
        assertNotNull(c)
        assertEquals(ChallengeKind.NEED_CAPTCHA, c!!.kind)
        assertEquals(2, c.captchaType)
    }

    @Test
    fun detectTokenRejected() {
        val c = MCloudCaptcha.detectChallenge(JsonParser.parseString(tokenRejectedJson).asJsonObject)
        assertNotNull(c)
        assertEquals(ChallengeKind.TOKEN_REJECTED, c!!.kind)
        assertTrue(c.message.contains("F019"))
    }

    @Test
    fun normalResponseNotChallenge() {
        val ok = """{"success":true,"code":"0000","message":"请求成功","data":{"msgid":"abc"}}"""
        assertNull(MCloudCaptcha.detectChallenge(JsonParser.parseString(ok).asJsonObject))
    }

    @Test
    fun parsePuzzleToken() {
        val v = MCloudCaptcha.parsePuzzleToken(realPuzzleToken)
        assertNotNull(v)
        assertEquals("m77FyhuWtp", v!!.certifyId)
        assertEquals("1rmw421p", v.sceneId)
        assertTrue(v.isSign)
        assertTrue(v.securityToken.startsWith("6oOo7e72"))
    }

    @Test
    fun puzzleTokenRoundTrip() {
        val v = MCloudCaptcha.parsePuzzleToken(realPuzzleToken)!!
        assertEquals(realPuzzleToken, MCloudCaptcha.buildPuzzleToken(v))
    }

    @Test
    fun garbageTokenReturnsNull() {
        assertNull(MCloudCaptcha.parsePuzzleToken("!!!not-base64!!!"))
        assertNull(MCloudCaptcha.parsePuzzleToken(""))
    }

    @Test
    fun verifyUrlRoundTrip() {
        val device = MCloudDevice(deviceId = "7FCE333E3DF15B390502CEFC84E34115")
        val url = MCloudCaptcha.buildVerifyUrl("13800138000", device)
        assertTrue(url.startsWith(MCloudConstants.URL_CAPTCHA_PAGE + "?data="))

        // 把 %XX 还原再做 base64 解码
        val encoded = url.substringAfter("?data=")
        val decoded = java.net.URLDecoder.decode(encoded, "UTF-8")
        val json = String(java.util.Base64.getDecoder().decode(decoded), Charsets.UTF_8)
        val obj = JsonParser.parseString(json).asJsonObject

        assertEquals(2, obj.get("captchaType").asInt)
        val params = obj.getAsJsonObject("params")
        assertEquals("414", params.get("clientType").asString)
        assertEquals("13800138000", params.get("phoneNumber").asString)
        assertEquals("+86", params.get("nationCode").asString)
        assertEquals("0", params.get("additionalSpaceSelected").asString)
        val headers = obj.getAsJsonObject("headers")
        assertEquals("10000023", headers.get("x-yun-app-channel").asString)
        assertTrue(headers.get("x-yun-client-info").asString.startsWith("5|127.0.0.1|1|13.2.5|"))
    }

    @Test
    fun h5DeviceInfoShape() {
        val device = MCloudDevice(deviceId = "7fce333e3df15b390502cefc84e34115")
        val h5 = device.h5Info
        assertTrue(h5.startsWith("||3|7.17.7|"), "H5 串应以 ||3|7.17.7 开头，实际 ${h5}")
        // base64(机型名)：机型改成小米 18 Pro Max 后对应的值
        val expectedModelB64 = java.util.Base64.getEncoder()
            .encodeToString(MCloudConstants.DEVICE_MODEL.toByteArray(Charsets.UTF_8))
        assertTrue(h5.contains(expectedModelB64), "H5 串应含 base64 机型（$expectedModelB64）")
        // 原生串是另一套
        assertTrue(device.info.startsWith("5|127.0.0.1|1|13.2.5|"))
    }

    @Test
    fun h5KeyIsAes256() {
        assertEquals(32, MCloudConstants.KEY_H5_CAPTCHA.toByteArray(Charsets.UTF_8).size)
        // 与原生那把必须不同——混用会解密失败
        assertTrue(MCloudConstants.KEY_H5_CAPTCHA != MCloudConstants.KEY_BIZ)
        assertEquals(24, MCloudConstants.KEY_BIZ.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun h5KeyDecryptsRealRequest() {
        // HAR 里 index=232 的请求体（H5 页面发的），明文含 puzzleVerfycode
        val cipher =
                "MDEyMzQ1Njc4OWFiY2RlZiAW6UtjfBtIbXhS4Cpn9dEysDAQXWLuUpLs9XyKG1lnfIAmVh3obBzkFNcJA5jJNfwCXFFB3pnY+5Qx" +
                "xSzmChNyhCgGSgHgZXPePXNs0XQWEqyCWH8V5oq50y6ZFufkkGt/nT0SVt1FnuDYc4o1Rp4D8TfYJR37flVj1G90cUukhRG9TNPW" +
                "yJJWRMWK7mxIC+djiwon5rHdjMiAAYQVyZZjsT0RusCRxTa2zhLUDYOJeb0zhyT1V75+j/bC+2OeNWA5KM0UVTl8IyTcpp4vnimd" +
                "4KBCtTc0vJp/Kg/IobOy3nG8giVEKIVCFRPIY01xImBtYZaYWAUIo/WihQqzfYfLNaxk4M5yxthdyQ3A/ssFlqNVN33+tiCZRY3X" +
                "uO8S9kWSH2vL11HK27WvNhUMunx1ilu3LvC4SHyza68NhD04LQ5s5wQ2yxqZpknBpB1bRQR1X8Bdcn5CNYKD0sfCor51RHnOItSw" +
                "QF/vvIdWiG5mIUMGPP/LLpVxWP9jm5N/ZcMpoWOX9+JRganEC7unPL1/ebH09wSSosoW1iSOIEVqZY4XJtvsgm/eIeX2oPNc6Q1J" +
                "8x0YxdNW+E6a/sLW6F3t58gYono/Bwx6hDUhvjzJ"
        val plain = MCloudCrypto.decryptAesIv(cipher, MCloudConstants.KEY_H5_CAPTCHA)
        assertTrue(plain.contains("\"puzzleVerfycode\""))
        assertTrue(plain.contains("\"captchaType\":2"))
        assertTrue(plain.contains("13800138000"))
    }
}
