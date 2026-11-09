package io.synapse.core.gateway

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.util.boolOrNull
import io.synapse.core.util.intOrNull

/**
 * 自动签到的用户偏好。
 *
 * ## 为什么两个开关默认都是关
 *
 * 本项目最硬的一条纪律是「不替用户做决定」（标签不猜、模型不静默替换、额度未知不显示 0）。
 * 按天主动向上游发起签到属于同一类事：没打招呼就替他去签到，与他手动点「一键签到」
 * 是两回事。所以两个开关都默认关，用户自己打开才生效。
 *
 * ## 两条触发路径共用一个「今天已跑过」判断
 *
 * [onLaunchEnabled]（当天第一次打开 App）与 [scheduledEnabled]（挂后台到点）是两个入口，
 * 但**同一天只跑一次** —— 否则会出现「23:59 开 App 签一次、00:01 闹钟又签一次」。
 * 判断落在 `GatewayEngine` 的当天去重里，两个入口都走它。
 *
 * ## 抖动（[jitterMinutes]）
 *
 * 整点批量签到是最典型的机器特征。当天在 `[时间, 时间 + 抖动)` 里**按日期派生**一个固定时刻
 * （同一天永远同一个值，不需要额外持久化），成本几乎为零。设 0 表示不抖动。
 */
data class AutoCheckinSettings(
    /** 当天第一次打开 App 时自动签到。默认关。 */
    val onLaunchEnabled: Boolean = false,

    /** App 挂后台时到点自动签到。默认关。 */
    val scheduledEnabled: Boolean = false,

    /** 定时时刻，从 0 点起的分钟数；默认 0 = 凌晨 00:00。 */
    val scheduledMinuteOfDay: Int = DEFAULT_MINUTE_OF_DAY,

    /** 抖动窗口（分钟）；0 表示不抖动。默认 30 —— 实际落在 00:00–00:30。 */
    val jitterMinutes: Int = DEFAULT_JITTER_MINUTES,

    /**
     * 登录新账号后自动**做任务**（不只手接单领奖，还按配方推进进度）。默认关。
     *
     * 单独一个开关、且默认关：这一项的风控等级比签到高一个量级 ——
     * 它要模拟真实对话埋点，埋点里的会话 id 必须指向真实发出的对话，
     * 自造 id 上游不计进度（见 docs/08「成长任务的风控」）。
     * 签到与「检查任务并提示」不受这个开关影响，始终执行。
     */
    val autoCompleteTasksOnLogin: Boolean = false,
) {

    val hour: Int get() = (scheduledMinuteOfDay / 60).coerceIn(0, 23)
    val minute: Int get() = (scheduledMinuteOfDay % 60).coerceIn(0, 59)

    /** 供界面显示的 `HH:mm`。 */
    fun timeLabel(): String = "%02d:%02d".format(hour, minute)

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("onLaunchEnabled", onLaunchEnabled)
        addProperty("scheduledEnabled", scheduledEnabled)
        addProperty("scheduledMinuteOfDay", scheduledMinuteOfDay)
        addProperty("jitterMinutes", jitterMinutes)
        addProperty("autoCompleteTasksOnLogin", autoCompleteTasksOnLogin)
    }

    companion object {
        const val STORE_KEY = "settings/auto_checkin.json"

        /** 默认 00:00（用户指定的默认值）。 */
        const val DEFAULT_MINUTE_OF_DAY = 0

        /** 默认 30 分钟抖动窗口。 */
        const val DEFAULT_JITTER_MINUTES = 30

        /** 抖动上限：再大就等于「时间不确定」，反而不好解释。 */
        const val MAX_JITTER_MINUTES = 120

        fun fromJson(raw: String): AutoCheckinSettings {
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return AutoCheckinSettings()
            return AutoCheckinSettings(
                onLaunchEnabled = obj.boolOrNull("onLaunchEnabled") ?: false,
                scheduledEnabled = obj.boolOrNull("scheduledEnabled") ?: false,
                // 非法值一律回落默认，绝不让坏数据把排程算到一个荒谬的时刻上
                scheduledMinuteOfDay = (obj.intOrNull("scheduledMinuteOfDay") ?: DEFAULT_MINUTE_OF_DAY)
                    .coerceIn(0, 24 * 60 - 1),
                jitterMinutes = (obj.intOrNull("jitterMinutes") ?: DEFAULT_JITTER_MINUTES)
                    .coerceIn(0, MAX_JITTER_MINUTES),
                autoCompleteTasksOnLogin = obj.boolOrNull("autoCompleteTasksOnLogin") ?: false,
            )
        }
    }
}
