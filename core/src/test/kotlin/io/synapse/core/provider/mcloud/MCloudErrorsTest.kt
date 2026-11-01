package io.synapse.core.provider.mcloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 上游错误文案提取。
 *
 * 移动云盘有三套报错形态，最容易踩的是「验证码不正确」藏在 `third.thirdDesc` 里
 * （HTTP 200 + resultCode 501），只看 `resultDesc` 会得到一句没用的「调用服务失败」。
 */
class MCloudErrorsTest {

    @Test
    fun `二级错误 thirdDesc 优先于笼统的 resultDesc`() {
        val body = """
            {"resultCode":"501","resultDesc":"调用服务失败",
             "third":{"thirdCode":"9441","thirdDesc":"验证码不正确","platformName":"USER_DOMAIN"}}
        """.trimIndent()
        assertEquals("验证码不正确", MCloudErrors.extractMessage(body))
    }

    @Test
    fun `对话接口的 error 对象能被识别`() {
        val body = """
            {"error":{"code":"01000002","message":"无效的ApiKey"},
             "code":"01000002","message":"无效的ApiKey"}
        """.trimIndent()
        assertEquals("无效的ApiKey", MCloudErrors.extractMessage(body))
    }

    @Test
    fun `会员中心普通错误用 resultDesc`() {
        assertEquals("参数解密失败", MCloudErrors.extractMessage("""{"resultCode":"-1","resultDesc":"参数解密失败"}"""))
    }

    @Test
    fun `报表片段不会被当作人话展示`() {
        assertNull(MCloudErrors.extractMessage("""{"resultDesc":"{\"a\":1}"}"""))
        assertNull(MCloudErrors.extractMessage("<html>502</html>"))
        assertNull(MCloudErrors.extractMessage(""))
        assertNull(MCloudErrors.extractMessage("不是 JSON"))
    }

    @Test
    fun `resultCode 0 才算成功`() {
        assertTrue(MCloudErrors.isOk("""{"resultCode":"0","data":{}}"""))
        assertFalse(MCloudErrors.isOk("""{"resultCode":"501"}"""))
        assertFalse(MCloudErrors.isOk("""{"code":"01000002"}"""))
    }

    @Test
    fun `requireData 在失败时抛异常并带上正确分类`() {
        val e = kotlin.runCatching {
            MCloudErrors.requireData(200, """{"resultCode":"01000002","message":"无效的ApiKey"}""", "取密钥")
        }.exceptionOrNull() as MCloudApiException
        assertEquals(MCloudErrorKind.SESSION_DEAD, e.kind)
        assertEquals("无效的ApiKey", e.message)

        val quota = kotlin.runCatching {
            MCloudErrors.requireData(200, """{"resultCode":"500","resultDesc":"余额不足"}""", "对话")
        }.exceptionOrNull() as MCloudApiException
        assertEquals(MCloudErrorKind.QUOTA, quota.kind)
    }

    @Test
    fun `HTTP 5xx 归为服务端错误`() {
        val e = kotlin.runCatching {
            MCloudErrors.requireData(503, "gateway timeout", "对话")
        }.exceptionOrNull() as MCloudApiException
        assertEquals(MCloudErrorKind.SERVER, e.kind)
    }
}
