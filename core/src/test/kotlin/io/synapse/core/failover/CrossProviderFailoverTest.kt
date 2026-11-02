package io.synapse.core.failover

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 跨供应商故障转移的回归。
 *
 * 这套逻辑是**默认关闭**的第三套机制，所以最重要的用例不是「能换成功」，
 * 而是「关着的时候绝对不能干预」——一旦它偷偷生效，用户写的「灵犀/qwen-max」
 * 就可能跑到别家去，这是比失效更严重的问题。
 */
class CrossProviderFailoverTest {

    private val a = CrossProviderCandidate("mcloud", "qwen-max")
    private val b = CrossProviderCandidate("trae", "claude-sonnet")
    private val c = CrossProviderCandidate("codebuddy", "glm-5.2")

    private fun decider(cfg: CrossProviderFailoverConfig, random: Random = Random(1)) =
        CrossProviderSwitchDecider({ cfg }, random)

    // ---------------------------------------------------------------- 默认关闭

    @Test
    fun `默认关闭时任何触发都不换供应商`() {
        val cfg = CrossProviderFailoverConfig() // 默认 enabled=false
        val d = decider(cfg)
        val triggers = listOf(
            CrossProviderSwitchDecider.Trigger.ProviderCircuitOpen("mcloud"),
            CrossProviderSwitchDecider.Trigger.NoUsableAccount("mcloud"),
            CrossProviderSwitchDecider.Trigger.ModelFullyCooling("mcloud", "qwen-max", 30),
            CrossProviderSwitchDecider.Trigger.UpstreamExhausted("mcloud", "502"),
        )
        triggers.forEach { trigger ->
            assertNull(d.decide(trigger, emptySet()), "关闭时不该换家：$trigger")
        }
    }

    @Test
    fun `打开但没有候选也不换`() {
        val cfg = CrossProviderFailoverConfig(enabled = true, candidates = emptyList())
        assertNull(decider(cfg).decide(CrossProviderSwitchDecider.Trigger.ProviderCircuitOpen("mcloud"), emptySet()))
    }

    @Test
    fun `active 只在开启且有候选时为真`() {
        assertTrue(!CrossProviderFailoverConfig().active)
        assertTrue(!CrossProviderFailoverConfig(enabled = true).active, "没有候选不算生效")
        assertTrue(CrossProviderFailoverConfig(enabled = true, candidates = listOf(a)).active)
    }

    // ---------------------------------------------------------------- 四类触发

    @Test
    fun `四类触发都会换家`() {
        val cfg = CrossProviderFailoverConfig(enabled = true, candidates = listOf(a, b))
        val d = decider(cfg)
        val triggers = listOf(
            CrossProviderSwitchDecider.Trigger.ProviderCircuitOpen("x"),
            CrossProviderSwitchDecider.Trigger.NoUsableAccount("x"),
            CrossProviderSwitchDecider.Trigger.ModelFullyCooling("x", "m", 10),
            CrossProviderSwitchDecider.Trigger.UpstreamExhausted("x", "503"),
        )
        triggers.forEach { trigger ->
            val verdict = d.decide(trigger, emptySet())
            assertEquals(a, verdict?.candidate, "应当换到第一个候选：$trigger")
        }
    }

    @Test
    fun `客户端错误不换家`() {
        val cfg = CrossProviderFailoverConfig(enabled = true, candidates = listOf(a))
        val verdict = decider(cfg).decide(
            CrossProviderSwitchDecider.Trigger.ClientError(400, "context_length_exceeded"),
            emptySet(),
        )
        assertNull(verdict, "参数/审核类错误换家也一样错，不该把错误记到别家头上")
    }

    // ---------------------------------------------------------------- 策略

