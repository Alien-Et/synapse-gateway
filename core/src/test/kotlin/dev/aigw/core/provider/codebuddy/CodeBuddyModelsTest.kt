package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 模型目录解析。
 *
 * 国内（网页控制台 `/console/enterprises/personal/models`）与国际（客户端 product config
 * `/v3/config`）返回的结构相同：`data.models[]` 是模型定义，`data.agents[name=cli].models`
 * 是 CLI 可用白名单。下面的报文取自 `https://www.workbuddy.ai/v3/config` 的真实响应（截取）。
 */
class CodeBuddyModelsTest {

    private val provider = CodeBuddyProvider()

    private fun parse(json: String) =
        provider.parseModelsCatalog(
            JsonParser.parseString(json).asJsonObject.getAsJsonObject("data"),
        )

    @Test
    fun `按 cli 白名单过滤并解析元信息`() {
        val models = parse(
            """
            {
              "code": 0,
              "data": {
                "agents": [
                  {"name": "compact", "models": ["compact-only"]},
                  {"name": "cli", "models": ["default-model", "gpt-6-astra", "glm-5.3", "retired-model"]}
                ],
                "models": [
                  {"id": "default-model", "name": "Auto", "maxInputTokens": 176000,
                   "maxOutputTokens": 24000, "credits": "", "supportsImages": true,
                   "supportsToolCall": true, "vendor": "e"},
                  {"id": "gpt-6-astra", "name": "GPT-6-Astra", "maxInputTokens": 1000000,
                   "maxOutputTokens": 128000, "credits": "x6.67", "supportsImages": true,
                   "supportsReasoning": true, "supportsToolCall": true, "vendor": "e"},
                  {"id": "glm-5.3", "name": "GLM-5.3", "maxInputTokens": 1000000,
                   "maxOutputTokens": 48000, "credits": "x0.79", "supportsToolCall": true},
                  {"id": "retired-model", "name": "Retired", "disabled": true},
                  {"id": "hy3", "name": "Hy3", "maxInputTokens": 192000},
                  {"id": "deepseek-v4.1-flash", "name": "Deepseek-V4.1-Flash", "maxInputTokens": 1000000}
                ]
              }
            }
            """.trimIndent(),
        )

        // 不在 cli 白名单里的（hy3 / deepseek）与 disabled 的都不出现
        assertEquals(listOf("default-model", "gpt-6-astra", "glm-5.3"), models.map { it.id })

        val gpt = models.single { it.id == "gpt-6-astra" }
        assertEquals("GPT-6-Astra", gpt.name)
        assertEquals(1_000_000L, gpt.contextWindow)
        assertEquals("6.67×", gpt.extra["rate"])
        assertEquals("128000", gpt.extra["maxOutput"])
        assertEquals("true", gpt.extra["reasoning"])
        assertEquals("true", gpt.extra["vision"])
        assertEquals("e", gpt.extra["vendor"])

        // credits 为空时不给倍率，不能编造
        assertEquals(null, models.single { it.id == "default-model" }.extra["rate"])
    }

    @Test
    fun `限时活动从 tags 的 badge 里解析出来`() {
        // 真实上游结构（2026-10 实测）：限时活动以 tags 数组里的 `badge:<文案>:<颜色>`
        // 下发，同时 credits 被压到 x0.00。
        val models = parse(
            """
            {"code": 0, "data": {"models": [
              {"id": "hy3", "name": "Hy3", "credits": "x0.00 credits",
               "tags": ["craft", "badge:限时免费:#FF0000"], "maxInputTokens": 192000},
              {"id": "normal", "name": "Normal", "credits": "x0.79 credits",
               "tags": ["craft"]}
            ]}}
            """.trimIndent(),
        )

        val free = models.single { it.id == "hy3" }
        assertEquals("0×", free.extra["rate"])
        assertEquals("0×", free.extra["rateDiscount"], "限时活动要有折扣倍率")
        assertEquals("限时免费", free.extra["rateDiscountTag"], "文案取自 badge")

        // 没有 badge 的模型不应凭空多出折扣字段
        val normal = models.single { it.id == "normal" }
        assertEquals("0.79×", normal.extra["rate"])
        assertNull(normal.extra["rateDiscount"])
        assertNull(normal.extra["rateDiscountTag"])
    }

    @Test
    fun `白名单缺失时列出全部模型`() {
        val models = parse(
            """
            {"code": 0, "data": {"agents": [{"name": "compact", "models": ["c"]}],
             "models": [{"id": "only-model", "name": "Only"}]}}
            """.trimIndent(),
        )
        assertEquals(listOf("only-model"), models.map { it.id })
    }
}
