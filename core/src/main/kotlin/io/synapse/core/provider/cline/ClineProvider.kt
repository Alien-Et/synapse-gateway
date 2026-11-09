package io.synapse.core.provider.cline

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.provider.AggregatedChatCall
import io.synapse.core.provider.AuthKind
import io.synapse.core.provider.ChatCall
import io.synapse.core.provider.CreditInfo
import io.synapse.core.provider.DeviceAuthPoll
import io.synapse.core.provider.DeviceCodeSupport
import io.synapse.core.provider.DeviceAuthTicket
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.FailedChatCall
import io.synapse.core.provider.LineTransformStream
import io.synapse.core.provider.OpenAiSseAggregator
import io.synapse.core.provider.Provider
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderCapability
import io.synapse.core.provider.ProviderHooks
import io.synapse.core.provider.ProviderModel
import io.synapse.core.provider.ProviderModelCatalogView
import io.synapse.core.provider.SSE_DONE
import io.synapse.core.provider.StreamFailureChatCall
import io.synapse.core.provider.StreamingChatCall
import io.synapse.core.provider.UpstreamError
import io.synapse.core.provider.isStreamingBody
import io.synapse.core.provider.requestedModelOf
import io.synapse.core.util.HttpSupport
import io.synapse.core.util.NetworkErrorText
import io.synapse.core.util.arrayOrNull
import io.synapse.core.util.asObjectOrNull
import io.synapse.core.util.bodySnippet
import io.synapse.core.util.longOrNull
import io.synapse.core.util.newCompletionId
import io.synapse.core.util.objOrNull
import io.synapse.core.util.stringOrNull
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets

/**
 * Cline（`api.cline.bot`）—— 免费池与订阅池共用一套实现。
 *
 * ## 为什么是**一家** provider，而不是 `cline-free` / `cline-pass` 两家
 *
 * 上游把两个额度池挂在**同一个账号**上：同一个 `workos:` 令牌既能打
 * `cline-free/xxx`，也能打 `cline-pass/xxx`。池是「模型走哪条计费通道」，
 * **不是账号的属性**。
 *
 * 本项目已有的 [io.synapse.core.provider.RegionAwareSupport] 建模的是
 * 「账号属于哪个站点」（腾讯的 cn/global），套到 Cline 上会出三个错：
 *
 * 1. 会为两个池**各拉一次**目录 —— 上游一个接口就返回四组，重复拉没有意义；
 * 2. 该池没有账号就不生成分组 —— 一个令牌两池都能打，却因为「免费池没有
 *    专门账号」而不显示免费模型；
 * 3. `pickAccount(region=...)` 硬筛 `regionOf(account) == region` ——
 *    同一个号两池都行，硬筛是 bug。
 *
 * 所以池的信息只体现在**模型 id 的前缀**里（`cline-free/...`），
 * 那是上游自己的语义。选号不做任何池过滤。
 *
 * ## 对话链路
 *
 * ```
 * POST https://api.cline.bot/api/v1/chat/completions
 * ```
 *
 * **路径里只有一个 `v1`**：上游用 AI SDK 的 `createOpenAICompatible`，把 baseURL
 * 当**根**拼。发错路径的表现是 `404 {"error":"Not Found"}`。
 *
 * ## 三处必须照做的上游规矩
 *
 * 1. **`X-CLIENT-TYPE: cline-sdk` 是免费池的硬门槛**。缺了它免费池模型一律 403
 *    （`only available via Cline product surfaces`）。取值不能换成 `cline-cli`
 *    —— 那会让上游走另一条兼容路径，回 `500 empty response content`。
 * 2. **`workos:` 前缀原样带上**，去掉就 401（见 `ClineCredential`）。
 * 3. **`model` 里的池前缀是计费通道选择器**，剥掉转发会 404 `model not found`。
 *
 * ## 请求体是**白名单重建**，不是原样透传
 *
 * 上游虽然 OpenAI 兼容，但只认有限字段。重建时注入三样东西：`max_tokens`
 * 默认 128000、`session_id`（`sess_<毫秒>`，与 `X-Task-ID` 头同值）、
 * `reasoning_effort` 默认 `high`。白名单之外的键一律丢弃。
 *
 * ⚠️ 客户端给了**小的** `max_tokens` 时**不要强制抬高**：上游在 max_tokens 太小
 * 而模型又要先输出一大段 reasoning 时会回 `500 empty response content`，
 * 那是客户端自己的取舍。
 *
 * ## 错误分类（实测三种错误体）
 *
 * | 状态 | 归类 | 理由 |
 * |---|---|---|
 * | 401 | [ErrorKind.SESSION_DEAD] | 刷新后同账号重试一次 |
 * | 403 `ENTITLEMENT_ERROR` | [ErrorKind.MODEL_LIMIT] | 「这个号没这个模型的权限」，不是凭证失效 |
 * | 403 其它 | [ErrorKind.SESSION_DEAD] | 确实可能是令牌问题 |
 * | 404 `model not found` | [ErrorKind.NOT_FOUND] | |
 * | 429 | [ErrorKind.SOFT_RATE] | 模型级限流由 `isModelLimited` 改判 |
 *
 * **403 归 `MODEL_LIMIT` 而不是 `SESSION_DEAD` 是关键**：后者会
 * `pool.disable()` 硬禁用账号，用户必须重新登录才能解封 —— 而权限问题
 * 重新登录根本解决不了。[ErrorKind.MODEL_LIMIT] 的处置恰好是
 * 「先换同家其它号，全试完才冷却该模型」，语义完全对得上。
 */
