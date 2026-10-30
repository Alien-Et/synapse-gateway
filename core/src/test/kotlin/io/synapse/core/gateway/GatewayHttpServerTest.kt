package io.synapse.core.gateway

import io.synapse.core.InMemoryKeyValueStore
import io.synapse.core.provider.AuthKind
import io.synapse.core.provider.ChatCall
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.Provider
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderModelCatalogView
import io.synapse.core.provider.UpstreamError
import java.io.ByteArrayOutputStream
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 直接对真实 HTTP 端口发请求，覆盖路由、鉴权与请求体解析。 */
class GatewayHttpServerTest {

    private fun withServer(
        configure: (GatewayEngine) -> Unit = {},
        block: (port: Int) -> Unit,
    ) {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        configure(engine)
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            block(server.listeningPort)
        } finally {
            server.stop()
        }
    }

    /**
     * 一个「存在但没账号」的供应商：
     * 声明「腾讯国内」标签，但测试里故意不往池子里放账号。
     *
     * 这样 `腾讯国内/xxx` 能通过标签解析（供应商已注册、已启用），
     * 然后在选号阶段没账号可用 → 走 503 no_healthy_account 那条路。
     */
    private class NoAccountProvider : Provider {
        override val id = "codebuddy"
        override val displayName = "腾讯"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            throw UnsupportedOperationException()
    }

    private fun send(port: Int, request: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 15_000
        socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    private fun post(port: Int, body: String, extraHeaders: String = ""): String = send(
        port,
        "POST /v1/chat/completions HTTP/1.1\r\n" +
            "Host: 127.0.0.1\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            extraHeaders +
            "Connection: close\r\n\r\n" + body,
    )

    @Test
    fun `标签指向的供应商没有账号时直接报标签无效`() {
        // 新的标签规则：标签必须指向一个「存在 + 已启用 + 有账号」的供应商。
        // 一个账号都没有 → 标签作废（400），不会跑到「无可用账号 503」那一步。
        withServer(configure = { engine ->
            engine.registry.register(NoAccountProvider())
        }) { port ->
            val response = post(port, """{"model":"腾讯国内/glm-5.2","messages":[{"role":"user","content":"hi"}]}""")
            assertTrue(response.startsWith("HTTP/1.1 400"), response.lineSequence().first())
            assertTrue(response.contains("model_not_routable"))
        }
    }

    @Test
    fun `账号全部停用时返回 503 no_healthy_account`() {
        // 账号存在但被停用：标签有效（size>0），选号时挑不出人 → 503。
        withServer(configure = { engine ->
            engine.registry.register(NoAccountProvider())
            engine.pool.upsert(ProviderAccount("codebuddy", "u1", "停用号", "{}"))
            engine.pool.setEnabled("codebuddy", "u1", false, "手动停用")
        }) { port ->
            val response = post(port, """{"model":"腾讯国内/glm-5.2","messages":[{"role":"user","content":"hi"}]}""")
            assertTrue(response.startsWith("HTTP/1.1 503"), response.lineSequence().first())
            assertTrue(response.contains("no_healthy_account"))
        }
    }

    @Test
    fun `不写标签时返回 400 model_not_routable`() {
        withServer { port ->
            val response = post(port, """{"model":"glm-5.2","messages":[{"role":"user","content":"hi"}]}""")
            assertTrue(response.startsWith("HTTP/1.1 400"), response.lineSequence().first())
            assertTrue(response.contains("model_not_routable"))
        }
    }

    @Test
    fun `分块请求体被正确解析而不是挂住`() {
        withServer(configure = { engine ->
            engine.registry.register(NoAccountProvider())
            engine.pool.upsert(ProviderAccount("codebuddy", "u1", "停用号", "{}"))
            engine.pool.setEnabled("codebuddy", "u1", false, "手动停用")
        }) { port ->
            val body = """{"model":"腾讯国内/glm-5.2","messages":[{"role":"user","content":"hi"}]}"""
            // 故意拆成两个分块，模拟流式发送请求体的客户端
            val first = body.substring(0, 20)
            val second = body.substring(20)
            val chunked = "POST /v1/chat/completions HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Type: application/json\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: close\r\n\r\n" +
                Integer.toHexString(first.toByteArray(Charsets.UTF_8).size) + "\r\n" + first + "\r\n" +
                Integer.toHexString(second.toByteArray(Charsets.UTF_8).size) + "\r\n" + second + "\r\n" +
                "0\r\n\r\n"
            val response = send(port, chunked)
            // 请求体若没被正确读出会退化成「请求体不是合法 JSON」或直接超时
            assertTrue(response.startsWith("HTTP/1.1 503"), response.lineSequence().first())
            assertTrue(response.contains("no_healthy_account"))
        }
    }

    @Test
    fun `不带账号时模型列表为空但接口正常`() {
        // 模型目录只在登录后拉取：没有账号时返回空列表，但 /v1/models 仍是合法的
        // OpenAI 结构（客户端不该因此报错）。
        withServer { port ->
            val response = send(
                port,
                "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
            )
            assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
            assertTrue(response.contains("\"object\":\"list\""), "应返回标准 list 结构")
            // 登录后才拉取的供应商（Trae / WorkBuddy）没有账号时不该冒充内置快照。
            assertTrue(
                !response.contains("\"trae-cn/") && !response.contains("\"trae-global/"),
                "Trae 无账号时不该列出模型",
            )
            assertTrue(
                !response.contains("\"codebuddy-cn/") && !response.contains("\"codebuddy-global/"),
                "WorkBuddy 无账号时不该列出模型",
            )
        }
    }

    @Test
    fun `关闭无 Key 调用后缺 Key 会被拒`() {
        withServer(configure = { engine ->
            engine.updateSettings(engine.settings().copy(apiKey = "secret", allowNoKey = false))
        }) { port ->
            val withoutKey = send(
                port,
                "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
            )
            assertTrue(withoutKey.startsWith("HTTP/1.1 401"), withoutKey.lineSequence().first())

            val withKey = send(
                port,
                "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer secret\r\nConnection: close\r\n\r\n",
            )
            assertTrue(withKey.startsWith("HTTP/1.1 200"), withKey.lineSequence().first())
        }
    }

    @Test
    fun `请求体不是 JSON 时返回 400`() {
        withServer { port ->
            val response = post(port, "not-json")
            assertTrue(response.startsWith("HTTP/1.1 400"), response.lineSequence().first())
            assertTrue(response.contains("invalid_request"))
        }
    }

    @Test
    fun `登录回调缺参数时返回可读的错误页`() {
        withServer { port ->
            val response = send(
                port,
                "GET /authorize?foo=1 HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
            )
            assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
            assertTrue(response.contains("text/html"))
            assertTrue(response.contains("登录失败"))
        }
    }

    @Test
    fun `普通响应带 CORS 头`() {
        withServer { port ->
            val response = send(
                port,
                "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
            )
            assertTrue(response.contains("Access-Control-Allow-Origin: *"), response)
        }
    }

    @Test
    fun `OPTIONS 预检直接放行`() {
        withServer { port ->
            val response = send(
                port,
                "OPTIONS /v1/chat/completions HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Origin: http://localhost\r\n" +
                    "Access-Control-Request-Method: POST\r\n" +
                    "Connection: close\r\n\r\n",
            )
            assertTrue(response.startsWith("HTTP/1.1 204"), response.lineSequence().first())
            assertTrue(response.contains("Access-Control-Allow-Origin: *"))
            assertTrue(response.contains("POST"))
        }
    }

    @Test
    fun `credits 端点返回 providers 列表结构`() {
        withServer { port ->
            val response = send(
                port,
                "GET /v1/credits HTTP/1.1\r\n" +
                    "Host: 127.0.0.1\r\n" +
                    "Connection: close\r\n\r\n",
            )
            assertTrue(response.startsWith("HTTP/1.1 200 OK"), response.lineSequence().first())
            assertTrue(response.contains("\"providers\":"))
        }
    }
}

