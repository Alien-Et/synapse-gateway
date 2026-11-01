package io.synapse.core.provider.mcloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.util.long
import io.synapse.core.util.str

/**
 * 一个中国移动云盘（139 云）账号。
 *
 * 凭证分两层，缺一不可：
 * - [apiKey]：长期有效的 LLM Key，对话时当 Bearer 用。**实测不会自动轮换**，
 *   所以换新 Key 只能重新走 `apikey/get`。
 * - [basicCredential]：`Authorization: Basic` 头里的原始 Base64（`mobile:手机号:token`）。
 *   `apikey/get` 认它，Key 失效时靠它重新换一把，不用再走一遍登录。
 *
 * [basicExpiresAtMillis] 是 Basic 凭证的预估过期时间。上游没在 body 里给过期字段，
 * 但 token 段是带时间戳的签名，官方 WebView 约 4 小时就要求重新取，
 * 所以这里保守按 [BASIC_TTL_MILLIS] 记；到期前 [MCloudProvider] 会先用
 * [refreshAccount] 自动续，续不上再提示用户重新登录。
 */
data class MCloudAccount(
    /** 上游用户 ID（`apikey/get` 的 `userId`）。 */
    val uid: String,
    /** 登录手机号，仅用于展示与换 Key。 */
    val phone: String,
    /** 长期 LLM Key。 */
    val apiKey: String,
    /** 对话地址（上游 `modelUrl` 下发，含渠道号，不能自己拼）。 */
    val modelUrl: String = "${MCloudConstants.AI_BASE}/api/llm/${MCloudConstants.CHANNEL_ID}${MCloudConstants.PATH_CHAT_COMPLETIONS}",
    /** `apikey/get` 用的 Basic 凭证；为空表示没法自动续 Key。 */
    val basicCredential: String = "",
    val nickname: String = "",
    /** 账号身份：会员等级等，用于界面区分（如「白银会员」）。 */
    val identity: String = "",
    /** 模型清单（模型编码 → 展示名），登录时一起落盘，离线也能列出模型。 */
    val models: List<MCloudModelInfo> = emptyList(),
    val createdAtMillis: Long = 0L,
    val basicExpiresAtMillis: Long = 0L,
) {
    /** Key 是否还有机会自动续：Basic 凭证得在，且没到期。 */
    fun canAutoRefresh(nowMillis: Long): Boolean =
        basicCredential.isNotEmpty() && (basicExpiresAtMillis <= 0L || nowMillis < basicExpiresAtMillis)

    /**
     * 换完 Key 后的新账号：保留身份信息，只更新 Key 与过期时间。
     *
     * 单独写成一个方法而不是 copy 一堆字段，是为了让「刷新」这条路径只碰它该碰的东西——
     * 之前别家供应商刷新时把昵称清空过，属于同一类事故。
     */
    fun withRefreshed(apiKey: String, modelUrl: String, basicExpiresAtMillis: Long): MCloudAccount =
        copy(
            apiKey = apiKey,
            modelUrl = modelUrl.ifEmpty { this.modelUrl },
            basicExpiresAtMillis = basicExpiresAtMillis,
        )

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("uid", uid)
        addProperty("phone", phone)
        addProperty("apiKey", apiKey)
        addProperty("modelUrl", modelUrl)
        addProperty("basicCredential", basicCredential)
        addProperty("nickname", nickname)
        addProperty("identity", identity)
        addProperty("createdAt", createdAtMillis)
        addProperty("basicExpiresAt", basicExpiresAtMillis)
        if (models.isNotEmpty()) {
            add("models", com.google.gson.JsonArray().apply {
                models.forEach { add(it.toJson()) }
            })
        }
    }

    companion object {

        /** Basic 凭证保守有效期：4 小时。 */
        const val BASIC_TTL_MILLIS = 4 * 60 * 60 * 1000L

        fun parse(raw: String): MCloudAccount? =
            runCatching { fromJson(JsonParser.parseString(raw).asJsonObject) }.getOrNull()

        fun fromJson(obj: JsonObject): MCloudAccount {
            val models = obj.get("models")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { el ->
                runCatching { MCloudModelInfo.fromJson(el.asJsonObject) }.getOrNull()
            }.orEmpty()
            return MCloudAccount(
                uid = obj.str("uid"),
                phone = obj.str("phone"),
                apiKey = obj.str("apiKey"),
                modelUrl = obj.str("modelUrl").ifEmpty {
                    "${MCloudConstants.AI_BASE}/api/llm/${MCloudConstants.CHANNEL_ID}${MCloudConstants.PATH_CHAT_COMPLETIONS}"
                },
                basicCredential = obj.str("basicCredential"),
                nickname = obj.str("nickname"),
                identity = obj.str("identity"),
                models = models,
                createdAtMillis = obj.long("createdAt"),
                basicExpiresAtMillis = obj.long("basicExpiresAt"),
            )
        }
    }
}

/**
 * 上游下发的一条模型信息。
 *
 * [code] 是真正要填进 `model` 字段的编码（如 `blian_qwen38_flash`），
 * [name] 是给人看的名字（如 `qwen3.8-Flash`）——路由与展示分别用这两个，别混。
 */
data class MCloudModelInfo(
    val code: String,
    val name: String,
    /** 描述文案，如「轻量高效，适配高频次问答场景」。 */
    val ability: String = "",
    /** 计费倍率（上游给的字符串，如 `0.18`）。空串表示没有。 */
    val tokenRatio: String = "",
    /** 图标地址，界面可选展示。 */
    val iconUrl: String = "",
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("code", code)
        addProperty("name", name)
        addProperty("ability", ability)
        addProperty("tokenRatio", tokenRatio)
        addProperty("iconUrl", iconUrl)
    }

    companion object {
        fun fromJson(obj: JsonObject): MCloudModelInfo = MCloudModelInfo(
            code = obj.str("modelCode").ifEmpty { obj.str("code") },
            name = obj.str("modelName").ifEmpty { obj.str("name") },
            ability = obj.str("ability"),
            tokenRatio = obj.str("tokenRatio"),
            iconUrl = obj.str("iconUrl"),
        )
    }
}
