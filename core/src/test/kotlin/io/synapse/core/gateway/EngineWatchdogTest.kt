package io.synapse.core.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 看门狗只认「真实探活」的结果，不信 `isRunning()` 这种对象状态。
 *
 * 这几条用例锁死的是「僵尸态怎么收场」：能重启就重启，连续失败就诚实放弃，
 * 放弃后绝不复活；用户主动停服务时看门狗也必须立刻闭嘴。
 */
class EngineWatchdogTest {

    private class Harness(
        maxFailedTicks: Int = 3,
        var healthy: Boolean = false,
        var restartWorks: Boolean = true,
        var probeThrows: Boolean = false,
        var restartThrows: Boolean = false,
    ) {
        var probeCount = 0
        var restartCount = 0
        var gaveUpCount = 0
        val watchdog: EngineWatchdog = EngineWatchdog(
            probe = {
                probeCount++
                if (probeThrows) throw IllegalStateException("probe boom")
                healthy
            },
            restart = {
                restartCount++
                if (restartThrows) throw IllegalStateException("restart boom")
                if (restartWorks) healthy = true
                restartWorks
            },
            onGaveUp = { gaveUpCount++ },
            maxFailedTicks = maxFailedTicks,
        )
    }

    @Test
    fun `探活健康时不重启`() {
        val h = Harness(healthy = true)
        assertTrue(h.watchdog.tick())
        assertEquals(0, h.restartCount)
        assertEquals(0, h.watchdog.consecutiveFailures)
        assertFalse(h.watchdog.gaveUp)
    }

    @Test
    fun `探活失败但重启成功时恢复`() {
        val h = Harness(healthy = false)
        assertTrue(h.watchdog.tick(), "重启成功应视为本次已恢复")
        assertEquals(1, h.restartCount)
        assertFalse(h.watchdog.gaveUp)
        // 下一轮探活已经是健康的
        assertTrue(h.watchdog.tick())
        assertEquals(0, h.watchdog.consecutiveFailures)
    }

    @Test
    fun `连续失败达到上限后放弃且只放弃一次`() {
        val h = Harness(maxFailedTicks = 3, healthy = false, restartWorks = false)
        assertFalse(h.watchdog.tick())
        assertFalse(h.watchdog.tick())
        assertFalse(h.watchdog.tick())
        assertTrue(h.watchdog.gaveUp)
        assertEquals(1, h.gaveUpCount)

        // 放弃之后不再探活、不再重启
        val probes = h.probeCount
        val restarts = h.restartCount
        assertFalse(h.watchdog.tick())
        assertEquals(probes, h.probeCount)
        assertEquals(restarts, h.restartCount)
        assertEquals(1, h.gaveUpCount)
    }

    @Test
    fun `探活或重启抛异常按失败计数`() {
        val h = Harness(maxFailedTicks = 2, probeThrows = true, restartWorks = false)
        assertFalse(h.watchdog.tick())
        assertEquals(1, h.watchdog.consecutiveFailures)
        assertFalse(h.watchdog.tick())
        assertTrue(h.watchdog.gaveUp)

        val r = Harness(maxFailedTicks = 1, healthy = false, restartThrows = true)
        assertFalse(r.watchdog.tick())
        assertTrue(r.watchdog.gaveUp, "重启抛异常也应计入失败并触发放弃")
    }

    @Test
    fun `cancel 之后不再探活也不重启`() {
        val h = Harness(healthy = false)
        h.watchdog.cancel()
        assertFalse(h.watchdog.tick())
        assertEquals(0, h.probeCount)
        assertEquals(0, h.restartCount)
        assertEquals(0, h.gaveUpCount)
    }
}
