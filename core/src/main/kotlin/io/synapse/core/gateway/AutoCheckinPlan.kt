package io.synapse.core.gateway

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 自动签到的**时间计算**（纯函数，不碰存储、不碰上游）。
 *
 * 单独拆出来是为了能单测：这一块全是「跨天」「时区」「抖动」的边界逻辑，
 * 算错一次就是「今天没签到」或者「一天签两次」，靠真机试很难穷尽。
 *
 * ## 为什么用本地日期而不是 `nowMillis() / 86400000`
 *
 * `GatewayEngine.autoPurgeIfDue()` 用的是 UTC 天数（`nowMillis() / DAY_MILLIS`），
 * 那是给**日志清理**用的 —— 清理晚一天没有任何影响。
 * 而「凌晨 00:00 签到」是**本地时间**的概念：东八区用户在 00:05 签到，
 * UTC 天数那时还没翻篇（UTC 才 16:05），用 UTC 天数会把它算成「昨天已经跑过」，
 * 于是当天不会再跑 —— 恰恰把用户要的功能吃掉。所以这里一律按本地日期字符串做键。
 */
object AutoCheckinPlan {

    /** 本地日期（`yyyy-MM-dd`）。 */
    fun localDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate().toString()

    /**
     * 由日期派生的抖动分钟数：`[0, windowMinutes)`，同一天恒定。
     *
     * 用日期字符串的稳定哈希，所以**不需要把它持久化** —— 重算多少次都一样，
     * 进程重启、设置页来回切都不会让当天的目标时刻漂移。
     */
    fun jitterFor(date: String, windowMinutes: Int): Int {
        if (windowMinutes <= 0) return 0
        var hash = 0
        for (ch in date) hash = hash * 31 + ch.code
        // Kotlin 的 % 对负数返回负数，这里手工取正模
        return ((hash % windowMinutes) + windowMinutes) % windowMinutes
    }

    /** 某天该跑自动签到的**本地时刻**。 */
    fun targetMillis(
        date: String,
        minuteOfDay: Int,
        jitterMinutes: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val dayStart = LocalDate.parse(date).atStartOfDay(zone)
        return dayStart
            .plusMinutes(minuteOfDay.toLong())
            .plusMinutes(jitterFor(date, jitterMinutes).toLong())
            .toInstant()
            .toEpochMilli()
    }

    /**
     * 距**下一次**目标时刻还有多少毫秒。
     *
     * 今天的目标还没到 → 返回今天那个；已经过了 → 返回明天的。
     * 返回值恒为正：排程用它算延迟，0 会让定时器立刻空转。
     */
    fun millisUntilNextTarget(
        nowMillis: Long,
        minuteOfDay: Int,
        jitterMinutes: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val today = localDate(nowMillis, zone)
        val todayTarget = targetMillis(today, minuteOfDay, jitterMinutes, zone)
        if (nowMillis < todayTarget) return (todayTarget - nowMillis).coerceAtLeast(1L)
        val tomorrow = LocalDate.parse(today).plusDays(1).toString()
        return (targetMillis(tomorrow, minuteOfDay, jitterMinutes, zone) - nowMillis).coerceAtLeast(1L)
    }
}
