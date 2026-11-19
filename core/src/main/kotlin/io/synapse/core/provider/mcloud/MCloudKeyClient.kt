package io.synapse.core.provider.mcloud

import com.google.gson.JsonObject
import io.synapse.core.util.bool
import io.synapse.core.util.long
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
    /** 设备指纹：`chat/config/get` 必带 `x-DeviceInfo` / `x-yun-client-info`，缺了会被网关拒。 */
    private val device: MCloudDevice = MCloudDevice.random(),
    /** 补充接口的失败原因（可选）：用于运行日志，方便排查「为什么没上下文」。 */
    private val onConfigError: (String) -> Unit = {},
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
        // 上下文窗口与联网/思考能力在 `chat/config/get` 里，`apikey/get` 不提供。
        // 这是补充信息：拉取失败不影响登录与对话，只是模型少了上下文/能力标记。
        val configs = runCatching { fetchChatConfig(credential, device) }
            .onFailure { onConfigError("chat/config/get 失败：${it.message}") }
            .getOrDefault(emptyMap())
        val enrichedModels = models.map { model ->
            val config = configs[model.code] ?: return@map model
            model.copy(
                maxInputLength = config.maxInputLength,
                enableNetworkSearch = config.networkSearch,
                enableSetThinking = config.thinking,
            )
        }
        return MCloudAccount(
            uid = credential.userId.ifEmpty { phone },
            phone = phone,
            apiKey = apiKey,
            modelUrl = data.str("modelUrl").ifEmpty {
                "$aiBase/api/llm/$channelId${MCloudConstants.PATH_CHAT_COMPLETIONS}"
            },
            basicCredential = credential.basic,
            nickname = credential.nickname,
            models = enrichedModels,
            createdAtMillis = nowMillis,
            basicExpiresAtMillis = nowMillis + MCloudAccount.BASIC_TTL_MILLIS,
        )
    }

    /**
     * 只补模型能力（上下文 / 联网 / 思考），不换 Key。
     *
     * 老版本落盘的账号没有这些字段（`apikey/get` 本来就不给）。用户点「刷新模型」时
     * 补一次即可，不必为了一个附加字段要求重新登录。失败返回 null，调用方保留旧数据。
     */
    fun enrichModelMeta(account: MCloudAccount): MCloudAccount? {
        if (account.basicCredential.isEmpty() || account.uid.isEmpty()) return null
        val configs = runCatching {
            fetchChatConfig(
                LoginCredential(basic = account.basicCredential, userId = account.uid),
                device,
            )
        }
            .onFailure { onConfigError("补拉模型能力失败：${it.message}") }
            .getOrNull() ?: return null
        if (configs.isEmpty()) return null
        val models = account.models.map { model ->
            val config = configs[model.code] ?: return@map model
            model.copy(
                maxInputLength = config.maxInputLength,
                enableNetworkSearch = config.networkSearch,
                enableSetThinking = config.thinking,
            )
        }
        return account.copy(models = models)
    }

    /** `chat/config/get` 里一条模型的能力配置。 */
    private data class ChatConfig(
        val maxInputLength: Long = 0L,
        val networkSearch: Boolean = false,
        val thinking: Boolean = false,
    )

    /**
     * 拉模型能力配置：上下文长度、联网、思考。
     *
     * 上游 `POST /api/outer/assistant/chat/config/get`，与 `apikey/get` 共用同一套
     * Basic 凭证。请求头照官方 WebView 抓包还原（`移动云盘Ai接口抓包数据.har`）：
     * - `Origin: https://yun.139.com` / `Referer: https://yun.139.com/`（主站来源，不是 vip 域）
     * - `x-yun-api-version: v2`、`x-yun-app-channel: 101`
     * - **设备串必须是 WebView 形态**：抓包里是 `||1|13.2.5||PJE110|<32hex>||android 16|||||`
     *   与 `||36|13.2.5||PJE110|...`（第二段是 WebView 内核版本），
     *   而 App 原生接口用的是 `5|127.0.0.1|1|13.2.5|...`。**两套不能混用**：
     *   发错形态时上游不报参数错误，而是直接回「资源不存在」（`01000005`），
     *   表现就是「接口通了但拿不到数据」——这是最初界面没上下文的真正原因。
     *
     * 它是**补充信息**，不是登录必需项：任何失败都只让调用方拿到空表，
     * 不能因为一个附加接口失败就中断登录/续期。
     */
    private fun fetchChatConfig(
        credential: LoginCredential,
        device: MCloudDevice,
    ): Map<String, ChatConfig> {
        val body = JsonObject().apply {
            add("versionInfo", JsonObject().apply { addProperty("h5Version", "3.4.2") })
            addProperty("userId", credential.userId)
            addProperty("sourceChannel", "101")
        }
        val (status, resp) = MCloudHttp.postJson(
            url = aiBase + MCloudConstants.PATH_CHAT_CONFIG_GET,
            body = body.toString(),
            headers = MCloudHttp.vipHeaders(
                mapOf(
                    "Authorization" to "Basic ${credential.basic}",
                    "x-yun-api-version" to "v2",
                    "x-yun-app-channel" to "101",
                    "Origin" to "https://yun.139.com",
                    "Referer" to "https://yun.139.com/",
                    // 设备指纹：抓包里这个接口是 WebView 形态（两个头第二段版本号不同），
                    // 与 App 原生接口的 device.info 不是一套，必须分开用。
                    "x-DeviceInfo" to device.webviewDeviceInfo,
                    "x-yun-client-info" to device.webviewClientInfo,
                ),
            ),
        )
        val data = MCloudErrors.requireData(status, resp, "获取模型配置")
        val arr = data.get("modelList")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
        val result = HashMap<String, ChatConfig>(arr.size())
        for (el in arr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val code = obj.str("modelType").ifEmpty { obj.str("code") }
            if (code.isEmpty()) continue
            result[code] = ChatConfig(
                maxInputLength = obj.long("maxInputLength"),
                networkSearch = obj.bool("enableNetworkSearch"),
                thinking = obj.bool("enableSetThinking"),
            )
        }
        return result
    }

    /**
     * 用已有的 Basic 凭证续一把新 Key（Key 被服务端作废时用）。
     *
     * 与 [fetchKey] 的区别只在入参：这里没有登录响应，userId 与手机号都从账号本身取。
     * 续期失败就说明 Basic 也过期了，只能请用户重新登录。
     *
     * 续期时 Basic 是新鲜的，`chat/config/get` 才能成功——所以上下文/能力的补齐
     * 主要靠这条路径（老账号的 Basic 早就过期，靠 [enrichModelMeta] 补不上）。
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
            // 模型清单按编码逐条合并：新清单的倍率/名称优先，但**上下文与能力**若这次
            // 没拉到（chat/config/get 失败），必须沿用旧账号里已有的值。
            // 否则一次补充接口抖动就把用户已经拿到的上下文清空——表现为「上下文时有时无」。
            models = mergeModels(fresh.models, account.models),
            identity = account.identity,
        )
    }

    /**
     * 合并新旧模型清单：以新清单为准，但新条目缺的上下文/能力从旧条目继承。
     *
     * 只按 [MCloudModelInfo.code] 对齐——编码是上游唯一标识，名称会改。
     * 新清单为空时整体回退旧清单（续期拿到空目录不代表模型全下架）。
     */
    private fun mergeModels(
        fresh: List<MCloudModelInfo>,
        previous: List<MCloudModelInfo>,
    ): List<MCloudModelInfo> {
        if (fresh.isEmpty()) return previous
        val old = previous.associateBy { it.code }
        return fresh.map { model ->
            val prev = old[model.code] ?: return@map model
            model.copy(
                // 新值有就用新的，没有（0 / false）才继承旧值
                maxInputLength = model.maxInputLength.takeIf { it > 0 } ?: prev.maxInputLength,
                enableNetworkSearch = model.enableNetworkSearch || prev.enableNetworkSearch,
                enableSetThinking = model.enableSetThinking || prev.enableSetThinking,
            )
        }
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
