package io.synapse.core.util

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 把底层 Java 网络异常翻译成**人能看懂的一句话**。
 *
 * 之前所有供应商都是 `e.message ?: "失败"` 直接往外抛，于是界面上出现的是：
 *
 * ```
 * failed to connect to xiaohuanxiong.com/180.184.181.237 (port 443) from
 * /172.19.0.1 (port 38276) after 30000ms: isConnected failed: ETIMEDOUT
 * ```
 *
 * 这句话对排查有用，但对用户毫无意义——他既不知道发生了什么，也不知道该干什么。
 *
 * 这里做两件事：
 * 1. **给用户看的**：一句中文 + 可执行的下一步（检查网络 / 挂代理 / 稍后重试）；
 * 2. **给排查看的**：原始异常文本压进括号里，仍然保留（verbose 日志不受影响）。
 *
 * 与 [bodySnippet] 的分工：那个处理「上游有响应但没给可读消息」，
 * 这个处理「连响应都没拿到」。两者可以组合使用。
 */
object NetworkErrorText {

    /**
     * 把异常翻成友好文案。
     *
     * @param e 捕获到的异常。
     * @param host 上游主机名（可空；有的话提示里会带上，方便用户判断是不是代理问题）。
     */
    fun describe(e: Throwable, host: String? = null): String {
        val raw = (e.message ?: e::class.simpleName ?: "").trim()
        val where = if (host.isNullOrBlank()) "" else "（$host）"

        // 已经是**人话**（含中文、且不像底层网络异常）就直接用，别再加一层外壳。
        // 典型：各家的 ApiException 已经把人话拼好了（「登录状态已失效，需要重新登录」），
        // 再包一层「请求上游失败：…」只会让提示变长变糊。
        if (raw.isNotEmpty() && containsChinese(raw) && !looksLikeNetworkRaw(raw)) return raw

        val friendly = when {
            // 超时：最常见，尤其境外供应商不走代理时
            e is SocketTimeoutException || raw.contains("ETIMEDOUT", true) ||
                raw.contains("timed out", true) || raw.contains("timeout", true) ->
                "连接超时$where：上游在规定时间内没有响应。请检查网络，境外供应商可能需要开启代理"

            // 连不上：端口被拒、目标不可达
            e is ConnectException || raw.contains("ECONNREFUSED", true) ||
                raw.contains("Connection refused", true) ->
                "无法连接到上游$where：对方拒绝了连接。请稍后重试，或检查代理设置是否正确"

            e is NoRouteToHostException || raw.contains("EHOSTUNREACH", true) ||
                raw.contains("ENETUNREACH", true) || raw.contains("unreachable", true) ->
                "网络不可达$where：当前网络无法访问该上游。请切换网络或开启代理后重试"

            // DNS：域名解析不了，常是代理/DNS 配置问题
            e is UnknownHostException || raw.contains("Unable to resolve host", true) ||
                raw.contains("No address associated with hostname", true) ->
                "域名解析失败$where：找不到上游服务器。请检查 DNS 或代理设置"

            // 握手失败：代理拦截、证书问题、或上游要求特定 TLS
            e is SSLException || raw.contains("SSL", true) || raw.contains("certificate", true) ->
                "安全连接建立失败$where：TLS 握手没成功。若在代理后面，请检查代理是否劫持了证书"

            // 连接被中途掐断
            raw.contains("ECONNRESET", true) || raw.contains("Connection reset", true) ||
                raw.contains("broken pipe", true) || raw.contains("unexpected end of stream", true) ->
                "连接被中断$where：上游提前断开了连接。请稍后重试"

            // 其它：不硬编，保留原文但加上引导，至少不让界面只剩一串英文
            else -> "请求上游失败$where：${raw.ifEmpty { "未知错误" }}"
        }

        // 原始文本压进括号：既方便排查，也不会让界面彻底丢失细节。
        // 若友好文案已经包含了原始文本（else 分支），就不再重复。
        return if (raw.isEmpty() || friendly.contains(raw)) friendly else "$friendly（${raw.take(160)}）"
    }

    /** 是否含中日韩字符——用来判断这条消息是不是已经被人写成了可读文案。 */
    private fun containsChinese(text: String): Boolean =
        text.any { ch -> ch.code in 0x4E00..0x9FFF }

    /**
     * 是否像「底层网络异常原文」。
     *
     * 有些异常消息是中文开头、后面跟着原生错误码（如「连接超时 ETIMEDOUT」），
     * 这种仍然值得走一遍翻译拿到「该怎么做」的指引。
     */
    private fun looksLikeNetworkRaw(text: String): Boolean {
        val markers = listOf(
            "ETIMEDOUT", "ECONNREFUSED", "ECONNRESET", "EHOSTUNREACH", "ENETUNREACH",
            "failed to connect", "Connection refused", "Connection reset",
            "Unable to resolve host", "timed out", "SSL", "certificate",
        )
        return markers.any { text.contains(it, ignoreCase = true) }
    }
}
