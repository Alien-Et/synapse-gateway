package dev.aigw.core.provider.trae

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `get_detail_param` 的模型目录解析。
 *
 * 重点锁住倍率：它藏在 `display_config.display_contact_config` 里，而后者是
 * **被转义的 JSON 字符串**，需要二次 parse 才能拿到 `consumption_rate.data.rate`。
 */
class TraePayloadModelsTest {

    private fun parse(raw: String) = TraePayloadModels.parse(raw)

    @Test
    fun `倍率从被转义的 display_contact_config 里解析出来`() {
        // display_contact_config 是字符串形态的 JSON（真实上游结构，2026-10 实测）
        val body = """
        {"config_info_list":[
          {"config_name":"glm-5.3","max_input_tokens":200000,
           "display_config":{"display_name":"GLM-5.3",
             "display_contact_config":"{\"access\":{\"data\":{}},\"consumption_rate\":{\"enable\":true,\"data\":{\"rate\":0.7}}}"}},
          {"config_name":"free-model","max_input_tokens":100000,
           "display_config":{"display_name":"Free",
             "display_contact_config":"{\"consumption_rate\":{\"enable\":true,\"data\":{\"rate\":0.0}}}"}}
        ]}
        """.trimIndent()

        val models = parse(body)
        assertEquals(2, models.size)
        assertEquals(0.7, models[0].rate, "倍率应从 consumption_rate.data.rate 读到")
        assertEquals("GLM-5.3", models[0].name)
        assertEquals(200_000L, models[0].contextWindow)
        assertEquals(0.0, models[1].rate)
    }

    @Test
    fun `consumption_rate 未启用时不给倍率`() {
        val body = """
        {"config_info_list":[
          {"config_name":"no-rate",
           "display_config":{"display_name":"NoRate",
             "display_contact_config":"{\"consumption_rate\":{\"enable\":false,\"data\":{\"rate\":0.5}}}"}}
        ]}
        """.trimIndent()
        assertNull(parse(body)[0].rate, "enable=false 时不展示倍率")
    }

    @Test
    fun `缺少 display_contact_config 时不崩且不给倍率`() {
        val body = """
        {"config_info_list":[{"config_name":"plain","display_config":{"display_name":"Plain"}}]}
        """.trimIndent()
        val model = parse(body).single()
        assertEquals("Plain", model.name)
        assertNull(model.rate)
    }
}
