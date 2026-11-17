package io.synapse.core.failover

import io.synapse.core.provider.ProviderModel
import io.synapse.core.provider.RoutedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「按模型名自动跨供应商路由」的回归。
 *
 * 重点不是「能换成功」，而是三条纪律：
 * 1. 默认关闭时**绝不参与**（悄悄换家比失效更严重）；
 * 2. 「同名」只做归一化，**不做模糊匹配**（猜模型会把请求发到另一个模型上）；
 * 3. 客户端错误（400）不换家 —— 换了还是一样错，还会污染别家熔断计数。
 */
class SameModelAutoRouterTest {

    private fun routed(providerId: String, modelId: String) = RoutedModel(
        providerId = providerId,
        providerName = providerId,
        model = ProviderModel(modelId, modelId),
    )

    /** 三家都有 `deepseek-4.1`（写法各不相同），另有两家只有 `v3` 系。 */
    private val catalog = listOf(
        routed("mcloud", "deepseek-4.1"),
        routed("raccoon", "DeepSeek_4.1"),
        routed("trae", "deepseek4.1"),
        routed("codebuddy", "deepseek-v3.2"),
        routed("loomy", "deepseek-v3"),
        routed("mcloud", "qwen-max"),
    )

    private fun router(models: List<RoutedModel> = catalog) = SameModelAutoRouter { models }

    /** 自动匹配模式下「能生效」的配置：总开关 + 自动匹配都开。 */
    private fun cfg(
        enabled: Boolean = true,
        auto: Boolean = true,
        strategy: CrossProviderStrategy = CrossProviderStrategy.IN_ORDER,
    ) = CrossProviderFailoverConfig(
        enabled = enabled,
        autoMatchSameModel = auto,
        strategy = strategy,
    )

    // ---------------------------------------------------------------- 归一化

    @Test
    fun `归一化把分隔符和大小写视作同一个模型`() {
        assertEquals("deepseek41", SameModelAutoRouter.normalize("DeepSeek-4.1"))
        assertEquals("deepseek41", SameModelAutoRouter.normalize("deepseek_4.1"))
        assertEquals("deepseek41", SameModelAutoRouter.normalize(" deepseek 4.1 "))
    }

    // ---------------------------------------------------------------- 匹配

    @Test
    fun `跨供应商找出全部同名模型并排除当前供应商`() {
        assertEquals(
            listOf("raccoon|DeepSeek_4.1", "trae|deepseek4.1"),
            router().candidatesFor("mcloud", "deepseek-4.1").map { it.key },
        )
    }

    @Test
    fun `不做模糊匹配`() {
        // v3.2 不会反向命中 v3（别家只有 v3）
        assertTrue(router().candidatesFor("codebuddy", "deepseek-v3.2").isEmpty())
        // v3 也不会命中 v3.2（别家只有 v3.2）
        assertTrue(router().candidatesFor("loomy", "deepseek-v3").isEmpty())
        // 但真正的同名要命中：从 trae 视角看，loomy 的 v3 就是同名
        assertEquals(
            listOf("loomy|deepseek-v3"),
            router().candidatesFor("trae", "deepseek-v3").map { it.key },
        )
    }

    @Test
    fun `已试过的落点不重复给`() {
        assertEquals(
            listOf("trae|deepseek4.1"),
            router().candidatesFor(
                currentProviderId = "mcloud",
                model = "deepseek-4.1",
                attempted = setOf("mcloud|deepseek-4.1", "raccoon|DeepSeek_4.1"),
            ).map { it.key },
        )
    }

    @Test
    fun `目录或模型名为空时不参与`() {
        assertTrue(router(emptyList()).candidatesFor("mcloud", "deepseek-4.1").isEmpty())
        assertTrue(router().candidatesFor("mcloud", "").isEmpty())
        assertTrue(router().candidatesFor("mcloud", "---").isEmpty())
    }

