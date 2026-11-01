package io.synapse.core.provider.mcloud

import com.google.gson.JsonObject
import io.synapse.core.util.str

/**
 * 用 Basic 凭证换长期 LLM Key 的客户端。
 *
 * 上游 `POST /api/llm/apikey/get`，body 是 `{userId, channelId}`，
 * 头里带 `Authorization: Basic <credential>`。返回 apikey + modelUrl + 完整模型清单。
 *
 * ## 为什么要单独一层
 * 这既是「首次登录」的最后一步，也是「Key 失效」时唯一的续期手段：
 * Basic 凭证有效期短（约 4 小时），apikey 长期但会被服务端作废。
 * 两者任何一方都在变化，所以每次拿到结果都要整份落盘，不能只更新 apikey。
 */
class MCloudKeyClient(
    private val aiBase: String = MCloudConstants.AI_BASE,
    private val channelId: String = MCloudConstants.CHANNEL_ID,
) {

    /**
     * 用 [credential] 换一套完整的账号信息。
     *
     * [userId] 取自登录响应；上游其实也用 Basic 里的手机号反查，
     * 但传上去更稳（实测空 userId 会回 `500400`）。
     */
    fun fetchKey(credential: LoginCredential, phone: String, nowMillis: Long): MCloudAccount {
        val body = JsonObject().apply {
            addProperty("userId", credential.userId)
            addProperty("channelId", channelId)
        }
        val (status, resp) = MCloudHttp.postJson(
            url = aiBase + MCloudConstants.PATH_APIKEY_GET,
            body = body.toString(),
            headers = MCloudHttp.vipHeaders(mapOf("Authorization" to "Basic ${credential.basic}")),
        )
        val data = MCloudErrors.requireData(status, resp, "获取调用密钥")

        val apiKey = data.str("apikey").ifEmpty { data.str("apiKey") }
        if (apiKey.isEmpty()) {
            throw MCloudApiException(status, MCloudErrorKind.CLIENT, resp, "NO_APIKEY")
        }
        val models = parseModels(data)
        return MCloudAccount(
            uid = credential.userId.ifEmpty { phone },
            phone = phone,
            apiKey = apiKey,
            modelUrl = data.str("modelUrl").ifEmpty {
                "$aiBase/api/llm/$channelId${MCloudConstants.PATH_CHAT_COMPLETIONS}"
            },
            basicCredential = credential.basic,
            nickname = credential.nickname,
            models = models,
            createdAtMillis = nowMillis,
            basicExpiresAtMillis = nowMillis + MCloudAccount.BASIC_TTL_MILLIS,
        )
    }

    /**
     * 用已有的 Basic 凭证续一把新 Key（Key 被服务端作废时用）。
     *
     * 与 [fetchKey] 的区别只在入参：这里没有登录响应，userId 与手机号都从账号本身取。
     * 续期失败就说明 Basic 也过期了，只能请用户重新登录。
     */
    fun refreshKey(account: MCloudAccount, nowMillis: Long): MCloudAccount {
        val credential = LoginCredential(basic = account.basicCredential, userId = account.uid)
        val fresh = fetchKey(credential, account.phone, nowMillis)
        // 续期只该更新 Key 与过期时间：昵称/身份是登录时一次性拿到的，别被空值覆盖
        return account.withRefreshed(
            apiKey = fresh.apiKey,
            modelUrl = fresh.modelUrl,
            basicExpiresAtMillis = fresh.basicExpiresAtMillis,
        ).copy(
            models = fresh.models.ifEmpty { account.models },
            identity = account.identity,
        )
    }

    /** 解析 `modelList`；字段缺失的条目直接跳过，不让个别脏数据毁掉整个目录。 */
    private fun parseModels(data: JsonObject): List<MCloudModelInfo> {
        val arr = data.get("modelList")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching { MCloudModelInfo.fromJson(el.asJsonObject) }
                .getOrNull()
                ?.takeIf { it.code.isNotEmpty() }
        }
    }
}
