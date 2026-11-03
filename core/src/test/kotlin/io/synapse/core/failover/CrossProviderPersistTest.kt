package io.synapse.core.failover

import io.synapse.core.InMemoryKeyValueStore
import io.synapse.core.gateway.GatewayEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 跨供应商候选的**落盘**与**勾选语义**验证。
 *
 * 用户反馈「勾选完模型没保存住」，这里把两件事分别钉死：
 * 1. 落盘：模拟「保存 → 重启 → 读回」，确认候选能完整读回；
 * 2. 勾选语义：顺序（=优先级）、去重、幂等，这三条直接决定故障转移先发给谁。
 */
class CrossProviderPersistTest {

    private val a = CrossProviderCandidate("mcloud", "blian_qwen38_flash")
    private val b = CrossProviderCandidate("mcloud", "huoshan_glm_5_3")
    private val c = CrossProviderCandidate("codebuddy", "claude-sonnet-4")

    // ---------------------------------------------------------------- 落盘

    @Test
    fun `候选勾选后跨引擎实例仍在`() {
        val store = InMemoryKeyValueStore()

        val engine1 = GatewayEngine(store)
        engine1.updateFailoverSettings(
            engine1.failoverSettings().copy(
                crossProvider = CrossProviderFailoverConfig(
                    enabled = true,
                    candidates = listOf(a, b, c),
                    strategy = CrossProviderStrategy.BY_CREDITS,
                ),
            ),
        )

        // 新引擎从同一份存储读回，等价于「重启 App」
        val engine2 = GatewayEngine(store)
        val restored = engine2.failoverSettings().crossProvider
        assertEquals(listOf(a, b, c), restored.candidates, "候选必须完整读回且保持顺序")
        assertEquals(CrossProviderStrategy.BY_CREDITS, restored.strategy)
        assertTrue(restored.enabled)
        assertTrue(restored.active)
    }

    @Test
    fun `逐次勾选并落盘后顺序保留`() {
        val store = InMemoryKeyValueStore()
        val engine = GatewayEngine(store)

        // 模拟用户依次点三个勾：每次都是一次独立的「读改写 + 落盘」
        listOf(a, b, c).forEach { candidate ->
            val cur = engine.failoverSettings()
            engine.updateFailoverSettings(
                cur.copy(
                    crossProvider = cur.crossProvider.withCandidateToggled(candidate, true),
                ),
            )
        }

        val restored = GatewayEngine(store).failoverSettings().crossProvider
        assertEquals(listOf(a, b, c), restored.candidates, "先勾的应排在前面（顺序=优先级）")
    }

    @Test
    fun `取消勾选后落盘不再包含该项`() {
        val store = InMemoryKeyValueStore()
        val engine = GatewayEngine(store)
        engine.updateFailoverSettings(
            engine.failoverSettings().copy(
                crossProvider = CrossProviderFailoverConfig(candidates = listOf(a, b, c)),
            ),
        )

        val cur = engine.failoverSettings()
        engine.updateFailoverSettings(
            cur.copy(crossProvider = cur.crossProvider.withCandidateToggled(b, false)),
        )

        val restored = GatewayEngine(store).failoverSettings().crossProvider
        assertEquals(listOf(a, c), restored.candidates)
    }

    @Test
    fun `只改候选不动开关时开关保持原值`() {
        val store = InMemoryKeyValueStore()
        val engine1 = GatewayEngine(store)
        // 开关默认关闭，只保存候选：不能顺手把开关打开（会被当成偷偷生效）
        engine1.updateFailoverSettings(
            engine1.failoverSettings().copy(
                crossProvider = CrossProviderFailoverConfig(candidates = listOf(a)),
            ),
        )
        val restored = GatewayEngine(store).failoverSettings().crossProvider
        assertEquals(1, restored.candidates.size)
        assertTrue(!restored.enabled, "开关必须还是关的")
        assertTrue(!restored.active)
    }

    // ---------------------------------------------------------------- 勾选语义

    @Test
    fun `重复勾同一个候选不会产生重复条目`() {
        val cfg = CrossProviderFailoverConfig(candidates = listOf(a))
        val again = cfg.withCandidateToggled(a, true)
        assertEquals(listOf(a), again.candidates, "连点两下不能出现两份")
    }

    @Test
    fun `新勾的追加到末尾而不是插到最前`() {
        val cfg = CrossProviderFailoverConfig(candidates = listOf(a))
        assertEquals(listOf(a, b), cfg.withCandidateToggled(b, true).candidates)
    }

    @Test
    fun `取消不存在的候选是无操作`() {
        val cfg = CrossProviderFailoverConfig(candidates = listOf(a))
        assertEquals(listOf(a), cfg.withCandidateToggled(b, false).candidates)
    }

    @Test
    fun `取消后重新勾选会排到最后`() {
        val cfg = CrossProviderFailoverConfig(candidates = listOf(a, b, c))
        val afterRemove = cfg.withCandidateToggled(a, false)
        val afterReAdd = afterRemove.withCandidateToggled(a, true)
        assertEquals(listOf(b, c, a), afterReAdd.candidates)
    }

    @Test
    fun `勾选只改候选不影响策略与开关`() {
        val cfg = CrossProviderFailoverConfig(
            enabled = true,
            candidates = emptyList(),
            strategy = CrossProviderStrategy.RANDOM,
        )
        val next = cfg.withCandidateToggled(a, true)
        assertTrue(next.enabled)
        assertEquals(CrossProviderStrategy.RANDOM, next.strategy)
        assertEquals(listOf(a), next.candidates)
    }
}
