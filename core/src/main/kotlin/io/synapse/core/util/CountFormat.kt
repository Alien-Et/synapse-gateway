package io.synapse.core.util

import java.util.Locale

/**
 * 大数字**压缩**显示（如 23200 → "23.2K"），用于统计行的窄格子。
 *
 * 改名自 formatCount：app 层另有一个同名但行为不同（千分位 1,234）的函数，
 * 同名不同义在跨模块改代码时极易引错包。现在名字直接说明用途。
 *
 * 放在 core 而不是 UI 层，是因为这属于纯格式化逻辑，可以脱离 Android 直接单测——
 * 界面上的换行/撑破问题往往就出在这里。
 */
fun formatCompactCount(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 1_000_000 -> trimTrailingZero(value / 1_000.0) + "K"
    else -> trimTrailingZero(value / 1_000_000.0) + "M"
}

/**
 * `%.1f` 后去掉多余的 `.0`。
 *
 * 固定用 [Locale.US]：小数点为逗号的地区（如德语）会把 3.2 格式化成 "3,2"。
 */
private fun trimTrailingZero(value: Double): String =
    "%.1f".format(Locale.US, value).removeSuffix(".0")

/**
 * token 数的人类友好缩写：3.82M / 276.6K。
 *
 * 下沉到 core 是为了让 UI 层与 provider 层共用同一套口径——以前
 * app/ui/Format.kt 与 MCloudProvider 各写了一份私有实现，改一处漏一处
 * 就会出现「同一个数字在两处显示不一样」。
 */
fun formatTokenCount(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> "%.2fB".format(Locale.US, tokens / 1_000_000_000.0)
    tokens >= 1_000_000L -> "%.2fM".format(Locale.US, tokens / 1_000_000.0)
    tokens >= 1_000L -> "%.1fK".format(Locale.US, tokens / 1_000.0)
    else -> tokens.toString()
}
