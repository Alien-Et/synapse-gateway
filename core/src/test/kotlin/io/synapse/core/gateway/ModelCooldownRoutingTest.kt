package io.synapse.core.gateway

import io.synapse.core.InMemoryKeyValueStore
import io.synapse.core.failover.BreakerState
import io.synapse.core.provider.AggregatedChatCall
import io.synapse.core.provider.AuthKind
import io.synapse.core.provider.ChatCall
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.Provider
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderModel
import io.synapse.core.provider.ProviderModelCatalogView
import io.synapse.core.provider.StreamFailureChatCall
import io.synapse.core.provider.UpstreamError
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 模型级限额（熔断/冷却类错误）的路由行为。
 *
 * 主人要求：**被使用的模型出现熔断、冷却时，立即在同供应商下换号继续**；
 * 同供应商没有其它账号、或其它账号也没额度/被禁用时，才提醒用户换供应商或等冷却。
 *
 * 所以这里的断言是：
 * ① 某个号撞了「模型已超限」→ 立刻换同家别的号，请求最后**成功**；
 * ② 冷却只记在撞墙的那个号上，其它号照常可用；
 * ③ 同家所有号都撞墙后才失败，并且不计入供应商熔断；
 * ④ 熔断打开时，只要同家还有号，仍然换号继续跑。
 */
class ModelCooldownRoutingTest {

    /**
     * 可以指定「哪些账号会撞模型限额」的桩供应商。
     *
     * @param limitedUids 对这些账号回「该模型已超限」，其余账号正常返回内容。
     */
    private inner class ModelLimitProvider(private val limitedUids: Set<String>) : Provider {
        var calls = 0
        val calledUids = mutableListOf<String>()
        override val id = "codebuddy"
        override val displayName = "模型限额桩"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) =
            ProviderModelCatalogView(listOf(ProviderModel("hot", "hot")), false, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.MODEL_LIMIT, "该模型已超限")
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
            calls++
            calledUids += account.uid
            if (account.uid in limitedUids) {
                return StreamFailureChatCall(200, UpstreamError(ErrorKind.MODEL_LIMIT, "该模型已超限，请稍后再试"))
            }
            return AggregatedChatCall(200, """{"id":"x","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""")
        }
    }

    private fun post(port: Int, model: String = "腾讯国内/hot"): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.write(
            """{"model":"$model","messages":[{"role":"user","content":"hi"}]}""".toByteArray(Charsets.UTF_8),
        )
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        return code to text
    }

    @Test
    fun `模型级限额只冷却撞墙的账号，同家换号继续`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // u1 撞限额，u2 正常
        val provider = ModelLimitProvider(limitedUids = setOf("u1"))
        engine.registry.register(provider)
        engine.pool.upsert(ProviderAccount(provider.id, "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount(provider.id, "u2", "账号2", "{}"))

        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            val (code, _) = post(server.listeningPort)
            assertEquals(200, code, "撞限额的号被换掉后，同家另一号应成功返回")
            // 两个号都必须被用到：先撞限额的号，再换成另一号
            assertTrue(provider.calledUids.contains("u1"), "应先试到撞限额的号，实际：${provider.calledUids}")
            assertTrue(provider.calledUids.contains("u2"), "应换同家另一号继续，实际：${provider.calledUids}")

            // 冷却只落在 u1 上，u2 仍然可用
            assertTrue(
                engine.modelCooldownOf().isCoolingFor(provider.id, "u1", "hot"),
                "撞墙的号应被单独冷却",
            )
            assertTrue(
                !engine.modelCooldownOf().isCoolingFor(provider.id, "u2", "hot"),
                "同家其它账号不得被连带冷却",
            )
            // 模型级错误不计入供应商熔断
            assertEquals(
                BreakerState.CLOSED,
                engine.circuitBreakerOf(provider.id).currentState(),
                "模型级限额不代表供应商故障，不得计入熔断",
            )
            // 冷却中的号不会再被选中
            assertNotNull(engine.pickAccount(provider.id, emptySet(), null, model = "hot"))
            assertEquals(
                "u2",
                engine.pickAccount(provider.id, setOf("u1"), null, model = "hot")?.uid,
                "u1 冷却中，选号应落到 u2",
            )
        } finally {
            server.stop()
        }
    }

    @Test
    fun `同家所有账号都撞墙后才失败且不计熔断`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        val provider = ModelLimitProvider(limitedUids = setOf("u1", "u2"))
        engine.registry.register(provider)
        engine.pool.upsert(ProviderAccount(provider.id, "u1", "账号1", "{}"))
        engine.pool.upsert(ProviderAccount(provider.id, "u2", "账号2", "{}"))

        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            val (code1, _) = post(server.listeningPort)
            assertTrue(code1 >= 400, "该模型不可用时应明确失败，实际 $code1")
            // 两个账号各试一次，都撞同一道墙才算真失败
            assertEquals(2, provider.calls, "模型级错误应先换同家其它账号各试一次")

            assertEquals(
                BreakerState.CLOSED,
                engine.circuitBreakerOf(provider.id).currentState(),
                "模型级限额不代表供应商故障，不得计入熔断",
            )
            // 两个号都被记了该模型冷却 → 整家该模型判定为不可用
            assertTrue(engine.isModelFullyCooling(provider.id, "hot"), "所有可用账号都撞墙后应整家判冷却")
            assertTrue(engine.cooldownRemainingMs(provider.id, "hot") > 0, "应能给出剩余冷却时间")

            // 冷却期内直接跳过，不再打上游
            val callsBeforeCooldown = provider.calls
            repeat(3) { post(server.listeningPort) }
            assertEquals(callsBeforeCooldown, provider.calls, "整家冷却期内必须直接跳过，不再打上游")

            val (_, body) = post(server.listeningPort)
            assertTrue(body.contains("冷却"), "应给出「冷却中」的原因，实际：$body")
        } finally {
            server.stop()
        }
    }
}
