package io.synapse.core.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 查询参数解析。
 *
 * 这里最容易出事的点是 **URL 解码**：OAuth 的 authorization code 里必然带转义字符
 * （Google 的 `4/0AeanS0b...` 在 URL 里是 `4%2F0AeanS0b...`），
 * 不解码就会拿一串 `%2F` 去换 token，上游直接回 `invalid_grant`——
 * 用户看到的现象是「浏览器说授权成功了，应用里却登录失败」。
 */
class UrlUtilTest {

    @Test
    fun `Google 风格的 code 会被 URL 解码`() {
        val url = "http://127.0.0.1:51121/oauth-callback?state=abc&code=4%2F0AeanS0bXYZ%2Btest%3D&scope=email"
        assertEquals("4/0AeanS0bXYZ+test=", queryParam(url, "code"))
        assertEquals("abc", queryParam(url, "state"))
        assertEquals("email", queryParam(url, "scope"))
    }

    @Test
    fun `加号按表单语义还原成空格`() {
        val url = "http://127.0.0.1/x?error_description=user+denied+access"
        assertEquals("user denied access", queryParam(url, "error_description"))
    }

    @Test
    fun `中文参数的转义也能正确还原`() {
        val url = "http://127.0.0.1/x?msg=%E6%8E%88%E6%9D%83%E5%A4%B1%E8%B4%A5"
        assertEquals("授权失败", queryParam(url, "msg"))
    }

    @Test
    fun `hash 片段不会混进参数值`() {
        val url = "http://127.0.0.1/x?code=abc#frag"
        assertEquals("abc", queryParam(url, "code"))
    }

    @Test
    fun `缺参数与畸形转义都不抛异常`() {
        assertNull(queryParam("http://127.0.0.1/x", "code"))
        assertNull(queryParam("http://127.0.0.1/x?a=1", "code"))
        // 畸形转义（非法 %XY）退回原文，不能把登录流程变成异常
        assertEquals("100%zz", queryParam("http://127.0.0.1/x?code=100%zz", "code"))
    }
}
