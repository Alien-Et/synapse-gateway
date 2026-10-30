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
 * 界面上曾经出现过这种文本（商汤刷新额度，实拍截图里那条）：
 * ```
 * 连接超时：上游在规定时间内没有响应。请检查网络…（failed to connect to
 * xiaohuanxiong.com/180.184.181.237 (port 443) from /172.19.0.1 (port 49996)
 * after 30000ms: isConnected failed: ETIMEDOUT (Connection timed o)
 * ```
 * 前半句翻译好了，后半句又把原文整段贴了回去，等于白翻。
 *
 * 所以这里锁住最重要的一条：**describe() 的输出里一个原始网络报错片段都不许有**。
 * 原始文本走 logText()，只进日志。
 */
class NetworkErrorTextTest {

    private val rawTimeout = "failed to connect to xiaohuanxiong.com/180.184.181.237 (port 443) from " +
        "/172.19.0.1 (port 49996) after 30000ms: isConnected failed: ETIMEDOUT (Connection timed out)"

    @Test
    fun `超时提示里绝不出现原始英文异常`() {
        val e = RuntimeException(rawTimeout)
        val text = NetworkErrorText.describe(e, "xiaohuanxiong.com")
        assertTrue(text.contains("连接"), text)
        assertTrue(text.contains("代理"), "超时最可能的原因是没开代理：$text")
        // 关键断言：英文原文不许漏到 UI
        assertFalse(text.contains("ETIMEDOUT"), "原始错误码不能出现在给用户的提示里：$text")
        assertFalse(text.contains("172.19.0.1"), "内网 IP 不能出现在给用户的提示里：$text")
        assertFalse(text.contains("port"), "端口信息不能出现在给用户的提示里：$text")
        assertFalse(text.contains("isConnected"), text)
        // 不能残留未闭合的中文括号（早期版本把原文截断后塞进括号，留下一个「（」）
        assertFalse(text.contains("（failed"), text)
    }

    @Test
    fun `logText 同时保留友好提示与原始异常`() {
        val text = NetworkErrorText.logText(RuntimeException(rawTimeout), "xiaohuanxiong.com")
        assertTrue(text.contains("连接"), "日志里要有给人看的结论：$text")
        assertTrue(text.contains("ETIMEDOUT"), "日志里必须保留原始异常供排查：$text")
    }

    @Test
    fun `主机名以短名形式出现在提示里`() {
        val text = NetworkErrorText.describe(SocketTimeoutException("Read timed out"), "api.deep.example.com")
        assertTrue(text.contains("example.com"), text)
        // 完整长域名不直接塞进句子，避免提示过长
        assertFalse(text.contains("api.deep.example.com"), text)
    }

    @Test
    fun `没有主机名时退化成连接上游`() {
        val text = NetworkErrorText.describe(SocketTimeoutException("Read timed out"))
        assertTrue(text.contains("上游"), text)
    }

    @Test
    fun `连接被拒给出去检查代理的指引`() {
        val text = NetworkErrorText.describe(ConnectException("Connection refused"), "api.example.com")
        assertTrue(text.contains("无法连接"), text)
        assertTrue(text.contains("代理"), text)
        assertFalse(text.contains("Connection refused"), text)
    }

    @Test
    fun `域名解析失败指向 DNS 或代理`() {
        val text = NetworkErrorText.describe(UnknownHostException("Unable to resolve host \"x.ai\""))
        assertTrue(text.contains("域名解析失败"), text)
        assertFalse(text.contains("Unable to resolve"), text)
    }

    @Test
    fun `TLS 握手失败提示证书问题`() {
        val text = NetworkErrorText.describe(SSLHandshakeException("certificate verify failed"))
        assertTrue(text.contains("安全连接"), text)
        assertFalse(text.contains("certificate"), text)
    }

    @Test
    fun `已经是中文可读文案时不额外套壳`() {
        // Loomy 这类 ApiException 已经把人话拼好了，再包一层「请求上游失败：」只会变糊
        val text = NetworkErrorText.describe(RuntimeException("登录状态已失效，需要重新登录"))
        assertTrue(text == "登录状态已失效，需要重新登录", text)
    }

    @Test
    fun `中文开头但带原生错误码时仍走翻译且不透出原文`() {
        val text = NetworkErrorText.describe(RuntimeException("连接超时 ETIMEDOUT"), "x.ai")
        assertTrue(text.contains("代理"), "带原生错误码的仍应补上可执行指引：$text")
        assertFalse(text.contains("ETIMEDOUT"), "但原文不能透出：$text")
    }

    @Test
    fun `未知异常也给中文提示且不含原文`() {
        val text = NetworkErrorText.describe(IllegalStateException("boom"))
        assertTrue(text.contains("请求上游失败"), text)
        assertFalse(text.contains("boom"), "未知异常原文也不该出现在 UI：$text")
    }

    @Test
    fun `没有 message 的异常不会产出空白提示`() {
        val text = NetworkErrorText.describe(RuntimeException())
        assertTrue(text.isNotBlank(), "不能给用户一个空字符串")
    }
}