class ClineProvider(
    private val hooks: ProviderHooks = ProviderHooks(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /**
     * 上游 API 基址。默认 [API_BASE_URL]；测试注入本地假上游时改这里。
     *
     * 与 `RaccoonProvider(host = ...)` 同一做法：协议回归要能打本地
     * `HttpServer`，否则每个用例都得连真实上游。
     */
    private val apiBaseUrl: String = API_BASE_URL,
) : Provider, DeviceCodeSupport {

    // 登录流程与协议主体共用同一组基址：测试注入本地假服务时两边一起改，
    // 不会出现「对话打本地、登录打真实上游」这种半截状态。
    private val login = ClineLogin(
        hooks = hooks,
        nowMillis = nowMillis,
        // 登录打的是 WorkOS，不是 Cline API —— 两者是不同域名，不能共用 apiBaseUrl
        workosBaseUrl = ClineLogin.WORKOS_BASE_URL,
        apiBaseUrl = apiBaseUrl,
    )

    override val id: String = ID
    override val displayName: String = "Cline"
    override val authKind: AuthKind = AuthKind.DEVICE_CODE
    override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.CREDIT_REFRESH)

    // ------------------------------------------------------------------ 模型

    /**
     * 拉模型目录。
     *
     * `GET /ai/cline/recommended-models` **无鉴权也能拉** —— 所以登录前也能出清单，
     * 只是没有账号时上游会按「未登录」返回一个子集。
     *
     * ## 归池一律看分组，不看前缀
     *
     * `free` 组实测 5 条里有 **2 条是别家的裸 id**（`z-ai/glm-5.3-flash`、
     * `poolside/laguna-s-2.1:free`）。它们同样是免费池成员（走 OpenRouter 那类
     * 通道转售），只是上游没加自己的通道前缀。按前缀猜池会把这 2 条错判进订阅池，
     * 而订阅池没账号时整家不广告 —— 结果它们在界面上**彻底消失**。
     *
     * ## `clineCloud` 组不收录
     *
     * 实测打它返回 403（未开通）。广告一个必然 403 的模型，只会让客户端在列表里
     * 选中它然后失败。
     */
    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        val (status, text) = try {
            // 目录响应带内嵌图标，可能上百 KB：允许 gzip（平台自己带上并透明解压）
            get("$apiBaseUrl/ai/cline/recommended-models", modelCatalogHeaders(), allowCompression = true)
        } catch (e: Exception) {
            hooks.onLog("Cline 拉取模型目录失败：${NetworkErrorText.logText(e, HOST)}")
            return ProviderModelCatalogView(emptyList(), fromFallback = false, error = NetworkErrorText.describe(e, HOST))
        }
        if (status !in 200..299) {
            val reason = extractMessage(text).ifEmpty { "HTTP $status" }
            hooks.onLog("Cline 拉取模型目录失败：$reason")
            return ProviderModelCatalogView(emptyList(), fromFallback = false, error = "拉取模型列表失败：$reason")
        }
        val payload = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return ProviderModelCatalogView(emptyList(), fromFallback = false, error = "模型列表响应不是合法 JSON")
        val data = payload.objOrNull("data") ?: payload
        val models = LinkedHashSet<ProviderModel>()
        // 三个组都收录（clineCloud 除外）。顺序 = 免费池、订阅池、推荐位。
        //
        // 不做「订阅状态过滤」：目录接口一次返回四组，多打一次 `/users/me/plan`
        // 只为少显示几条，代价与收益不成比例；而模型列表有缓存，过滤会时灵时不灵
        // ——用户看到的行为前后不一致，比看到 403 更困惑。没订阅时如实报 403
        // （`the user is not subscribed to required model plan`）已经足够说清原因。
        collectPoolModels(data, "free", POOL_FREE, models)
        collectPoolModels(data, "clinePass", POOL_PASS, models)
        collectPoolModels(data, "recommended", POOL_NONE, models)
        if (models.isEmpty()) {
            return ProviderModelCatalogView(emptyList(), fromFallback = false, error = "上游返回空模型列表")
        }
        return ProviderModelCatalogView(models.toList(), fromFallback = false, error = "")
    }

    /**
     * 收一个分组里的模型。
     *
     * [pool] 为空串表示「该组没有池归属信息」（`recommended` 组）：只能按 id 前缀
     * 兜底判池，判不出来就标空 —— 那组实测走 credit 计费，与两个池都不同，
     * 判进哪池都只是「挂在哪家广告」的问题，不该假装知道。
     */
    private fun collectPoolModels(
        data: JsonObject,
        group: String,
        pool: String,
        into: MutableSet<ProviderModel>,
    ) {
        val entries = data.arrayOrNull(group) ?: return
        for (element in entries) {
            val item = element.asObjectOrNull() ?: continue
            val modelId = item.firstNonEmpty("id", "model", "name")
            if (modelId.isEmpty()) continue
            val displayName = item.firstNonEmpty("name", "displayName", "display_name", "title")
                .ifEmpty { modelId.substringAfterLast('/') }
            val contextWindow = item.firstPositiveLong(
                "maxInputTokens", "max_input_tokens", "context_window", "contextWindow",
            )
            val supportsImages = item.get("supportsImages")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            val supportsReasoning =
                item.get("supportsReasoning")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            into.add(
                ProviderModel(
                    id = modelId,
                    name = displayName,
                    contextWindow = contextWindow,
                    extra = buildMap {
                        // 池归属：分组优先；recommended 组没有池信息，按 id 前缀兜底。
                        val resolvedPool = pool.ifEmpty { poolOfId(modelId) }
                        if (resolvedPool.isNotEmpty()) put(EXTRA_POOL, resolvedPool)
                        if (supportsImages) put("tags", "image-input")
                        if (supportsReasoning) put("reasoning", "true")
                    },
                ),
            )
        }
    }

    /** 按 id 前缀判池（只在分组没给池信息时兜底用）。 */
    private fun poolOfId(modelId: String): String = when {
        modelId.startsWith(FREE_PREFIX, ignoreCase = true) -> POOL_FREE
        modelId.startsWith(PASS_PREFIX, ignoreCase = true) -> POOL_PASS
        modelId.startsWith(CLOUD_PREFIX, ignoreCase = true) -> POOL_CLOUD
        else -> POOL_NONE
    }

    /**
     * 模型名归一化：**原样返回，不补池前缀**。
     *
     * 两个池都有 `deepseek-v4.1-flash`，短名补哪个池都是**替用户做决定**，
     * 违背本项目「不静默替换模型」的纪律（见 README 的模型名契约）。
     *
     * 代价是客户端要写全 `Cline/cline-free/deepseek-v4.1-flash`；
     * 收益是零歧义 —— 用户点名哪个池就走哪个池。
     */
    override fun resolveModel(requested: String): String = requested.trim()

    /**
     * 云端池的模型算「内部条目」：实测 403，不该出现在「只看可用模型」的列表里。
     *
     * 与 `TraeProvider.isInternalModel` 同一用途，判定只看 id 前缀。
     */
    override fun isInternalModel(id: String): Boolean =
        id.startsWith(CLOUD_PREFIX, ignoreCase = true)

    override fun hosts(): List<String> = listOf(HOST)

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = parseClineCredential(account.secret)
            ?: return FailedChatCall(401, "凭证无法解析，请重新登录")
        // 已确定过期就别带着它打上游：那会拿到 401 被分类成 SESSION_DEAD，
        // 一次瞬时问题就变成用户看到的「登录失效」。让网关先去续期。
        if (credential.expiresWithin(0, nowMillis())) {
            return FailedChatCall(401, "凭证已过期，请重新登录或等待自动续期")
        }
        val streaming = isStreamingBody(openAiBody)
        val requestedModel = requestedModelOf(openAiBody)
        // 上游恒为流式（非流式响应带信封，主链路不走那条路）。
        val body = buildUpstreamBody(openAiBody, newSessionId())
        val conn = try {
            post("$apiBaseUrl/chat/completions", body, chatHeaders(credential.accessToken))
        } catch (e: Exception) {
            hooks.onLog("Cline 请求异常：${NetworkErrorText.logText(e, HOST)}")
            return FailedChatCall(0, NetworkErrorText.describe(e, HOST))
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            hooks.onLog("Cline 请求异常：${NetworkErrorText.logText(e, HOST)}")
            return FailedChatCall(0, NetworkErrorText.describe(e, HOST))
        }
        return finishChat(conn, status, streaming, requestedModel)
    }

    /**
     * 处理上游响应。
     *
     * 上游的响应形态**不对称**：`stream: true` 是**裸 chunk**（可直传），
     * 非流式才是 `{"data":{...},"success":true}` 信封。我方恒以流式请求上游，
     * 所以主链路只走前者；客户端要非流式时本地聚合（复用
     * [OpenAiSseAggregator]，与 CodeBuddy / 小浣熊同一套）。
     */
    private fun finishChat(
        conn: HttpURLConnection,
        status: Int,
        streaming: Boolean,
        requestedModel: String,
    ): ChatCall {
        if (status !in 200..299) {
            val errorBody = runCatching { HttpSupport.readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, errorBody)
        }
        val contentType = conn.contentType.orEmpty()
        val stream = conn.inputStream
        if (contentType.contains("event-stream", ignoreCase = true)) {
            val translated = LineTransformStream(
                source = stream,
                transform = { line -> rewriteSseModel(line, requestedModel) },
            )
            if (streaming) {
                return StreamingChatCall(status, translated) { conn.disconnect() }
            }
            val aggregated = try {
                OpenAiSseAggregator.aggregate(translated, requestedModel)
            } finally {
                conn.disconnect()
            }
            if (OpenAiSseAggregator.isEmptyCompletion(aggregated)) {
                return emptyUpstreamFailure(status, contentType)
            }
            return AggregatedChatCall(status, aggregated)
        }

        // 上游没按 SSE 回（正常不该发生）：整段读完，剥信封后按需包成 SSE。
        val text = runCatching { stream.use { HttpSupport.readLimited(it) } }.getOrDefault("")
        conn.disconnect()
        val completion = unwrapCompletion(text, requestedModel)
            ?: return StreamFailureChatCall(
                status,
                UpstreamError(
                    ErrorKind.CLIENT,
                    "上游响应无法解析（HTTP $status）：${bodySnippet(text, 160)}",
                ),
            )
        if (OpenAiSseAggregator.isEmptyCompletion(completion)) {
            return emptyUpstreamFailure(status, contentType)
        }
        if (streaming) {
            return StreamingChatCall(
                status,
                OpenAiSseAggregator.completionAsSse(completion).byteInputStream(StandardCharsets.UTF_8),
            )
        }
        return AggregatedChatCall(status, completion)
    }

    /**
     * 上游 200 但没给出任何内容：保持**可检测的失败**。
     *
     * 当成「成功的空回复」会让客户端只看到「输出完成却什么都没有」，
     * 而这在实际排查里是最难定位的一类现象。
     */
    private fun emptyUpstreamFailure(status: Int, contentType: String): ChatCall = StreamFailureChatCall(
        status,
        UpstreamError(
            ErrorKind.CLIENT,
            "上游返回了空响应（HTTP $status，Content-Type: ${contentType.ifEmpty { "—" }}）",
        ),
    )

    /**
     * 把非流式响应剥成标准 `chat.completion`。
     *
     * 上游的信封是 `{"data":{...},"success":true}`，但也可能直接给扁平对象
     * （不同端点的口径不完全一致）。两种都认，取不到 `choices` 返回 null。
     */
    private fun unwrapCompletion(text: String, requestedModel: String): String? {
        val root = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val data = root.objOrNull("data") ?: root
        val choices = data.arrayOrNull("choices") ?: return null
        return JsonObject().apply {
            addProperty("id", data.stringOrNull("id").orEmpty().ifEmpty { newCompletionId() })
            addProperty("object", "chat.completion")
            addProperty("created", data.longOrNull("created") ?: (nowMillis() / 1000))
            // model 改回客户端请求的那个：上游给的是内部名（如 deepseek/deepseek-v4.1-flash）
            addProperty("model", requestedModel)
            add("choices", choices)
            (data.objOrNull("usage") ?: root.objOrNull("usage"))?.let { add("usage", it) }
        }.toString()
    }

    /**
     * SSE 单行改写：只动 `model` 字段，其余原样透传。
     *
     * 上游 SSE 帧里的 `model` 是**上游内部名**（`deepseek/deepseek-v4.1-flash`），
     * 直接透传会让客户端看到自己没请求过的模型名。改写失败时返回原文 ——
     * 一行解析不了不能把整条流掐断（[LineTransformStream] 的容错约定）。
     */
    private fun rewriteSseModel(line: String, requestedModel: String): List<String> {
        if (requestedModel.isEmpty()) return listOf("$line\n\n")
        if (!line.startsWith("data:", ignoreCase = true)) return listOf("$line\n\n")
        val payload = line.removePrefix("data:").removePrefix("DATA:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return listOf("$line\n\n")
        val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
            ?: return listOf("$line\n\n")
        if (!obj.has("model")) return listOf("$line\n\n")
        obj.addProperty("model", requestedModel)
        return listOf("data: ${obj}\n\n")
    }

    /**
     * 构造上游请求体：**白名单重建**，不是原样透传。
     *
     * 固定键：`model`（含池前缀，绝不能剥）、`max_tokens`、`session_id`、
     * `reasoning_effort`、`messages`、`stream`。
     * 其余只透传 [PASS_THROUGH_KEYS] 里客户端真给了的那些。
     *
     * `stream` 恒为 `true`：上游的非流式响应带信封，主链路统一走流式，
     * 客户端要非流式时由 [OpenAiSseAggregator] 本地聚合。
     */
    private fun buildUpstreamBody(openAiBody: String, sessionId: String): String {
        val src = runCatching { JsonParser.parseString(openAiBody).asJsonObject }.getOrNull()
            ?: return openAiBody
        val out = JsonObject()
        src.get("model")?.let { out.add("model", it) }
        // max_tokens：客户端显式给了正数就用它（不抬高），否则默认 128000。
        // ≤0 不算「显式小值」—— 上游只会当参数错误，没有「用户本意」可谈。
        val explicitMax = positiveDouble(src, "max_tokens")
            ?: positiveDouble(src, "max_completion_tokens")
        out.addProperty("max_tokens", explicitMax?.toLong() ?: DEFAULT_MAX_TOKENS)
        out.addProperty("session_id", sessionId)
        out.addProperty("reasoning_effort", explicitReasoningEffort(src) ?: DEFAULT_REASONING_EFFORT)
        src.get("messages")?.let { out.add("messages", it) }
        // 恒为流式：非流式响应带信封，主链路统一走流式再本地聚合。
        out.addProperty("stream", true)
        for (key in PASS_THROUGH_KEYS) {
            src.get(key)?.let { out.add(key, it) }
        }
        return out.toString()
    }

    /** 客户端**显式**给的思考档位：`reasoning_effort` → 驼峰 `reasoningEffort`。 */
    private fun explicitReasoningEffort(src: JsonObject): String? {
        val raw = src.stringOrNull("reasoning_effort")?.trim().orEmpty()
            .ifEmpty { src.stringOrNull("reasoningEffort")?.trim().orEmpty() }
        return raw.ifEmpty { null }
    }

    private fun positiveDouble(src: JsonObject, key: String): Double? {
        val value = src.get(key)?.takeIf { it.isJsonPrimitive }?.let {
            runCatching { it.asDouble }.getOrNull()
        } ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        return value
    }

    /** 会话标识：`sess_<毫秒>`，与官方 CLI 同形态，且与 `X-Task-ID` 头同值。 */
    private fun newSessionId(): String = "sess_${nowMillis()}"

    // ------------------------------------------------------------------ 请求头

    /**
     * 对话请求头 = 固定四头 + 九个伪装头。
     *
     * 固定四头是协议必需 / 每请求动态生成，**不可被用户覆盖**；
     * 伪装头里 `X-CLIENT-TYPE` 是免费池的硬门槛（见类注释）。
     *
     * 版本号沿用适配器实测口径（`3.0.62`）：上游行为可能随版本收紧，
     * 带上没有代价；哪天被拒，改 [CLIENT_VERSION] 一处即可。
     */
    private fun chatHeaders(token: String): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("Accept", "text/event-stream")
        put("Authorization", "Bearer ${ensureTokenPrefix(token)}")
        putAll(DISGUISE_HEADERS)
    }

    /** 目录接口的头：不需要产品面标识，但带上更贴近官方客户端。 */
    private fun modelCatalogHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "User-Agent" to DISGUISE_HEADERS.getValue("User-Agent"),
    )

    // ------------------------------------------------------------------ 登录

    override fun startDeviceAuth(region: String): DeviceAuthTicket = login.start()

    override fun pollDeviceAuth(state: String, region: String): DeviceAuthPoll = login.poll(state)

    // ------------------------------------------------------------------ 续期

    /**
     * 凭证续期：`POST /api/v1/auth/refresh`。
     *
     * ## 三处别按直觉改的口径
     *
     * 1. **`grantType` 是 camelCase**，不是 OAuth 标准的 `grant_type`
     *    （源实现 `@cline/core` 的 `gB()` 发的就是这个键名）。
     * 2. 返回的 `accessToken` **不带 `workos:` 前缀**，写入前统一补。
     * 3. `expiresAt` 是 **ISO 8601 字符串**，不是毫秒数（与桌面端文件同名不同型）。
     *
     * ## 单飞
     *
     * refresh_token 在服务端是**一次性轮换**语义的。并发请求同时发现 token 临期时，
     * 若各自打一次续期，后到的那次可能拿着已作废的 token → 401 → 用户被踢下线。
     * 所以整段刷新在 [refreshLock] 内，且锁内**复查**别人的成果。
     */
    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val credential = parseClineCredential(account.secret) ?: return null
        if (!credential.expiresWithin(skewSeconds, nowMillis())) return null
        if (credential.refreshToken.isEmpty()) return null
        synchronized(refreshLock) {
            // 锁内复查：并发请求跨入同一临期窗口时，复用别的线程刚刷出的凭证。
            // 必须返回新凭证 —— 返回 null 会让调用方拿旧 token 去请求（直接 401）。
            val cached = lastRefreshed[account.uid]
            if (cached != null && !cached.expiresWithin(skewSeconds, nowMillis())) {
                return toAccount(cached, account)
            }
            // 失败冷却：token 被吊销后不再按请求频率轰炸端点。
            val failedAt = lastRefreshFailedAt[account.uid] ?: 0L
            if (failedAt > 0 && nowMillis() - failedAt < REFRESH_COOLDOWN_MS) return null

            val body = JsonObject().apply {
                addProperty("refreshToken", credential.refreshToken)
                // camelCase：OAuth 标准是 grant_type，这里是 grantType
                addProperty("grantType", "refresh_token")
            }.toString()
            val (status, text) = try {
                post(
                    "$apiBaseUrl/auth/refresh",
                    body,
                    mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                ).let { conn ->
                    try {
                        conn.responseCode to HttpSupport.readBody(conn)
                    } finally {
                        conn.disconnect()
                    }
                }
            } catch (e: Exception) {
                hooks.onLog("Cline 续期异常：${NetworkErrorText.logText(e, HOST)}")
                markRefreshFailed(account.uid)
                return null
            }
            if (status !in 200..299) {
                hooks.onLog("Cline 续期失败（HTTP $status）：${bodySnippet(text, 160)}")
                markRefreshFailed(account.uid)
                return null
            }
            val payload = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            val data = payload?.objOrNull("data") ?: payload
            val newAccess = data?.stringOrNull("accessToken").orEmpty().trim()
            if (newAccess.isEmpty()) {
                hooks.onLog("Cline 续期响应缺少 accessToken")
                markRefreshFailed(account.uid)
                return null
            }
            val refreshed = ClineCredential(
                accessToken = ensureTokenPrefix(newAccess),
                // 上游可能不给新 refreshToken：沿用旧的，否则账号失去续期能力
                refreshToken = data?.stringOrNull("refreshToken").orEmpty().trim()
                    .ifEmpty { credential.refreshToken },
                // ISO 字符串 → 毫秒（两种形态都要认）
                expiresAt = data?.let { parseExpiresAt(it) } ?: 0L,
                accountId = credential.accountId,
                email = credential.email,
                name = credential.name,
            )
            lastRefreshed = lastRefreshed + (account.uid to refreshed)
            lastRefreshFailedAt = lastRefreshFailedAt - account.uid
            return toAccount(refreshed, account)
        }
    }

    private fun markRefreshFailed(uid: String) {
        lastRefreshFailedAt = lastRefreshFailedAt + (uid to nowMillis())
    }

    private fun toAccount(credential: ClineCredential, existing: ProviderAccount): ProviderAccount =
        existing.copy(secret = credential.toSecret())

    // ------------------------------------------------------------------ 额度

    /**
     * 余额：`GET /users/{userId}/balance`。
     *
     * ## `balance` 是**微 credit**，要 ÷ 1e6
     *
     * 实测一个刚用过的账号返回 `balance: -17704`，而 Cline 官方界面
     * （Cline Hub 余额卡片）对同一账号显示 `-0.0177`。差值恰好 1e6，
     * 官方前端的格式化函数就是 `format(e / 1e6)`。
     *
     * **负值表示已欠费**（免费额度用超了）—— 这个符号是用户最需要看到的事实，
     * 如实保留（换算不改变符号）。
     *
     * ## 套餐 404 是正常状态
     *
     * 免费用户没有订阅，`/users/me/plan` 返回 404（`no plan history found for user`）。
     * 不能当失败 —— 只记空，不影响余额结果。
     */
    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val credential = parseClineCredential(account.secret) ?: return null
        // 余额路径要的是 `usr-...` 形态的账号 id。凭证里没有、或 uid 不是这个形态时，
        // 打一次 `/users/me` 问上游 —— 直接拿 uid 去拼会变成 `/users/<哈希>/balance`，
        // 上游回 `Invalid request format`，看起来像协议写错了。
        val userId = resolveUserId(credential, account.uid)
        if (userId.isEmpty()) {
            return CreditInfo(0, known = false, detail = "查询余额失败：拿不到账号 id")
        }
        val (status, text) = try {
            get("$apiBaseUrl/users/$userId/balance", bearerHeaders(credential.accessToken))
        } catch (e: Exception) {
            return CreditInfo(0, known = false, detail = NetworkErrorText.describe(e, HOST))
        }
        if (status !in 200..299) {
            val reason = extractMessage(text).ifEmpty { "HTTP $status" }
            return CreditInfo(0, known = false, detail = "查询余额失败：$reason")
        }
        val payload = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return CreditInfo(0, known = false, detail = "余额响应不是合法 JSON")
        val data = payload.objOrNull("data") ?: payload
        val micro = data.longOrNull("balance")
            ?: return CreditInfo(0, known = false, detail = "上游未返回 balance 字段")
        // 微 credit → credit：÷ 1e6。负值（欠费）如实保留。
        val credits = micro / MICRO_CREDITS_PER_CREDIT
        val plan = planLabelOf(credential.accessToken)
        return CreditInfo(
            balance = credits,
            known = true,
            detail = buildString {
                append("余额 $credits credit")
                if (micro < 0) append("（已欠费）")
                if (plan.isNotEmpty()) append("；套餐：$plan")
            },
        )
    }

    /**
     * 解析余额路径要的账号 id（`usr-...`）。
     *
     * 三级兜底，与上游适配器同口径：
     * 1. 凭证里存着就用它；
     * 2. 池里的 uid 恰好是 `usr-` 形态（早期登录写进去的）也认；
     * 3. 都没有时打一次 `GET /users/me`，用它的 `data.id`。
     *
     * 返回空串表示**确实拿不到** —— 调用方据此报「拿不到账号 id」，
     * 而不是拼一个必然 400 的路径去撞上游。
     */
    private fun resolveUserId(credential: ClineCredential, poolUid: String): String {
        credential.accountId.takeIf { it.startsWith(USER_ID_PREFIX) }?.let { return it }
        poolUid.takeIf { it.startsWith(USER_ID_PREFIX) }?.let { return it }
        return fetchUserIdFromMe(credential.accessToken)
    }

    /** `GET /users/me` → `data.id`（取不到返回空串）。 */
    private fun fetchUserIdFromMe(token: String): String {
        val (status, text) = try {
            get("$apiBaseUrl/users/me", bearerHeaders(token))
        } catch (e: Exception) {
            hooks.onLog("Cline 查询账号 id 失败：${NetworkErrorText.logText(e, HOST)}")
            return ""
        }
        if (status !in 200..299) return ""
        val payload = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return ""
        val data = payload.objOrNull("data") ?: payload
        return data.firstNonEmpty("id", "userId", "accountId")
    }

    /**
     * 当前套餐名（取不到返回空串）。
     *
     * 404 是**免费用户的正常状态**，不是错误 —— 所以这里静默返回空串，
     * 不往 detail 里塞一句「查询失败」吓人。
     */
    private fun planLabelOf(token: String): String {
        val (status, text) = try {
            get("$apiBaseUrl/users/me/plan", bearerHeaders(token))
        } catch (e: Exception) {
            return ""
        }
        if (status !in 200..299) return ""
        val payload = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return ""
        val data = payload.objOrNull("data") ?: return ""
        return data.firstNonEmpty("name", "planName", "plan_name", "tier", "title")
    }

    // ------------------------------------------------------------------ 错误分类

    /**
     * 上游错误分类（实测三种错误体）。
     *
     * `403` 分两支是本类最关键的一处：
     * - `ENTITLEMENT_ERROR` / `API_REQUEST_ERROR_CODE` → **`MODEL_LIMIT`**：
     *   是「这个账号对这个模型没权限」，换号可能有用（另一个号有订阅），
     *   但**绝不能**硬禁用账号；
     * - 其它 403 → `SESSION_DEAD`：确实可能是令牌问题。
     *
     * 把权限问题归 `SESSION_DEAD` 会让 `pool.disable()` 停用账号，
     * 用户必须重新登录才能解封 —— 而权限问题重新登录解决不了。
     *
     * ## 中文语义（402 / 403）
     *
     * 上游的错误体是英文的（`insufficient_credits` / `ENTITLEMENT_ERROR` …），
     * 直接透给用户等于让人自己猜。这里在原文前面补一句中文结论：
     * **「中文结论（上游原文）」**。原文必须保留 —— 排查时它是唯一能搜的线索。
     */
    override fun classify(status: Int, body: String): UpstreamError {
        val raw = extractMessage(body).ifEmpty { "上游 HTTP $status${bodySnippet(body)}" }
        val kind = when {
            status == 401 -> ErrorKind.SESSION_DEAD
            status == 403 && looksLikeEntitlement(body) -> ErrorKind.MODEL_LIMIT
            status == 403 -> ErrorKind.SESSION_DEAD
            status == 402 -> ErrorKind.QUOTA
            status == 404 && looksLikeMissingModel(body) -> ErrorKind.NOT_FOUND
            status == 404 -> ErrorKind.CLIENT
            // 429 的模型级限流（免费额度撞限）由调用方按文案改判 MODEL_LIMIT
            status == 429 -> ErrorKind.SOFT_RATE
            status in 500..599 -> ErrorKind.SERVER
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, explain(status, kind, body, raw))
    }

    /**
     * 给上游错误补中文语义。已经是中文的（或没识别出模式的）原样返回。
     *
     * 三类实测错误体各有对应说法：
     * - 402 `insufficient_credits` → 余额不足，附 `current_balance` 与充值地址；
     * - 403 `ENTITLEMENT_ERROR` → 没订阅 / 没这个模型的权限；
     * - 401 → 登录凭证失效，要重新登录。
     */
    private fun explain(status: Int, kind: ErrorKind, body: String, raw: String): String {
        // 上游文案本身已是中文（自定义中转站常见），不再套壳
        if (raw.any { it.code in 0x4E00..0x9FFF }) return raw
        val hint = when {
            status == 402 || looksLikeInsufficientCredits(body) -> buildString {
                append("Cline 账户余额不足（已欠费或额度用完）")
                balanceOf(body)?.let { append("，当前余额 ").append(it) }
                creditsUrlOf(body)?.let { append("；充值地址 ").append(it) }
            }
            status == 403 && kind == ErrorKind.MODEL_LIMIT ->
                "这个账号没有该模型的权限（通常是没订阅 ClinePass，或该模型不在套餐内）"
            status == 403 -> "上游以 403 拒绝：账号可能没有这项权限，也可能是凭证被拒"
            status == 401 -> "Cline 登录凭证已失效，需要重新登录"
            status == 429 -> "上游限流（免费额度撞到频率上限），稍后自动恢复"
            status in 500..599 -> "Cline 上游服务异常"
            else -> return raw
        }
        return "$hint｜上游原文：$raw"
    }

    /** 403 是否属于「账号对该模型无权限」（而不是凭证失效）。 */
    private fun looksLikeEntitlement(body: String): Boolean {
        val lower = body.lowercase()
        return ENTITLEMENT_MARKERS.any { lower.contains(it) }
    }

    /** 404 是否属于「上游没有这个模型」。 */
    private fun looksLikeMissingModel(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("model not found") || lower.contains("model_not_found") ||
            lower.contains("no such model") || lower.contains("unknown model")
    }

    /** 402 是否属于「余额不足」（实测 code = `insufficient_credits`）。 */
    private fun looksLikeInsufficientCredits(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("insufficient_credits") || lower.contains("insufficient credits") ||
            lower.contains("insufficient balance") || lower.contains("insufficient_quota")
    }

    /**
     * 从 402 错误体里取当前余额，格式化成人话。
     *
     * 实测上游带的是 `current_balance: -0.002877` —— **已经是 credit，不是微 credit**，
     * 与余额接口（微 credit）口径不同，所以这里**绝不能再除 1e6**。
     */
    private fun balanceOf(body: String): String? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val err = obj.objOrNull("error") ?: obj
        val element = err.get("current_balance")?.takeIf { it.isJsonPrimitive } ?: return null
        val value = runCatching { element.asDouble }.getOrNull() ?: return null
        if (!value.isFinite()) return null
        // 最多 6 位小数，去掉尾随 0（-0.002877 → "-0.002877"，1.0 → "1"）
        val text = String.format(java.util.Locale.US, "%.6f", value).trimEnd('0').trimEnd('.')
        return text.ifEmpty { "0" } + " credit"
    }

    /** 从 402 错误体里取充值地址（`buy_credits_url`）。 */
    private fun creditsUrlOf(body: String): String? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val err = obj.objOrNull("error") ?: obj
        return err.stringOrNull("buy_credits_url")?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------ HTTP

    private fun post(url: String, body: String, headers: Map<String, String>): HttpURLConnection =
        HttpSupport.open(
            url = url,
            method = "POST",
            headers = headers,
            body = body,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )

    private fun get(url: String, headers: Map<String, String>, allowCompression: Boolean = false): Pair<Int, String> {
        val conn = HttpSupport.open(
            url = url,
            method = "GET",
            headers = headers,
            body = null,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = CONNECT_TIMEOUT_MS,
            allowCompression = allowCompression,
        )
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun bearerHeaders(token: String): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Authorization" to "Bearer ${ensureTokenPrefix(token)}",
    )

    /**
     * 从上游错误体里抠出人话。
     *
     * Cline 的三种错误体都出现过：`{"error":{"message":...}}`、
     * `{"error":"..."}`、以及裸 `{"message":...}`。三种都认。
     */
    private fun extractMessage(body: String): String {
        if (body.isBlank()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        obj.objOrNull("error")?.stringOrNull("message")?.takeIf { it.isNotBlank() }?.let { return it }
        obj.get("error")?.takeIf { it.isJsonPrimitive }?.let { element ->
            val text = runCatching { element.asString }.getOrNull().orEmpty()
            if (text.isNotBlank()) return text
        }
        obj.stringOrNull("message")?.takeIf { it.isNotBlank() }?.let { return it }
        obj.objOrNull("data")?.stringOrNull("message")?.takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    private fun JsonObject.firstNonEmpty(vararg keys: String): String {
        for (key in keys) {
            val text = stringOrNull(key).orEmpty().trim()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun JsonObject.firstPositiveLong(vararg keys: String): Long {
        for (key in keys) {
            val value = longOrNull(key) ?: continue
            if (value > 0) return value
        }
        return 0L
    }

    // ------------------------------------------------------------------ 状态

    private val refreshLock = Any()

    /** 各账号最近一次成功刷新的凭证（按 uid），用于并发单飞。 */
    @Volatile
    private var lastRefreshed: Map<String, ClineCredential> = emptyMap()

    /** 各账号最近一次刷新失败的毫秒时间戳（按 uid），用于失败冷却。 */
    @Volatile
    private var lastRefreshFailedAt: Map<String, Long> = emptyMap()

    companion object {
        const val ID = "cline"

        const val HOST = "cline.bot"

        /** 上游 API 基址。**路径里只有一个 `v1`**（上游把 baseURL 当根拼）。 */
        const val API_BASE_URL = "https://api.cline.bot/api/v1"

        /** 产品面标识头的取值。缺了它免费池一律 403；换成 `cline-cli` 会 500。 */
        const val CLIENT_TYPE = "cline-sdk"

        /** 免费池的通道前缀（计费通道选择器，转发时必须原样带上）。 */
        const val FREE_PREFIX = "cline-free/"

        /** 订阅池的通道前缀。 */
        const val PASS_PREFIX = "cline-pass/"

        /** 云端池前缀：已知但不收录（实测 403）。 */
        const val CLOUD_PREFIX = "cline-cloud/"

        /** 池标识（模型 extra 里的值，供界面分组显示）。 */
        const val POOL_FREE = "free"
        const val POOL_PASS = "pass"
        const val POOL_CLOUD = "cloud"
        const val POOL_NONE = ""

        /** 模型 extra 里存池归属的键。 */
        const val EXTRA_POOL = "pool"

        /** 客户端没给 max_tokens 时的默认值（上游实测口径）。 */
        const val DEFAULT_MAX_TOKENS = 128_000L

        /** 客户端没给思考档位时的默认值。 */
        const val DEFAULT_REASONING_EFFORT = "high"

        /** 上游账号 id 的前缀（余额路径 `/users/{id}/balance` 要的就是它）。 */
        const val USER_ID_PREFIX = "usr-"

        /** 微 credit → credit 的换算刻度。 */
        const val MICRO_CREDITS_PER_CREDIT = 1_000_000L

        /** 续期失败后的冷却时长：避免 token 被吊销后按请求频率轰炸端点。 */
        internal const val REFRESH_COOLDOWN_MS = 60_000L

        /** 伪装头。`X-CLIENT-TYPE` 是免费池的硬门槛（见类注释）。 */
        private val DISGUISE_HEADERS = mapOf(
            "User-Agent" to "Cline/3.0.62",
            "HTTP-Referer" to "https://cline.bot",
            "X-Title" to "Cline",
            "X-IS-MULTIROOT" to "false",
            "X-CLIENT-TYPE" to CLIENT_TYPE,
            "X-CLIENT-VERSION" to "3.0.62",
            "X-PLATFORM" to "terminal",
            "X-PLATFORM-VERSION" to "3.0.62",
            "X-CORE-VERSION" to "0.0.70",
        )

        /**
         * 白名单透传的键。
         *
         * 上游是 OpenAI 兼容网关，但只认有限字段；白名单之外的键传过去没有意义，
         * 还可能触发参数校验。这份清单与上游适配器实测口径一致。
         */
        private val PASS_THROUGH_KEYS = listOf(
            "tools", "tool_choice", "parallel_tool_calls",
            "functions", "function_call",
            "temperature", "top_p", "top_k", "stop",
            "presence_penalty", "frequency_penalty",
            "response_format", "user", "n", "logit_bias", "seed",
            "logprobs", "top_logprobs", "stream_options", "metadata",
        )

        /** 403 里表示「账号对该模型无权限」的标记（不区分大小写）。 */
        private val ENTITLEMENT_MARKERS = listOf(
            "entitlement",
            "not available via",
            "product surfaces",
            "no access to",
            "insufficient permission",
        )

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000
    }
}
