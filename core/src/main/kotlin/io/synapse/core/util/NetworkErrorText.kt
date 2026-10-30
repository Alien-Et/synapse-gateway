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
 * 设计原则：**这里返回的字符串是直接给用户看的，所以一个英文字符都不许漏出去。**
 * 原始异常只进日志（[logText]），不进 UI。早期版本把原文追加在括号里，
 * 结果对话框里又冒出 `(port 443) from /172.19.0.1 ... ETIMEDOUT` 这一大段，
 * 等于白翻了。
 *
 * 与 [bodySnippet] 的分工：那个处理「上游有响应但没给可读消息」，
 * 这个处理「连响应都没拿到」。
 */
object NetworkErrorText {

    /**
     * 把异常翻成给用户看的中文提示。
     *
     * @param e 捕获到的异常。
     * @param host 上游主机名（可空）。**只用于选择提示语，不会把 host 之外的原始文本带出来**。
     */
    fun describe(e: Throwable, host: String? = null): String {
        val raw = (e.message ?: "").trim()

        // 已经是**人话**（含中文、且不像底层网络异常）就直接用，别再加一层外壳。
        // 典型：各家的 ApiException 已经把人话拼好了（「登录状态已失效，需要重新登录」），
        // 再包一层「请求上游失败：…」只会让提示变长变糊。
        if (raw.isNotEmpty() && containsChinese(raw) && !looksLikeNetworkRaw(raw)) return raw

        // ⚠️ 注意：下面所有分支都**不再**拼接 raw。
        // 面向用户的一句话里不允许出现英文异常原文，原始信息走 logText()。
        val service = serviceName(host)
        return when {
            // 超时：最常见，尤其境外供应商不走代理时
            e is SocketTimeoutException || raw.contains("ETIMEDOUT", true) ||
                raw.contains("timed out", true) || raw.contains("timeout", true) ->
                "连接${service}超时：上游在规定时间内没有响应。请检查网络，境外供应商可能需要开启代理"

            // 连不上：端口被拒、目标不可达
            e is ConnectException || raw.contains("ECONNREFUSED", true) ||
                raw.contains("Connection refused", true) ->
                "无法连接$service：对方拒绝了连接。请稍后重试，或检查代理设置是否正确"

            e is NoRouteToHostException || raw.contains("EHOSTUNREACH", true) ||
                raw.contains("ENETUNREACH", true) || raw.contains("unreachable", true) ->
                "网络不可达$service：当前网络无法访问该上游。请切换网络或开启代理后重试"

            // DNS：域名解析不了，常是代理/DNS 配置问题
            e is UnknownHostException || raw.contains("Unable to resolve host", true) ||
                raw.contains("No address associated with hostname", true) ->
                "域名解析失败$service：找不到上游服务器。请检查 DNS 或代理设置"

            // 握手失败：代理拦截、证书问题、或上游要求特定 TLS
            e is SSLException || raw.contains("SSL", true) || raw.contains("certificate", true) ->
                "安全连接建立失败$service：TLS 握手没有成功。若在代理后面，请检查代理是否劫持了证书"

            // 连接被中途掐断
            raw.contains("ECONNRESET", true) || raw.contains("Connection reset", true) ||
                raw.contains("broken pipe", true) || raw.contains("unexpected end of stream", true) ->
                "连接被中断$service：上游提前断开了连接，请稍后重试"

            // 兜底：连原文字段都透不出去，只能给一句通用提示 + 下一步。
            // 具体情况由调用方在日志里看 logText()。
            else -> "请求上游失败$service：暂时连不上该服务，请检查网络或稍后重试"
        }
    }

    /**
     * 给日志用的原始文本（**永远不要在 UI 上显示**）。
     *
     * 格式：「友好提示 ← 原始异常」，日志里两者都要有：前者说明用户看到什么，
     * 后者说明真实发生了什么。
     */
    fun logText(e: Throwable, host: String? = null): String {
        val raw = (e.message ?: e::class.simpleName ?: "").trim()
        val where = if (host.isNullOrBlank()) "" else " [$host]"
        return if (raw.isEmpty()) "${describe(e, host)}$where" else "${describe(e, host)}$where ← $raw"
    }

    /**
     * 「连接xx」里的 xx：有主机名就用短名（取最后两段，如 `xiaohuanxiong.com`），
     * 太长的话直接把整段塞进句子里会很难读。
     */
    private fun serviceName(host: String?): String {
        val h = host?.trim().orEmpty()
        if (h.isEmpty()) return "上游"
        val parts = h.split('.')
        val short = if (parts.size >= 2) parts.takeLast(2).joinToString(".") else h
        return "「$short」"
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

    /**
     * 从完整 URL 里取出主机名，喂给 [describe] 做「连接「xx」」里的 xx。
     *
     * 只取 host 段：base 里可能带端口和路径，整串塞进提示会很长。
     * 解析失败返回 null（提示语会退化成「连接上游」）。
     */
    fun hostOf(url: String?): String? {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return null
        return runCatching { java.net.URI(text).host }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