    // ---------------------------------------------------------------- 决策

    @Test
    fun `客户端错误不触发换家`() {
        assertNull(
            router().decide(
                config = cfg(),
                trigger = CrossProviderSwitchDecider.Trigger.ClientError(400),
                currentProviderId = "mcloud",
                model = "deepseek-4.1",
                attempted = setOf("mcloud|deepseek-4.1"),
            ),
            "400 换家还是一样错，只会污染别家熔断计数",
        )
    }

    /**
     * 回归：第一版 `decide` 只看触发原因、不看总开关，于是「开了自动匹配 → 关掉总开关」
     * 之后仍会跨供应商。用户能真实复现（界面上的开关是灰的，但配置里还是 true）。
     */
    @Test
    fun `总开关关闭时绝不生效`() {
        val off = cfg(enabled = false, auto = true)
        assertFalse(off.active, "enabled=false 时 active 必须为 false")
        assertNull(
            router().decide(
                config = off,
                trigger = CrossProviderSwitchDecider.Trigger.UpstreamExhausted("mcloud"),
                currentProviderId = "mcloud",
                model = "deepseek-4.1",
                attempted = setOf("mcloud|deepseek-4.1"),
            ),
            "总开关关了就不能再换家",
        )
    }

    @Test
    fun `自动匹配未开启时本判定器不参与`() {
        assertNull(
            router().decide(
                config = cfg(auto = false),
                trigger = CrossProviderSwitchDecider.Trigger.UpstreamExhausted("mcloud"),
                currentProviderId = "mcloud",
                model = "deepseek-4.1",
                attempted = setOf("mcloud|deepseek-4.1"),
            ),
            "没开自动匹配就走手写候选那条路，这里必须让位",
        )
    }

    @Test
    fun `限速或上游失败时给出同名落点`() {
        val v = router().decide(
            config = cfg(),
            trigger = CrossProviderSwitchDecider.Trigger.ModelFullyCooling("mcloud", "deepseek-4.1", 120),
            currentProviderId = "mcloud",
            model = "deepseek-4.1",
            attempted = setOf("mcloud|deepseek-4.1"),
        )
        assertEquals("raccoon|DeepSeek_4.1", v?.candidate?.key)
        assertTrue(v?.reason.orEmpty().contains("同名模型自动路由"))
    }

    @Test
    fun `按额度策略能改写落点`() {
        val v = router().decide(
            config = cfg(strategy = CrossProviderStrategy.BY_CREDITS),
            trigger = CrossProviderSwitchDecider.Trigger.UpstreamExhausted("mcloud"),
            currentProviderId = "mcloud",
            model = "deepseek-4.1",
            attempted = setOf("mcloud|deepseek-4.1"),
            creditsOf = { c -> if (c.providerId == "trae") 900_000L else 10L },
        )
        assertEquals("trae|deepseek4.1", v?.candidate?.key, "额度多的那家应被优先选中")
    }

    // ---------------------------------------------------------------- 开关

    @Test
    fun `默认关闭时配置不激活`() {
        val cfg = CrossProviderFailoverConfig()
        assertFalse(cfg.enabled)
        assertFalse(cfg.autoMatchSameModel)
        assertFalse(cfg.active, "关闭状态下绝不能参与决策")
    }

    @Test
    fun `开启自动匹配后无需手写候选也能激活`() {
        val cfg = CrossProviderFailoverConfig(enabled = true, autoMatchSameModel = true)
        assertTrue(cfg.active)
        assertTrue(cfg.candidates.isEmpty(), "自动模式不需要手写候选")
    }

    @Test
    fun `开关能往返 JSON`() {
        val back = CrossProviderFailoverConfig.fromJson(
            CrossProviderFailoverConfig(enabled = true, autoMatchSameModel = true).toJson().toString(),
        )
        assertTrue(back.enabled)
        assertTrue(back.autoMatchSameModel)
    }
}
