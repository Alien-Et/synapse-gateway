package io.synapse.core.gateway

import io.synapse.core.InMemoryKeyValueStore
import io.synapse.core.failover.BreakerState
import io.synapse.core.failover.CircuitBreakerConfig
import io.synapse.core.failover.ModelFailoverSettings
import io.synapse.core.provider.AuthKind
import io.synapse.core.provider.ChatCall
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.FailedChatCall
import io.synapse.core.provider.Provider
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderModelCatalogView
import io.synapse.core.provider.UpstreamError
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 账号级失败不能把整个供应商冻结。
 *
 * 现场问题：一个账号被上游限流/报错，整家供应商随后不可用。
 * 根因是 `cb.recordFailure()` 是**供应商级**计数，而它在换号循环内**每失败一次就 +1**：
 * 3 个号各错 2 次 = 6 次，越过阈值（默认 5）直接把整家打进熔断，
 * 明明还有健康的号没用上。
 *
 * 现在只在「整整一轮所有号都试完仍失败」时才计一次。
 */
class ProviderBreakerNotFrozenTest {

    /** 指定哪些账号会失败；其余账号正常返回。 */
    private inner class FlakyProvider(private val failingUids: Set<String>) : Provider {
        var calls = 0
        override val id = "codebuddy"
        override val displayName = "腾讯"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) =
            ProviderModelCatalogView(listOf(io.synapse.core.provider.ProviderModel("m", "m")), false, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, "upstream down")
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
            calls++
            return if (account.uid in failingUids) {
                FailedChatCall(500, "upstream down")
            } else {
                io.synapse.core.provider.AggregatedChatCall(
                    200,
                    """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""",
                )
            }
        }
    }

    private fun post(port: Int): Int {
        val conn = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.write(
            """{"model":"腾讯国内/m","messages":[{"role":"user","content":"hi"}]}""".toByteArray(Charsets.UTF_8),
        )
        return conn.responseCode
    }

    @Test
    fun `一个号连续失败不会冻结整家`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // u1 永远失败，u2 / u3 正常
        val provider = FlakyProvider(setOf("u1"))
        engine.registry.register(provider)
        listOf("u1", "u2", "u3").forEach { engine.pool.upsert(ProviderAccount("codebuddy", it, it, "{}")) }
        engine.updateFailoverSettings(
            ModelFailoverSettings(circuitBreaker = CircuitBreakerConfig(failureThreshold = 2)),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            // 打 6 次：每次 u1 失败换号成功。若按旧逻辑每个号失败都 +1，
            // 阈值 2 早就熔断了，后面的请求会直接 503。
            repeat(6) {
                val code = post(server.listeningPort)
                assertEquals(200, code, "同家还有健康账号时必须成功，实际 $code")
            }
            assertEquals(
                BreakerState.CLOSED,
                engine.circuitBreakerOf("codebuddy").currentState(),
                "单账号反复失败不得把整家打进熔断",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `整轮全部失败才计入供应商熔断`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val provider = FlakyProvider(setOf("u1", "u2"))
        engine.registry.register(provider)
        listOf("u1", "u2").forEach { engine.pool.upsert(ProviderAccount("codebuddy", it, it, "{}")) }
        engine.updateFailoverSettings(
            ModelFailoverSettings(circuitBreaker = CircuitBreakerConfig(failureThreshold = 1)),
        )

        val server = GatewayHttpServer(engine, "127.0.0.1", 0).apply { start(0, true) }
        try {
            post(server.listeningPort)
            // 阈值 1：一整轮两个号都失败后才计一次 → 恰好达到 1，熔断打开
            assertEquals(
                BreakerState.OPEN,
                engine.circuitBreakerOf("codebuddy").currentState(),
                "所有账号都失败时应计入供应商熔断",
            )
            // 而且只计了一次：一轮两个号失败 = 1 次，不是 2 次
            assertTrue(provider.calls >= 2, "两个号都应被尝试，实际 ${provider.calls}")
        } finally {
            server.stop()
        }
    }
}
