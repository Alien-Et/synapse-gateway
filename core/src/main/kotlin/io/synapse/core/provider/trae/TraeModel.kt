package io.synapse.core.provider.trae

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.util.objOrNull
import io.synapse.core.util.str

/** 上游模型目录的一项。 */
data class TraeModel(
    val id: String,
    val name: String,
    /** 上游 `max_input_tokens`；上游未给时为 0（界面据此不展示上下文），不编造默认值。 */
    val contextWindow: Long = 0,
    /**
     * 上游下发的积分倍率（`display_config.display_contact_config.consumption_rate.data.rate`）。
     *
     * ⚠️ 此前架构文档记录「上游不返回倍率」，那是只看了 `get_detail_param` 的顶层字段得出的结论——
     * 倍率其实藏在 `display_config` 里，是个**被转义的 JSON 字符串**，要二次 parse 才看得到。
     * `consumption_rate.enable=false` 或字段缺失时为 null（不编造）。
     */
    val rate: Double? = null,
)

/**
 * `get_detail_param` 的请求体构造与响应解析。
 * 响应结构：`{"config_info_list":[{"config_name","max_input_tokens","display_config":{"display_name"}}]}`
 */
internal object TraePayloadModels {

    fun requestBody(): String = JsonObject().apply {
        addProperty("function", TraeConstants.FUNCTION)
        add("config_names", null)
        addProperty("need_prompt", false)
        add("current_config_info", null)
        addProperty("poly_prompt", true)
        add("mode_type", null)
        add("agent_type", null)
    }.toString()

    fun parse(raw: String): List<TraeModel> {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return emptyList()
        val list = root.get("config_info_list") as? JsonArray ?: return emptyList()
        val out = ArrayList<TraeModel>(list.size())
        for (element in list) {
            val cfg = element as? JsonObject ?: continue
            val id = cfg.str("config_name")
            if (id.isEmpty()) continue
            val display = cfg.objOrNull("display_config")?.str("display_name").orEmpty()
            val context = cfg.long("max_input_tokens").takeIf { it > 0 }
                ?: cfg.long("maxInputTokens").takeIf { it > 0 }
                ?: 0L
            // 倍率在 display_config.display_contact_config 里，而且 display_contact_config
            // 本身是被转义的 JSON 字符串（`"{\"access\":...,\"consumption_rate\":{...}}"`），
            // 要先取字符串再 parse 一次。enable=false 视为未启用、不展示。
            val rate = cfg.objOrNull("display_config")
                ?.str("display_contact_config").orEmpty()
                .takeIf { it.isNotEmpty() }
                ?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
                ?.objOrNull("consumption_rate")
                ?.takeIf { it.get("enable")?.asBoolean != false }
                ?.objOrNull("data")
                ?.get("rate")?.takeIf { it.isJsonPrimitive }
                ?.let { runCatching { it.asDouble }.getOrNull() }
            out.add(
                TraeModel(
                    id = id,
                    name = display.ifEmpty { id },
                    contextWindow = context,
                    rate = rate,
                ),
            )
        }
        return out
    }
}
