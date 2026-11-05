package io.synapse.core.failover

import io.synapse.core.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 模型禁用表的回归。
 *
 * 用户定的规则：「冷却解封后，当天连续报错 3 次 → 禁用 24 小时；
 * 禁用期间不再路由到它；日志卡片带禁用标签」。
 * 这里把触发、时长、落盘、跨天清零、成功清零逐条钉住。
 */
class ModelBanTrackerTest {

    private var now = 1_700_000_000_000L
    private val store = InMemoryKeyValueStore()
    private fun tracker() = ModelBanTracker(store, nowMillis = { now })

    private val BAN_MS = 24L * 60 * 60 * 1000

    @Test
    fun `连续失败达阈值才禁用`() {
        val t = tracker()
        assertFalse(t.recordFailure("p", "m", threshold = 3, banDurationMs = BAN_MS))
        assertEquals(1, t.failureCount("p", "m"))
        assertFalse(t.isBanned("p", "m"))

        assertFalse(t.recordFailure("p", "m", threshold = 3, banDurationMs = BAN_MS))
        assertEquals(2, t.failureCount("p", "m"))
        assertFalse(t.isBanned("p", "m"))

        assertTrue(t.recordFailure("p", "m", threshold = 3, banDurationMs = BAN_MS))
        assertTrue(t.isBanned("p", "m"))
        assertEquals(0, t.failureCount("p", "m"), "触发禁用后计数要清零")
    }

    @Test
    fun `禁用按时长自动解封`() {
        val t = tracker()
        repeat(3) { t.recordFailure("p", "m", 3, BAN_MS) }
        assertTrue(t.isBanned("p", "m"))
        assertEquals(BAN_MS, t.remainingBanMs("p", "m"))

        now += BAN_MS - 1
        assertTrue(t.isBanned("p", "m"), "还没到 24 小时不能解封")
        now += 1
        assertFalse(t.isBanned("p", "m"))
        assertEquals(0L, t.remainingBanMs("p", "m"))
    }

    @Test
    fun `成功一次清掉连续失败计数`() {
        val t = tracker()
        t.recordFailure("p", "m", 3, BAN_MS)
        t.recordFailure("p", "m", 3, BAN_MS)
        assertEquals(2, t.failureCount("p", "m"))

        t.recordSuccess("p", "m")
        assertEquals(0, t.failureCount("p", "m"))

        // 清空后重新累计：再失败两次仍不到阈值，不会禁用
        t.recordFailure("p", "m", 3, BAN_MS)
        t.recordFailure("p", "m", 3, BAN_MS)
        assertFalse(t.isBanned("p", "m"))
        assertEquals(2, t.failureCount("p", "m"))
    }

    @Test
    fun `跨天自动清零连续失败`() {
        val t = tracker()
        t.recordFailure("p", "m", 3, BAN_MS)
        t.recordFailure("p", "m", 3, BAN_MS)
        assertEquals(2, t.failureCount("p", "m"))

        // 用真实时区算「明天」，避免测试自己造的时间戳刚好仍在同一天
        val tomorrow = ModelBanTracker.startOfDay(now) + 25L * 60 * 60 * 1000
        now = tomorrow
        assertEquals(0, t.failureCount("p", "m"), "昨天攒的失败不该算到今天")
        assertFalse(t.recordFailure("p", "m", 3, BAN_MS), "今天只算第一次")
    }

    @Test
    fun `禁用落盘重启后依然生效`() {
        val t1 = tracker()
        repeat(3) { t1.recordFailure("mcloud", "seedream", 3, BAN_MS) }
        assertTrue(t1.isBanned("mcloud", "seedream"))

        // 新的 tracker 从同一份 store 读回 = 模拟 App 重启
        val t2 = ModelBanTracker(store, nowMillis = { now })
        assertTrue(t2.isBanned("mcloud", "seedream"))
    }

    @Test
    fun `读回时丢弃已过期的禁用`() {
        val t1 = tracker()
        repeat(3) { t1.recordFailure("p", "m", 3, BAN_MS) }
        now += BAN_MS + 1
        val t2 = ModelBanTracker(store, nowMillis = { now })
        assertFalse(t2.isBanned("p", "m"), "App 关了三天再打开，禁用早该失效了")
    }

    @Test
    fun `手动解除禁用`() {
        val t = tracker()
        repeat(3) { t.recordFailure("p", "m", 3, BAN_MS) }
        assertTrue(t.isBanned("p", "m"))
        t.unban("p", "m")
        assertFalse(t.isBanned("p", "m"))
        // 解除后重启也不该再出现
        assertFalse(ModelBanTracker(store, nowMillis = { now }).isBanned("p", "m"))
    }

    @Test
    fun `不同模型互不影响`() {
        val t = tracker()
        repeat(3) { t.recordFailure("p", "m1", 3, BAN_MS) }
        assertTrue(t.isBanned("p", "m1"))
        assertFalse(t.isBanned("p", "m2"))
        assertFalse(t.isBanned("other", "m1"))
    }

    @Test
    fun `禁用时长与阈值被夹回合法区间`() {
        assertEquals(1, ModelBanTracker.clampThreshold(0))
        assertEquals(20, ModelBanTracker.clampThreshold(999))
        assertEquals(
            ModelBanTracker.MIN_BAN_DURATION_MS,
            ModelBanTracker.clampBanDuration(1L),
        )
        assertEquals(
            ModelBanTracker.MAX_BAN_DURATION_MS,
            ModelBanTracker.clampBanDuration(Long.MAX_VALUE),
        )
    }

    @Test
    fun `非法参数不触发禁用`() {
        val t = tracker()
        assertFalse(t.recordFailure("p", "m", threshold = 0, banDurationMs = BAN_MS))
        assertFalse(t.recordFailure("p", "m", threshold = 3, banDurationMs = 0))
        assertFalse(t.isBanned("p", "m"))
    }

    @Test
    fun `snapshot 只列未过期条目`() {
        val t = tracker()
        repeat(3) { t.recordFailure("p", "m1", 3, BAN_MS) }
        repeat(3) { t.recordFailure("p", "m2", 3, BAN_MS / 2) }
        assertEquals(2, t.snapshot().size)
        now += BAN_MS / 2 + 1
        assertEquals(1, t.snapshot().size, "过期条目应被清掉")
    }
}
