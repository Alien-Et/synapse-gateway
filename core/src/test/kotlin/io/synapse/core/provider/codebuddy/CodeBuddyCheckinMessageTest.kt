package io.synapse.core.provider.codebuddy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 签到失败时「原因」的提取口径。
 *
 * 现场问题：批量签到把「今天已签到，请明天再来」当成了失败，显示成
 * ```
 * 签到（国内 https://www.codebuddy.cn/v2/billing/meter/daily-checkin）HTTP 400：今天已签到，请明天再来
 * ```
 *
 * 根因有两层：
 * ① `extractMessage`（用于判断「已签到」）只认 `msg` / `message` / `error.message`，
 *    而 `describeBody`（用于显示）还认 `desc` 和字符串形式的 `error`
 *    —— 于是出现「界面能显示原因、逻辑却识别不出原因」的错位；
 * ② 「已签到」的关键词只有 2 个，覆盖不到「今日已完成」「明日再来」这类说法。
 *
 * 这里锁住「提取口径一致」与「常见说法都能识别」。
 */
class CodeBuddyCheckinMessageTest {

    @Test
    fun `字符串形式的 error 字段也能取到原因`() {
        val body = """{"code":400,"error":"今天已签到，请明天再来"}"""
        assertTrue(
            extractCodeBuddyMessage(body).contains("已签到"),
            "字符串 error 必须能取到，否则和 describeBody 口径不一致",
        )
    }

    @Test
    fun `desc 字段与 msg message 一样被认可`() {
        assertEquals("今天已签到", extractCodeBuddyMessage("""{"desc":"今天已签到"}"""))
        assertEquals("今天已签到", extractCodeBuddyMessage("""{"msg":"今天已签到"}"""))
        assertEquals("今天已签到", extractCodeBuddyMessage("""{"message":"今天已签到"}"""))
        assertEquals("今天已签到", extractCodeBuddyMessage("""{"error":{"message":"今天已签到"}}"""))
    }

    @Test
    fun `常见已签到说法都能被识别`() {
        val cases = listOf(
            "今天已签到，请明天再来",
            "今日已签到",
            "您已经签到过啦",
            "今日已完成",
            "今日已领取",
            "Please come back tomorrow",
            "Already checked in today",
        )
        for (text in cases) {
            assertTrue(isAlreadyCheckedInText(text), "应识别为已签到：$text")
        }
    }

    @Test
    fun `真正的失败不会被误判成已签到`() {
        val cases = listOf(
            "签到失败",
            "系统繁忙，请稍后再试",
            "token 已过期",
            "",
        )
        for (text in cases) {
            assertTrue(!isAlreadyCheckedInText(text), "不应识别为已签到：$text")
        }
    }
}
