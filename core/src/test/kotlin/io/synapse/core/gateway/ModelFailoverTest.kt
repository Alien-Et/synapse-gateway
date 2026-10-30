package io.synapse.core.gateway

import io.synapse.core.InMemoryKeyValueStore
import io.synapse.core.provider.AggregatedChatCall
import io.synapse.core.provider.AuthKind
import io.synapse.core.provider.ChatCall
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.FailedChatCall
import io.synapse.core.provider.Provider
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderModel
import io.synapse.core.provider.ProviderModelCatalogView
import io.synapse.core.provider.UpstreamError
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 标签路由的跨供应商隔离回归测试。
 *
 * **原来这个文件验证的是「首选供应商失败后自动切到支持同款模型的备用供应商」——
 * 那套跨供应商 Failover 已整体删除**（主人要求「标签只绑定对应供应商，
 * 选了哪家供应商模型就路由到哪家」）。
 *
 * 现在它锁住相反的行为：写了哪家的标签，请求就只会在那家；那家失败就直接报错，
 * **不会**偷偷把请求改发给拥有同名模型的另一家。
 */
class ModelFailoverTest {

    private inner class TestProvider(
        override val id: String,
        private val availableModels: List<String>,
        private val shouldFail: Boolean = false,
        private val responseContent: String = "ok",
    ) : Provider {
        override val displayName = id
        // 标签路由：替身供应商用自己的 id 当标签
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(
            models = availableModels.map { ProviderModel(it, it) },
            fromFallback = false,
            error = "",
        )
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
            if (shouldFail) {
                return FailedChatCall(500, "上游服务器暂时故障")
            }
            return AggregatedChatCall(
                200,
                """{"id":"c","object":"chat.completion","created":1,"model":"$id","choices":[{"index":0,"message":{"role":"assistant","content":"$responseContent"},"finish_reason":"stop"}]}""",
            )
        }
    }

    private fun post(engine: GatewayEngine, body: String): Pair<Int, String> {
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        return try {
            val conn = URL("http://127.0.0.1:${server.listeningPort}/v1/chat/completions")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.write(body.toByteArray(Charsets.UTF_8))
            val code = conn.responseCode
            val text = runCatching { conn.inputStream.bufferedReader().readText() }
                .getOrElse { conn.errorStream?.bufferedReader()?.readText().orEmpty() }
            code to text
        } finally {
            server.stop()
        }
    }

    @Test
    fun `首选供应商失败时不会改发给拥有同款模型的另一家`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // 两家都"支持" glm-5.2，A 上游崩了（500）
        engine.registry.register(TestProvider("codebuddy", listOf("glm-5.2"), shouldFail = true))
        engine.registry.register(
            TestProvider("trae", listOf("glm-5.2"), shouldFail = false, responseContent = "来自B的回复"),
        )
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "账号A", "{}"))
        engine.pool.upsert(ProviderAccount("trae", "u2", "账号B", "{}"))

        val (code, resp) = post(engine, """{"model":"腾讯国内/glm-5.2","messages":[{"role":"user","content":"hi"}]}""")

        // A 失败就是失败，绝不因为 B 也有 glm-5.2 就悄悄改发 B
        assertTrue(code >= 400, "标签指向 A 时不得改发 B，实际状态码 $code：$resp")
        assertTrue(
            !resp.contains("来自B的回复"),
            "响应绝不能来自另一家供应商：$resp",
        )
    }

    @Test
    fun `标签指向的供应商没有账号时不会切换到有账号的另一家`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // A 支持 gpt-4o 但没账号；B 支持 gpt-4o 且有账号
        engine.registry.register(TestProvider("codebuddy", listOf("gpt-4o")))
        engine.registry.register(
            TestProvider("trae", listOf("gpt-4o"), responseContent = "来自B的成功响应"),
        )
        engine.pool.upsert(ProviderAccount("trae", "u2", "账号B", "{}"))

        val (code, resp) = post(engine, """{"model":"腾讯国内/gpt-4o","messages":[{"role":"user","content":"hi"}]}""")

        // A 没账号 → 标签作废 → 400，而不是平滑切到 B
        assertEquals(400, code, "A 没账号时应直接报错，而不是切到 B：$resp")
        assertTrue(resp.contains("model_not_routable"), "应报模型无法路由：$resp")
        assertTrue(!resp.contains("来自B的成功响应"), "响应绝不能来自另一家：$resp")
    }

    @Test
    fun `写了哪家的标签就由哪家处理`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(TestProvider("codebuddy", listOf("glm-5.2"), responseContent = "来自A的回复"))
        engine.registry.register(TestProvider("trae", listOf("glm-5.2"), responseContent = "来自B的回复"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "账号A", "{}"))
        engine.pool.upsert(ProviderAccount("trae", "u2", "账号B", "{}"))

        val (codeA, respA) = post(engine, """{"model":"腾讯国内/glm-5.2","messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(200, codeA)
        assertTrue(respA.contains("来自A的回复"), "写 A 就该由 A 答复：$respA")

        val (codeB, respB) = post(engine, """{"model":"字节国内/glm-5.2","messages":[{"role":"user","content":"hi"}]}""")
        assertEquals(200, codeB)
        assertTrue(respB.contains("来自B的回复"), "写 B 就该由 B 答复：$respB")
    }
}
