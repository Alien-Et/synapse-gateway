package io.synapse.core.gateway

import io.synapse.core.InMemoryKeyValueStore
import io.synapse.core.failover.ModelFailoverSettings
import io.synapse.core.provider.AuthKind
import io.synapse.core.provider.ChatCall
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.Provider
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderModelCatalogView
import io.synapse.core.provider.UpstreamError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 「国内外互通」开关。
 *
 * 腾讯、字节各有国内 / 国外两个站点，共用同一个 providerId。默认开启：
 * 本区域账号全都不可用时顺延到另一区域；关闭后区域是硬约束，绝不跨区。
 *
 * 两条都要锁住，因为「跨区」在这里有两面性：
 * - 开着是好事（本区域没号了还能救回来）；
 * - 但**必须优先本区域**，否则国内请求会长期跑在国外号上，违背用户选区域的本意。
 */
class CrossRegionFailoverTest {

    /** 区域型供应商桩：uid 以 `-cn` / `-global` 结尾决定区域。 */
    private inner class RegionProvider : Provider, io.synapse.core.provider.RegionAwareSupport {
        override val id = "codebuddy"
        override val displayName = "腾讯"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) =
            ProviderModelCatalogView(emptyList(), false, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            throw UnsupportedOperationException()
        override fun regionOf(account: ProviderAccount) =
            if (account.uid.endsWith("-global")) "global" else "cn"
        override fun regions() = listOf("cn", "global")
    }

    private fun engineWith(accounts: List<Pair<String, Long>>): GatewayEngine {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(RegionProvider())
        accounts.forEach { (uid, credits) ->
            engine.pool.upsert(ProviderAccount("codebuddy", uid, uid, "{}"))
            engine.pool.updateCredits("codebuddy", uid, credits, known = true)
        }
        return engine
    }

    @Test
    fun `开启互通时本区域没号才跨区`() {
        val engine = engineWith(listOf("a-cn" to 10L, "b-global" to 100L))
        // 把国内号停用（真正的不可用，不是「额度 0」——后者会被降级轮自愈选中）
        engine.setAccountEnabled("codebuddy", "a-cn", false)
        assertEquals(
            "b-global",
            engine.pickAccount("codebuddy", region = "cn", allowCrossRegion = true)?.uid,
            "开启互通后应顺延到国外账号",
        )
    }

    @Test
    fun `关闭互通时本区域没号也不跨区`() {
        val engine = engineWith(listOf("a-cn" to 10L, "b-global" to 100L))
        engine.setAccountEnabled("codebuddy", "a-cn", false)
        engine.updateFailoverSettings(ModelFailoverSettings(crossRegionFailover = false))
        assertNull(
            engine.pickAccount("codebuddy", region = "cn", allowCrossRegion = true),
            "关闭互通后，国内请求不能跑到国外账号上",
        )
    }

    @Test
    fun `开启互通时仍然优先本区域`() {
        val engine = engineWith(listOf("a-cn" to 10L, "b-global" to 999L))
        assertEquals(
            "a-cn",
            engine.pickAccount("codebuddy", region = "cn", allowCrossRegion = true)?.uid,
            "本区域有号就必须先用本区域，哪怕另一边额度更高",
        )
    }

    @Test
    fun `开关默认开启且能持久化`() {
        assertEquals(true, ModelFailoverSettings().crossRegionFailover)
        assertEquals(true, ModelFailoverSettings.fromJson("""{"enabled":true}""").crossRegionFailover)
        val off = ModelFailoverSettings(crossRegionFailover = false)
        assertEquals(false, ModelFailoverSettings.fromJson(off.toJson().toString()).crossRegionFailover)
    }

    @Test
    fun `不传 allowCrossRegion 时行为与以前一致`() {
        val engine = engineWith(listOf("a-cn" to 10L, "b-global" to 100L))
        engine.setAccountEnabled("codebuddy", "a-cn", false)
        assertNull(
            engine.pickAccount("codebuddy", region = "cn"),
            "调用方没要求跨区时，绝不能擅自跨区",
        )
        assertNotNull(engine.pickAccount("codebuddy", region = "global", allowCrossRegion = true))
    }
}