class ChunkWriterTest {

    @Test
    fun `按 HTTP 分块编码写出并收尾`() {
        val out = ByteArrayOutputStream()
        val writer = ChunkWriter(out)
        writer.write("你好")
        writer.write("")
        writer.finish()

        val expected = Integer.toHexString("你好".toByteArray(Charsets.UTF_8).size) + "\r\n你好\r\n0\r\n\r\n"
        assertEquals(expected, out.toString("UTF-8"))
    }

    @Test
    fun `收尾后的写入会被丢弃，不会把分块帧写坏`() {
        val out = ByteArrayOutputStream()
        val writer = ChunkWriter(out)
        writer.finish()
        writer.write("迟到的数据")
        writer.finish()

        assertEquals("0\r\n\r\n", out.toString("UTF-8"))
    }
}

class SseResponseTest {

    @Test
    fun `流式响应自带 CORS 头并以分块结束`() {
        val out = ByteArrayOutputStream()
        val scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        try {
            SseResponse(scheduler) { writer -> writer.write("data: [DONE]\n\n") }.send(out)
        } finally {
            scheduler.shutdownNow()
        }

        val text = out.toString("UTF-8")
        assertTrue(text.contains("Content-Type: text/event-stream"), text)
        assertTrue(text.contains("Transfer-Encoding: chunked"), text)
        assertTrue(text.contains("Access-Control-Allow-Origin: *"), text)
        assertTrue(text.endsWith("0\r\n\r\n"), text)
        assertTrue(text.contains("data: [DONE]"), text)
    }
}
