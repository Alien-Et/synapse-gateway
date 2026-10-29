package dev.aigw.core.failover

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewayHttpServer
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.usage.CallStatus
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FailoverIntegrationTest {

    private inner class ConfigurableProvider(
        override val id: String,
        private val supportedModels: List<String>,
        var behavior: (account: ProviderAccount, body: String) -> ChatCall,
    ) : Provider {
        override val displayName = "Provider-$id"
        // 标签路由：替身供应商用自己的 id 当标签
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(
            models = supportedModels.map { ProviderModel(it, it) },
            fromFallback = false,
            error = "",
        )
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String): UpstreamError = when (status) {
            400 -> UpstreamError(ErrorKind.CLIENT, "invalid_request_error")
            401 -> UpstreamError(ErrorKind.SESSION_DEAD, "unauthorized")
            429 -> UpstreamError(ErrorKind.SOFT_RATE, "rate limit")
            else -> UpstreamError(ErrorKind.SERVER, "upstream error $status")
        }
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            behavior(account, openAiBody)
    }

    private fun post(serverPort: Int, model: String, bodyContent: String = "hi"): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$serverPort/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        val req = """{"model":"$model","messages":[{"role":"user","content":"$bodyContent"}]}"""
        conn.outputStream.write(req.toByteArray(Charsets.UTF_8))
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        return status to text
    }

    @Test
    fun `配置的跨供应商优先级表不再生效，标签写哪家就只去哪家`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        var p1Calls = 0
        var p2Calls = 0

        // p1: 声明标签，但返回 500
        val p1 = ConfigurableProvider("codebuddy", listOf("glm-5.3-upstream-p1")) { _, _ ->
            p1Calls++
            FailedChatCall(500, "{\"error\": \"p1 temporary down\"}")
        }
        // p2: 也能“接”这个模型，但绝不应被调用
        val p2 = ConfigurableProvider("trae", listOf("glm-5.3-upstream-p2")) { _, _ ->
            p2Calls++
            AggregatedChatCall(200, """{"id":"c2","object":"chat.completion","model":"glm-5.3-upstream-p2","choices":[{"index":0,"message":{"role":"assistant","content":"来自备用供应商P2的回复"}}]}""")
        }

        engine.registry.register(p1)
        engine.registry.register(p2)
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("trae", "u2", "账号2", "{}"))

        // 即便用户旧配置里还留着跨供应商优先级表，它也不得再起作用
        engine.updateFailoverSettings(
            ModelFailoverSettings(
                enabled = true,
                retry = RetryConfig(maxAttempts = 2, initialBackoffMs = 10L),
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            val (status, _) = post(server.listeningPort, "腾讯国内/glm-5.3-flash")
            // p1 自己重试耗尽后直接失败，不会跑去 p2
            assertTrue(status >= 400, "p1 失败就应当失败，不应跨供应商，实际 $status")
            assertTrue(p1Calls >= 1, "p1 应当被真实调用")
            assertEquals(0, p2Calls, "绝不能把请求改发给 p2")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `401 凭证失效不浪费重试次数且不改发别家`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        var p1Calls = 0
        var p2Calls = 0

        // p1: 401 鉴权失效
        val p1 = ConfigurableProvider("codebuddy", listOf("m")) { _, _ ->
            p1Calls++
            FailedChatCall(401, "{\"error\": \"invalid_token\"}")
        }
        // p2: 正常返回 200（但标签写的是 p1，所以不应被调用）
        val p2 = ConfigurableProvider("trae", listOf("m")) { _, _ ->
            p2Calls++
            AggregatedChatCall(200, """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"p2 ok"}}]}""")
        }

        engine.registry.register(p1)
        engine.registry.register(p2)
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("trae", "u2", "账号2", "{}"))

        engine.updateFailoverSettings(
            ModelFailoverSettings(
                enabled = true,
                retry = RetryConfig(maxAttempts = 3, initialBackoffMs = 10L),
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            val (status, _) = post(server.listeningPort, "腾讯国内/m")
            // 401 是凭证硬失效：本供应商内不重试，且不跨供应商
            assertTrue(status >= 400, "401 应当直接失败，实际 $status")
            assertEquals(1, p1Calls, "401 不可恢复错误不应在当前供应商内重复重试")
            assertEquals(0, p2Calls, "绝不能改发 p2")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `客户端 400 参数或审核错误立即向客户端报错绝不换供应商`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        var p1Calls = 0
        var p2Calls = 0

        // p1: 报 400 参数错误 (CLIENT)
        val p1 = ConfigurableProvider("codebuddy", listOf("m")) { _, _ ->
            p1Calls++
            FailedChatCall(400, "{\"error\":{\"code\":\"invalid_request_error\",\"message\":\"max_tokens too large\"}}")
        }
        val p2 = ConfigurableProvider("trae", listOf("m")) { _, _ ->
            p2Calls++
            AggregatedChatCall(200, "{}")
        }

        engine.registry.register(p1)
        engine.registry.register(p2)
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("trae", "u2", "账号2", "{}"))

        engine.updateFailoverSettings(
            ModelFailoverSettings(
                enabled = true,
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            val (status, resp) = post(server.listeningPort, "腾讯国内/my-model")
            assertEquals(400, status, "客户端参数错必须向客户端返回 400")
            // 错误码按真实分类给（不再是笼统的 upstream_rejected），消息用供应商给的原话
            assertTrue(resp.contains("\"code\":\"invalid_request\""), resp)
            assertTrue(resp.contains("invalid_request_error"), resp)
            assertEquals(1, p1Calls)
            assertEquals(0, p2Calls, "客户端错误绝不能污染备选供应商")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `客户端错误不计入供应商熔断，健康供应商不被误隔离`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        var p1Calls = 0
        var p2Calls = 0

        // p1 对客户端参数错误稳定回 400（CLIENT）：这不是 p1 的故障
        val p1 = ConfigurableProvider("codebuddy", listOf("m")) { _, _ ->
            p1Calls++
            FailedChatCall(400, "{\"error\":{\"code\":\"invalid_request_error\",\"message\":\"bad model\"}}")
        }
        val p2 = ConfigurableProvider("trae", listOf("m")) { _, _ ->
            p2Calls++
            AggregatedChatCall(200, "{}")
        }

        engine.registry.register(p1)
        engine.registry.register(p2)
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("trae", "u2", "账号2", "{}"))

        engine.updateFailoverSettings(
            ModelFailoverSettings(
                enabled = true,
                // 阈值设 3：若客户端错误被误计入，第 3 次就会熔断 p1
                circuitBreaker = CircuitBreakerConfig(failureThreshold = 3, openDurationMs = 60_000L),
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            repeat(4) {
                val (status, _) = post(server.listeningPort, "腾讯国内/my-model")
                assertEquals(400, status)
            }
            assertEquals(4, p1Calls, "客户端错误不应导致 p1 被熔断跳过")
            assertEquals(0, p2Calls, "客户端错误绝不能污染备选供应商")
            assertEquals(
                BreakerState.CLOSED,
                engine.circuitBreakerOf("codebuddy").currentState(),
                "连续 4 次客户端 400 不应把健康供应商打成熔断",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `上游侧错误仍计入熔断`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val p1 = ConfigurableProvider("codebuddy", listOf("m")) { _, _ -> FailedChatCall(500, "p1 down") }
        val p2 = ConfigurableProvider("trae", listOf("m")) { _, _ -> AggregatedChatCall(200, "{}") }

        engine.registry.register(p1)
        engine.registry.register(p2)
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("trae", "u2", "账号2", "{}"))

        engine.updateFailoverSettings(
            ModelFailoverSettings(
                enabled = true,
                retry = RetryConfig(maxAttempts = 1, initialBackoffMs = 10L),
                circuitBreaker = CircuitBreakerConfig(failureThreshold = 2, openDurationMs = 60_000L),
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            post(server.listeningPort, "腾讯国内/my-model")
            post(server.listeningPort, "腾讯国内/my-model")
            assertEquals(
                BreakerState.OPEN,
                engine.circuitBreakerOf("codebuddy").currentState(),
                "上游 5xx 必须计入熔断",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `上游失败时调用记录带真实 HTTP 状态而非硬编码 200`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val p1 = ConfigurableProvider("codebuddy", listOf("m")) { _, _ -> FailedChatCall(502, "p1 bad gateway") }

        engine.registry.register(p1)
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "账号1", "{}"))

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            val (status, _) = post(server.listeningPort, "腾讯国内/m")
            assertEquals(502, status)
            val record = engine.callLogStore.list().firstOrNull()
            assertTrue(record != null, "失败也应留下一条调用记录")
            assertEquals(502, record.httpStatus, "应记录上游的真实状态")
            assertEquals(CallStatus.FAILED, record.status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `熔断开启的供应商直接被跳过`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        var p1Calls = 0
        var p2Calls = 0

        val p1 = ConfigurableProvider("codebuddy", listOf("m")) { _, _ ->
            p1Calls++
            AggregatedChatCall(200, "{}")
        }
        val p2 = ConfigurableProvider("trae", listOf("m")) { _, _ ->
            p2Calls++
            AggregatedChatCall(200, """{"id":"c","object":"chat.completion","model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"p2 ok"}}]}""")
        }

        engine.registry.register(p1)
        engine.registry.register(p2)
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount("trae", "u2", "账号2", "{}"))

        // 手动将 p1 打入熔断状态
        val cb1 = engine.circuitBreakerOf("codebuddy")
        repeat(5) { cb1.recordFailure() }
        assertEquals(BreakerState.OPEN, cb1.currentState())

        engine.updateFailoverSettings(
            ModelFailoverSettings(
                enabled = true,
                circuitBreaker = CircuitBreakerConfig(failureThreshold = 3, openDurationMs = 60_000L),
            ),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            val (status, _) = post(server.listeningPort, "腾讯国内/m")
            // 熔断只影响「这家暂时不接活」，不会改发别家 → 直接失败
            assertTrue(status >= 500, "熔断中应直接失败，实际 $status")
            assertEquals(0, p1Calls, "处于熔断状态的 p1 应直接被跳过，不发起任何物理网络调用")
            assertEquals(0, p2Calls, "熔断不应导致改发 p2")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `上游 5xx 时按真实主因返回 upstream_error`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val p1 = ConfigurableProvider("codebuddy", listOf("m")) { _, _ -> FailedChatCall(500, "p1 down") }

        engine.registry.register(p1)
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "账号1", "{}"))

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            val (status, resp) = post(server.listeningPort, "腾讯国内/m")
            // 上游 5xx → 主因 SERVER：502 upstream_error（而不是笼统的 503 无可用账号）
            assertEquals(502, status)
            assertTrue(resp.contains("upstream_error"), resp)
            assertTrue(resp.contains("上游服务异常"), resp)
            assertTrue(resp.contains("codebuddy"))
        } finally {
            server.stop()
        }
    }
}