    @Test
    fun `按顺序永远取第一个未试过的候选`() {
        val cfg = CrossProviderFailoverConfig(
            enabled = true, candidates = listOf(a, b, c), strategy = CrossProviderStrategy.IN_ORDER,
        )
        val d = decider(cfg)
        assertEquals(a, d.decide(CrossProviderSwitchDecider.Trigger.ProviderCircuitOpen("x"), emptySet())?.candidate)
        assertEquals(b, d.decide(CrossProviderSwitchDecider.Trigger.ProviderCircuitOpen("x"), setOf(a.key))?.candidate)
        assertEquals(
            c,
            d.decide(CrossProviderSwitchDecider.Trigger.ProviderCircuitOpen("x"), setOf(a.key, b.key))?.candidate,
        )
    }

    @Test
    fun `按顺序在候选全试过之后不再换`() {
        val cfg = CrossProviderFailoverConfig(enabled = true, candidates = listOf(a, b))
        val verdict = decider(cfg).decide(
            CrossProviderSwitchDecider.Trigger.ProviderCircuitOpen("x"),
            setOf(a.key, b.key),
        )
        assertNull(verdict, "全都试过了再换就会 A到B到A 死循环")
    }

    @Test
    fun `按额度优先挑剩余最多的`() {
        val cfg = CrossProviderFailoverConfig(
            enabled = true, candidates = listOf(a, b, c), strategy = CrossProviderStrategy.BY_CREDITS,
        )
        val credits = mapOf(a.key to 100L, b.key to 900L, c.key to 400L)
        val verdict = decider(cfg).decide(
            CrossProviderSwitchDecider.Trigger.ProviderCircuitOpen("x"),
            emptySet(),
        ) { credits[it.key] }
        assertEquals(b, verdict?.candidate, "900 最高")
    }

    @Test
    fun `按额度时未知额度排在已知之后而不是当成 0 排除`() {
        val cfg = CrossProviderFailoverConfig(
            enabled = true, candidates = listOf(a, b), strategy = CrossProviderStrategy.BY_CREDITS,
        )
        // a 额度未知，b 只有 1
        val verdict = decider(cfg).decide(
            CrossProviderSwitchDecider.Trigger.ProviderCircuitOpen("x"),
            emptySet(),
        ) { if (it == b) 1L else null }
        assertEquals(b, verdict?.candidate, "已知的 1 也比未知优先：不知道不等于没有")
    }

    @Test
    fun `随机策略覆盖所有候选`() {
        val cfg = CrossProviderFailoverConfig(
            enabled = true, candidates = listOf(a, b, c), strategy = CrossProviderStrategy.RANDOM,
        )
        val seen = HashSet<String>()
        repeat(200) {
            val v = decider(cfg, Random(it)).decide(
                CrossProviderSwitchDecider.Trigger.UpstreamExhausted("x"),
                emptySet(),
            )
            seen.add(v!!.candidate.key)
        }
        assertEquals(setOf(a.key, b.key, c.key), seen, "200 次应当覆盖全部候选")
    }

    @Test
    fun `随机策略排除已试过的候选`() {
        val cfg = CrossProviderFailoverConfig(
            enabled = true, candidates = listOf(a, b), strategy = CrossProviderStrategy.RANDOM,
        )
        repeat(50) {
            val v = decider(cfg, Random(it)).decide(
                CrossProviderSwitchDecider.Trigger.UpstreamExhausted("x"),
                setOf(a.key),
            )
            assertEquals(b, v?.candidate)
        }
    }

    // ---------------------------------------------------------------- 序列化

    @Test
    fun `配置可以往返序列化`() {
        val cfg = CrossProviderFailoverConfig(
            enabled = true,
            candidates = listOf(a, b),
            strategy = CrossProviderStrategy.BY_CREDITS,
        )
        val restored = CrossProviderFailoverConfig.fromJson(cfg.toJson().toString())
        assertEquals(cfg, restored)
    }

    @Test
    fun `老配置没有该字段时默认关闭`() {
        val legacy = """{"enabled":true,"crossRegionFailover":true}"""
        val cfg = ModelFailoverSettings.fromJson(legacy)
        assertTrue(!cfg.crossProvider.enabled, "升级后不能自动开启跨供应商")
        assertTrue(cfg.crossProvider.candidates.isEmpty())
        // 原有字段不受影响
        assertTrue(cfg.crossRegionFailover)
    }
}
