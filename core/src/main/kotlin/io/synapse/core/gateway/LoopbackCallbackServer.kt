package io.synapse.core.gateway

import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 临时本地回调服务：登录在系统浏览器里完成后，服务端会把浏览器重定向到本机端口，
 * 由它接住回调并交给网关换 token。
 *
 * 之所以不用内置 WebView：登录页在部分设备的 WebView 上渲染异常（实测有整页缩放错乱、
 * 输入框文字镜像），换系统浏览器可彻底绕开设备 WebView 的实现差异；
 * 代价是登录期间临时占用一个本地端口。
 *
 * 监听地址优先 `127.0.0.1`。部分设备的浏览器 / 网络栈会先把 `localhost` 解析成 IPv6 的
 * `::1`，只绑 IPv4 时第一次连接就是 connection refused，浏览器直接显示「无法访问此网站」
 * ——用户看起来就像「网不好」。因此调用方可以按 [HOSTS] 依次尝试。
 */
internal class LoopbackCallbackServer(
    hostname: String,
    port: Int,
    private val path: String,
    private val onCallback: (callbackUrl: String) -> String,
) : NanoHTTPD(hostname, port) {

    fun startServer() {
        start(SOCKET_READ_TIMEOUT_MS, false)
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        if (uri != path.trimEnd('/')) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
        }
        val query = session.queryParameterString.orEmpty()
        // 用实际监听端口（构造时传 0 表示由系统分配），否则回调链接里会是 0
        val callbackUrl = "http://127.0.0.1:$listeningPort$path" + if (query.isEmpty()) "" else "?$query"
        // 无论成功失败都给浏览器一个正经结果页，别让用户停在空白页上猜
        val html = runCatching { onCallback(callbackUrl) }
            .getOrElse { error -> errorPage(error.message ?: "登录处理异常") }
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun errorPage(message: String): String = """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>登录失败</title></head>
        <body style="font:16px -apple-system,sans-serif;padding:32px;line-height:1.6">
          <h2>登录失败</h2><p>${escapeHtml(message)}</p>
          <p>可以关闭本页并回到应用重试。</p>
        </body></html>
    """.trimIndent()

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    companion object {
        private const val SOCKET_READ_TIMEOUT_MS = 10_000

        /**
         * 依次尝试的监听地址：先 IPv4 回环，再 IPv6 回环。
         * 都失败时抛异常，由调用方给出「端口被占用」这类可读错误。
         */
        val HOSTS = listOf("127.0.0.1", "::1")

        /** 端口当前是否可监听（用来提前判断是不是端口被占用）。 */
        fun isPortAvailable(port: Int): Boolean = try {
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).use { true }
        } catch (_: IOException) {
            false
        }
    }
}
