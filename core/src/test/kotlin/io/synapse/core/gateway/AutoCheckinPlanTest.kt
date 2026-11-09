package io.synapse.core.gateway

import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 自动签到的时间计算。
 *
 * 这组测试锁的是**跨天与时区**的边界 —— 算错一次的后果是「今天没签到」或者
 * 「同一天签两次」，靠真机试很难穷尽，所以必须在这里钉死。
 */
class AutoCheckinPlanTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun at(text: String): Long =
        ZonedDateTime.parse(text).toInstant().toEpochMilli()

    @Test
    fun `本地日期按设备时区算，不是 UTC 天数`() {
        // 东八区 00:05 —— 此刻 UTC 还是「昨天」。用 UTC 天数会把今天算成已跑过，
        // 于是当天再也不会跑，正好把用户要的「凌晨签到」吃掉。
        val t = at("2026-11-10T00:05:00+08:00")
        assertEquals("2026-11-10", AutoCheckinPlan.localDate(t, shanghai))
        // 而 UTC 那边确实是 11-09
        assertEquals("2026-11-09", AutoCheckinPlan.localDate(t, ZoneId.of("UTC")))
    }

    @Test
    fun `抖动由日期派生，同一天恒定且落在窗口内`() {
        val a = AutoCheckinPlan.jitterFor("2026-11-10", 30)
        val b = AutoCheckinPlan.jitterFor("2026-11-10", 30)
        assertEquals(a, b, "同一天必须算出同一个抖动值，否则重启一次目标时刻就漂了")
        assertTrue(a in 0 until 30, "抖动必须落在 [0,30)：$a")
        // 不同日期不强制不同，但整体必须在窗口内
        val c = AutoCheckinPlan.jitterFor("2026-11-11", 30)
        assertTrue(c in 0 until 30)
    }

    @Test
    fun `抖动为 0 表示不抖动，目标就是整点`() {
        val target = AutoCheckinPlan.targetMillis("2026-11-10", 0, 0, shanghai)
        assertEquals(at("2026-11-10T00:00:00+08:00"), target)
    }

    @Test
    fun `目标时刻等于 起点 + 分钟数 + 抖动`() {
        val target = AutoCheckinPlan.targetMillis("2026-11-10", 9 * 60 + 30, 0, shanghai)
        assertEquals(at("2026-11-10T09:30:00+08:00"), target)
    }

    @Test
    fun `今天还没到点就排到今天，已经过了就排到明天`() {
        val before = at("2026-11-10T00:00:00+08:00")
        val after = at("2026-11-10T23:00:00+08:00")

        // 抖动窗口取 30，00:00 的目标落在 [00:00, 00:30)
        val delayBefore = AutoCheckinPlan.millisUntilNextTarget(before, 0, 30, shanghai)
        val window = Duration.ofMinutes(30).toMillis()
        assertTrue(delayBefore in 1..window, "未到点应排到今天的窗口内：${delayBefore}ms")

        val delayAfter = AutoCheckinPlan.millisUntilNextTarget(after, 0, 30, shanghai)
        assertTrue(
            delayAfter in Duration.ofMinutes(60).toMillis()..Duration.ofHours(2).toMillis(),
            "已过点应排到明天 00:00–00:30：${delayAfter}ms",
        )
    }

    @Test
    fun `排程延迟恒为正数`() {
        // 恰好站在目标时刻上：返回 0 会让定时器立刻空转，必须兜到至少 1ms
        val cfg = AutoCheckinSettings(scheduledMinuteOfDay = 0, jitterMinutes = 0)
        val exactly = AutoCheckinPlan.targetMillis("2026-11-10", cfg.scheduledMinuteOfDay, 0, shanghai)
        assertTrue(AutoCheckinPlan.millisUntilNextTarget(exactly, cfg.scheduledMinuteOfDay, 0, shanghai) > 0)
    }

    @Test
    fun `默认设置是 00点00分 抖动30分钟 两个开关都关`() {
        val cfg = AutoCheckinSettings()
        assertEquals("00:00", cfg.timeLabel())
        assertEquals(0, cfg.scheduledMinuteOfDay)
        assertEquals(30, cfg.jitterMinutes)
        assertFalse(cfg.onLaunchEnabled, "自动签到默认必须关：不打招呼就替用户签到是本项目的禁忌")
        assertFalse(cfg.scheduledEnabled)
        assertFalse(cfg.autoCompleteTasksOnLogin, "自动做任务风控更高，默认必须关")
    }

    @Test
    fun `坏设置回落默认，不会算出荒谬时刻`() {
        val cfg = AutoCheckinSettings.fromJson(
            """{"scheduledMinuteOfDay":99999,"jitterMinutes":-5,"onLaunchEnabled":true}""",
        )
        assertEquals(24 * 60 - 1, cfg.scheduledMinuteOfDay, "越界分钟数必须被夹到合法区间")
        assertEquals(0, cfg.jitterMinutes, "负数抖动夹到 0")
        assertTrue(cfg.onLaunchEnabled, "合法字段照常读出来")
    }

    @Test
    fun `JSON 往返不丢字段`() {
        val cfg = AutoCheckinSettings(
            onLaunchEnabled = true,
            scheduledEnabled = true,
            scheduledMinuteOfDay = 7 * 60 + 5,
            jitterMinutes = 15,
            autoCompleteTasksOnLogin = true,
        )
        val round = AutoCheckinSettings.fromJson(cfg.toJson().toString())
        assertEquals(cfg, round)
        assertEquals("07:05", round.timeLabel())
    }
}
