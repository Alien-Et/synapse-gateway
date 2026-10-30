package io.synapse.core.failover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 模型级冷却表（**按账号**）的语义。
 *
 * 关键点：冷却记在「供应商 + 账号 + 模型」上，所以
 * ① 同一个号同一模型才互相影响；
 * ② 换一个号（或换一家、换一个模型）完全不受影响——这正是「熔断/冷却时
 *    立即在同供应商下换号继续」能成立的前提。
 */
class ModelCooldownTrackerTest {

    private var now = 1_000_000L

    private val tracker = ModelCooldownTracker { now }

    @Test
    fun `只影响同一账号同一模型`() {
        assertFalse(tracker.isCoolingFor("codebuddy", "u1", "glm-5.3"))
        tracker.markCooling("codebuddy", "u1", "glm-5.3", 60_000L)

        assertTrue(tracker.isCoolingFor("codebuddy", "u1", "glm-5.3"))
        // 同家换号：必须还能用（这就是「换号继续」）
        assertFalse(tracker.isCoolingFor("codebuddy", "u2", "glm-5.3"))
        // 换模型、换供应商：不受影响
        assertFalse(tracker.isCoolingFor("codebuddy", "u1", "kimi-k2"))
        assertFalse(tracker.isCoolingFor("trae", "u1", "glm-5.3"))
    }

    @Test
    fun `冷却到期即恢复`() {
        tracker.markCooling("codebuddy", "u1", "m", 60_000L)
        now += 59_000L
        assertTrue(tracker.isCoolingFor("codebuddy", "u1", "m"))
        now += 2_000L
        assertFalse(tracker.isCoolingFor("codebuddy", "u1", "m"), "到点即恢复")
    }

    @Test
    fun `非正时长的冷却不记录`() {
        tracker.markCooling("codebuddy", "u1", "m", 0L)
        assertFalse(tracker.isCoolingFor("codebuddy", "u1", "m"))
    }

    @Test
    fun `所有账号都在冷却时才算整家不可用`() {
        tracker.markCooling("codebuddy", "u1", "m", 600_000L)
        assertFalse(
            tracker.allCooling("codebuddy", "m", listOf("u1", "u2")),
            "还有一个号没冷却，就不该判整家不可用",
        )
        tracker.markCooling("codebuddy", "u2", "m", 600_000L)
        assertTrue(tracker.allCooling("codebuddy", "m", listOf("u1", "u2")))
        // 没有任何账号时不做判断，避免误报
        assertFalse(tracker.allCooling("codebuddy", "m", emptyList()))
    }

    @Test
    fun `成功一次解除该号冷却`() {
        tracker.markCooling("codebuddy", "u1", "m", 600_000L)
        tracker.clear("codebuddy", "u1", "m")
        assertFalse(tracker.isCoolingFor("codebuddy", "u1", "m"))
    }

    @Test
    fun `clearAll 解除该模型下所有账号的冷却`() {
        tracker.markCooling("codebuddy", "u1", "m", 600_000L)
        tracker.markCooling("codebuddy", "u2", "m", 600_000L)
        tracker.markCooling("codebuddy", "u1", "other", 600_000L)
        tracker.clearAll("codebuddy", "m")
        assertTrue(tracker.coolingAccounts("codebuddy", "m").isEmpty())
        assertTrue(tracker.isCoolingFor("codebuddy", "u1", "other"), "别的模型不受影响")
    }

    @Test
    fun `snapshot 只返回未到期条目`() {
        tracker.markCooling("a", "u1", "m1", 60_000L)
        tracker.markCooling("b", "u2", "m2", 10_000L)
        now += 20_000L
        val snap = tracker.snapshot()
        assertEquals(setOf("a|u1|m1"), snap.keys)
        assertEquals(40_000L, snap["a|u1|m1"])
    }

    @Test
    fun `coolingAccounts 列出该模型下仍在冷却的账号`() {
        tracker.markCooling("codebuddy", "u1", "m", 60_000L)
        tracker.markCooling("codebuddy", "u2", "m", 60_000L)
        assertEquals(setOf("u1", "u2"), tracker.coolingAccounts("codebuddy", "m"))
        now += 61_000L
        assertTrue(tracker.coolingAccounts("codebuddy", "m").isEmpty())
    }
}
