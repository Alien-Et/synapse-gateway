package io.synapse.core.util

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 底层网络异常 → 人能看懂的提示。
 *
 * 界面上曾经出现过这种文本（商汤刷新额度）：
 * ```
 * failed to connect to xiaohuanxiong.com/180.184.181.237 (port 443) from
 * /172.19.0.1 (port 38276) after 30000ms: isConnected failed: ETIMEDOUT
 * ```
 * 这里锁住「必须给中文原因 + 可执行下一步，同时不丢原始文本」。
 */
class NetworkErrorTextTest {

    @Test
    fun `ETIMEDOUT 翻成连接超时并提示代理`() {
        val e = RuntimeException(
            "failed to connect to xiaohuanxiong.com/180.184.181.237 (port 443) from " +
                "/172.19.0.1 (port 38276) after 30000ms: isConnected failed: ETIMEDOUT (Connection timed out)",
        )
        val text = NetworkErrorText.describe(e, "xiaohuanxiong.com")
        assertTrue(text.contains("连接超时"), text)
        assertTrue(text.contains("代理"), "境外供应商超时最可能的原因是没开代理：$text")
        // 原始文本要保留，排查时还能看到具体是什么错
        assertTrue(text.contains("ETIMEDOUT"), "原始异常文本不能丢：$text")
    }

    @Test
    fun `SocketTimeoutException 同样识别为超时`() {
        val text = NetworkErrorText.describe(SocketTimeoutException("Read timed out"), "api.example.com")
        assertTrue(text.contains("连接超时"), text)
    }

    @Test
    fun `连接被拒给出去检查代理的指引`() {
        val text = NetworkErrorText.describe(ConnectException("Connection refused"), "api.example.com")
        assertTrue(text.contains("无法连接"), text)
        assertTrue(text.contains("代理"), text)
    }

    @Test
    fun `域名解析失败指向 DNS 或代理`() {
        val text = NetworkErrorText.describe(UnknownHostException("Unable to resolve host \"x.ai\""))
        assertTrue(text.contains("域名解析失败"), text)
    }

    @Test
    fun `TLS 握手失败提示证书问题`() {
        val text = NetworkErrorText.describe(SSLHandshakeException("certificate verify failed"))
        assertTrue(text.contains("安全连接"), text)
    }

    @Test
    fun `未知异常也加中文前缀并保留原文`() {
        val text = NetworkErrorText.describe(IllegalStateException("boom"))
        assertTrue(text.contains("请求上游失败"), text)
        assertTrue(text.contains("boom"), text)
        // 不能重复拼接原始文本两次
        assertFalse(text.count { it == 'b' } > 8, "原始文本不应重复出现：$text")
    }

    @Test
    fun `已经是中文可读文案时不额外套壳`() {
        // Loomy 这类 ApiException 已经把人话拼好了，再包一层「请求上游失败：」只会变糊
        val text = NetworkErrorText.describe(RuntimeException("登录状态已失效，需要重新登录"))
        assertTrue(text == "登录状态已失效，需要重新登录", text)
    }

    @Test
    fun `中文开头但带原生错误码时仍走翻译`() {
        val text = NetworkErrorText.describe(RuntimeException("连接超时 ETIMEDOUT"), "x.ai")
        assertTrue(text.contains("请检查网络"), "带原生错误码的仍应补上可执行指引：$text")
    }

    @Test
    fun `没有 message 的异常不会产出空白提示`() {
        val text = NetworkErrorText.describe(RuntimeException())
        assertTrue(text.isNotBlank(), "不能给用户一个空字符串")
        assertTrue(text.contains("请求上游失败") || text.contains("连接"), text)
    }
}
