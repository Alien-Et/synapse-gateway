package io.synapse.core.provider.custom

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.gateway.CustomProviderConfig
import io.synapse.core.provider.AggregatedChatCall
import io.synapse.core.provider.AuthKind
import io.synapse.core.provider.ChatCall
import io.synapse.core.provider.CreditInfo
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.FailedChatCall
import io.synapse.core.provider.OpenAiSseAggregator
import io.synapse.core.provider.Provider
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderCapability
import io.synapse.core.provider.ProviderModel
import io.synapse.core.provider.ProviderModelCatalogView
import io.synapse.core.provider.StreamFailureChatCall
import io.synapse.core.provider.StreamingChatCall
import io.synapse.core.provider.UpstreamError
import io.synapse.core.util.HttpSupport
import io.synapse.core.util.arrayOrNull
import io.synapse.core.util.bodySnippet
import io.synapse.core.util.objOrNull
import io.synapse.core.util.NetworkErrorText
import java.net.HttpURLConnection

/**
 * 用户手动导入的 OpenAI 兼容供应商。
 *
 * 无登录流程，凭证就是 API Key；配多个 key 时每个 key 是账号池里的一个「账号」，
 * 由账号池做轮询。模型列表以用户手填为准，也可用 [fetchModels] 从 `/v1/models` 拉取。
 */
class CustomProvider(
    private val config: CustomProviderConfig,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider {

    override val id: String = config.providerId
    override val displayName: String = config.name
    override val authKind: AuthKind = AuthKind.NONE
    override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.CREDIT_REFRESH)

    /**
     * 自定义供应商**声明支持全部透传端点**。
     *
     * 这里不做「按配置声明子集」：用户填的就是一个 OpenAI 兼容站，
     * 它到底实现了哪些端点只有上游知道。声明全集 + 上游 404 如实回传，
     * 比让用户先在配置里勾一遍「我这家支持生图」要省事得多——
     * 勾错了反而更难查（明明支持却被网关自己挡住）。
     */
    override val passthroughPaths: Set<String> =
        io.synapse.core.provider.ApiEndpoints.PASSTHROUGH.flatMap { it.allPaths }.toSet() +
            protocol().extraPaths

    /** 本供应商采用的协议（决定端点路径与鉴权头）。 */
    private fun protocol(): io.synapse.core.gateway.ProviderProtocol {
        val base = io.synapse.core.gateway.ProviderProtocols.of(config.protocol)
        // 用户在本供应商上显式改过「总是流式」时以他的为准，否则跟随协议默认
        val forced = config.forceStreaming ?: return base
        return if (base.forceStreaming == forced) base else base.copy(forceStreaming = forced)
    }

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView =
        ProviderModelCatalogView(
            models = config.models.map { ProviderModel(id = it, name = it) },
            fromFallback = false,
            error = "",
        )

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        if (model.isEmpty() || model == "auto") return config.models.firstOrNull().orEmpty()
        return model
    }

    override fun hosts(): List<String> = runCatching {
        java.net.URI(config.baseUrl.trim()).host
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?.let { listOf(it) }
            .orEmpty()
    }.getOrDefault(emptyList())

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        // 地址为空 = 这条配置是导入时补建的占位（旧导出文件没存地址）。
        // 明确报出来，别让它拼成一个 "/chat/completions" 的相对地址去撞一个看不懂的错误。
        if (config.baseUrl.isBlank()) {
            return FailedChatCall(
                0,
                "供应商「${config.name.ifEmpty { config.key }}」还没填接口地址，请到供应商详情页补全",
            )
        }
        val key = parseKey(account) ?: return FailedChatCall(401, "凭证无法解析")
        val proto = protocol()
        val clientWantsStream = isStreaming(openAiBody)
        val model = requestedModel(openAiBody)

        // Responses 协议：请求体要整形成 `input`，响应是它自己的一套 SSE 事件流，
        // 必须翻译回客户端认识的 chat.completion.chunk。
        if (proto.requestShape == io.synapse.core.gateway.RequestShape.RESPONSES) {
            return openResponsesChat(config, key, openAiBody, model, clientWantsStream)
        }

        // 上游只吃流式（如 Codex 后端）时，客户端要非流式也得先按流式请求，
        // 拿到事件流后在本地聚合成普通 JSON —— 否则上游直接 400。
        val upstreamStream = clientWantsStream || proto.forceStreaming
        val body = if (upstreamStream && !clientWantsStream) {
            withStreamFlag(openAiBody, true)
        } else {
            openAiBody
        }

        val conn = try {
            openConnection(chatUrl(config, model), key, body, "POST", config.headers, proto)
        } catch (e: Exception) {
            return FailedChatCall(0, NetworkErrorText.describe(e))
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, NetworkErrorText.describe(e))
        }
        if (status !in 200..299) {
            val errBody = runCatching { HttpSupport.readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, errBody)
        }

        val contentType = conn.contentType.orEmpty()
        val stream = conn.inputStream
        if (contentType.contains("event-stream", ignoreCase = true)) {
            if (clientWantsStream) {
                return StreamingChatCall(status, stream) { conn.disconnect() }
            }
            val aggregated = try {
                OpenAiSseAggregator.aggregate(stream, model)
            } finally {
                conn.disconnect()
            }
            if (OpenAiSseAggregator.isEmptyCompletion(aggregated)) {
                return emptyUpstreamFailure(status, contentType, aggregated)
            }
            return AggregatedChatCall(status, aggregated)
        }

        val text = runCatching { stream.use { HttpSupport.readLimited(it) } }.getOrDefault("")
        conn.disconnect()
        // 200 但空 body（代理吞响应、上游静默拒绝）：当成功会让客户端「输出完成却什么都没有」
        if (text.isBlank()) return emptyUpstreamFailure(status, contentType, text)
        // 非流式响应必须是 OpenAI completion；HTML 登录页之类不能当回复透传
        if (!isChatCompletion(text)) {
            return StreamFailureChatCall(
                status,
                UpstreamError(
                    ErrorKind.CLIENT,
                    "上游响应不符合 OpenAI 协议（HTTP $status，Content-Type: ${contentType.ifEmpty { "—" }}）：${text.trim().take(200)}",
                ),
            )
        }
        // 客户端要流式但上游只回了普通 JSON：包成 SSE，严格客户端才能解析出内容
        if (clientWantsStream) {
            return StreamingChatCall(status, OpenAiSseAggregator.completionAsSse(text).byteInputStream(Charsets.UTF_8))
        }
        return AggregatedChatCall(status, text)
    }

    /**
     * Responses 协议的对话。
     *
     * 上游只发 SSE 事件（`response.output_text.delta` 等），这里用
     * [io.synapse.core.provider.chatgpt.OpenAiResponses.SseTranslator] 逐行翻译成
     * `chat.completion.chunk`：客户端无论是流式还是非流式，看到的都是 OpenAI Chat 形状。
     *
     * 与非 Responses 协议一样，`stream` 由**客户端**决定；上游那侧始终是流式。
     */
    private fun openResponsesChat(
        config: io.synapse.core.gateway.CustomProviderConfig,
        key: String,
        openAiBody: String,
        model: String,
        clientWantsStream: Boolean,
    ): ChatCall {
        val proto = protocol()
        val payload = try {
            io.synapse.core.provider.chatgpt.OpenAiResponses.toResponsesRequest(openAiBody, model)
        } catch (e: Exception) {
            return FailedChatCall(400, """{"error":{"message":"请求体转换失败：${e.message}"}}""")
        }

        val conn = try {
            openConnection(chatUrl(config, model), key, payload, "POST", config.headers, proto)
        } catch (e: Exception) {
            return FailedChatCall(0, NetworkErrorText.describe(e))
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, NetworkErrorText.describe(e))
        }
        if (status !in 200..299) {
            val errBody = runCatching { HttpSupport.readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, errBody)
        }

        val translator = io.synapse.core.provider.chatgpt.OpenAiResponses.SseTranslator(
            io.synapse.core.util.newCompletionId(),
            model,
        )
        val translated = io.synapse.core.provider.LineTransformStream(
            source = conn.inputStream,
            transform = { line -> translator.translate(line) },
            onFinish = { translator.onFinish() },
        )
        return if (clientWantsStream) {
            StreamingChatCall(status, translated) { conn.disconnect() }
        } else {
            // 上游只会流式：非流式请求在本地聚合
            try {
                AggregatedChatCall(status, OpenAiSseAggregator.aggregate(translated, model))
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 把请求体里的 `stream` 改成指定值（其余字段原样保留）。 */
    private fun withStreamFlag(body: String, stream: Boolean): String {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return body
        obj.addProperty("stream", stream)
        return obj.toString()
    }

    /** 上游 200 但没给出任何内容：保持可检测的失败，不能报成「成功的空回复」。 */
    private fun emptyUpstreamFailure(status: Int, contentType: String, raw: String): ChatCall = StreamFailureChatCall(
        status,
        UpstreamError(
            ErrorKind.CLIENT,
            "上游返回了空响应（HTTP $status，Content-Type: ${contentType.ifEmpty { "—" }}" +
                if (raw.isBlank()) "）" else "，片段：${raw.trim().take(120)}）",
        ),
    )

    /** 是否是合法的 OpenAI completion（非流式响应）。 */
    private fun isChatCompletion(body: String): Boolean {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return false
        return obj.get("choices")?.isJsonArray == true
    }

    /**
     * 把非对话端点原样转发给上游。
     *
     * 走这条路的有:生图、图生图、语音转写/合成、向量、重排、审核、视频、模型列表。
     * 它们的共同点是**网关不需要理解响应内容**——原样透传即可,客户端自己会解析。
     *
     * 几个刻意的选择:
     * - 不做容灾重试:生图/语音都是**计费**动作,失败换号重试会出现「同一张图扣两次钱」;
     * - 不改写响应体:图片是 base64、语音是二进制,任何「解析再序列化」都可能损坏内容;
     * - 路径拼接尊重 baseUrl 的已有后缀:用户填 `https://x.com/v1` 就拼 `/images/generations`,
     *   填 `https://x.com` 则补全 `/v1/images/generations`——两种写法都常见。
     */
    override fun passthrough(
        account: ProviderAccount,
        method: String,
        path: String,
        body: String?,
    ): ChatCall? {
        if (config.baseUrl.isBlank()) {
            return FailedChatCall(0, "供应商「${config.name.ifEmpty { config.key }}」还没填接口地址，请到供应商详情页补全")
        }
        val key = parseKey(account) ?: return FailedChatCall(401, "凭证无法解析")
        val url = passthroughUrl(config.baseUrl, path)

        val conn = try {
            openConnection(url, key, body, method, config.headers, protocol())
        } catch (e: Exception) {
            return FailedChatCall(0, NetworkErrorText.describe(e))
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, NetworkErrorText.describe(e))
        }
        if (status !in 200..299) {
            val errBody = runCatching { HttpSupport.readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, errBody)
        }

        // 二进制/流式响应（语音合成返回 audio/mpeg）：原样交给网关写出去
        val contentType = conn.contentType.orEmpty()
        val isBinary = contentType.isNotEmpty() &&
            !contentType.contains("json", ignoreCase = true) &&
            !contentType.contains("text/", ignoreCase = true) &&
            !contentType.contains("event-stream", ignoreCase = true)
        if (isBinary) {
            return StreamingChatCall(status, conn.inputStream) { conn.disconnect() }
        }

        val text = try {
            conn.inputStream.use { HttpSupport.readLimited(it) }
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, NetworkErrorText.describe(e))
        } finally {
            runCatching { conn.disconnect() }
        }
        // 空 body 但 2xx：如实返回空（不伪造内容）；调用方会按「上游返回空响应」处理
        return AggregatedChatCall(status, text)
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = extractMessage(body).ifEmpty { "上游 HTTP $status" + bodySnippet(body) }
        // 「endpoint / url / route 不存在」是地址拼错，不是模型 id 错。
        // 模型在别的客户端能用、这里 404，几乎都是这一类；再标成 NOT_FOUND 会被网关说成「上游没有这个模型」。
        if (status == 404 && looksLikeMissingEndpoint(message)) {
            return UpstreamError(
                ErrorKind.CLIENT,
                "上游没有这个接口，多半是 Base URL 和协议对不上：$message",
            )
        }
        val kind = when {
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 402 -> ErrorKind.QUOTA
            status == 404 -> ErrorKind.NOT_FOUND
            status == 429 -> ErrorKind.SOFT_RATE
            status in 500..599 -> ErrorKind.SERVER
            status == 400 || status == 422 -> ErrorKind.CLIENT
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, message)
    }

    /**
     * 余额查询。
     *
     * OpenAI 协议本身**没有余额接口**（官方只有用量，没有余额），所以这里不能靠协议，
     * 只能靠用户在供应商配置里填 [BalanceQueryConfig]：一条 GET 地址 + 一个取值路径。
     * 没配就返回 null（界面显示「不支持」，不编数字）。
     *
     * 认证沿用账号的 API Key；用户在「自定义请求头」里写了 `Authorization` 时以他写的为准
     * ——有些中转站要求 `x-api-key` 或裸 Key，硬塞 Bearer 反而是错的。
     */
    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val raw = config.balanceQuery ?: return null
        // 预设先补空：用户选了预设但没逐项填时，这里把路径/字段/方法补齐；
        // 用户手改过的字段优先（BalancePresets.apply 只填空）。
        val query = io.synapse.core.gateway.BalancePresets.apply(raw)
        val key = parseKey(account) ?: return CreditInfo(0, known = false, detail = "凭证无法解析")
        val url = balanceUrl(query)
        if (url.isEmpty()) {
            return CreditInfo(0, known = false, detail = "余额地址为空：请在供应商设置里填完整地址或相对路径")
        }
        return try {
            // 部分大厂余额接口是 POST 且要带 JSON body（比如按时间窗查用量）
            val method = query.method.uppercase().takeIf { it == "POST" || it == "PUT" } ?: "GET"
            val body = if (method == "GET") null else query.body.ifEmpty { "{}" }
            val conn = openConnection(url, key, body, method, config.headers, protocol())
            val status = try {
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                return CreditInfo(0, known = false, detail = NetworkErrorText.describe(e))
            }
            if (status !in 200..299) {
                val body = runCatching { HttpSupport.readLimited(conn.errorStream) }.getOrDefault("")
                conn.disconnect()
                return CreditInfo(
                    0,
                    known = false,
                    detail = "余额接口 HTTP $status" +
                        if (body.isBlank()) "" else "：${body.trim().take(160)}",
                )
            }
            val text = runCatching { conn.inputStream.use { HttpSupport.readLimited(it) } }.getOrDefault("")
            conn.disconnect()
            parseBalance(text, query)
        } catch (e: Exception) {
            CreditInfo(0, known = false, detail = NetworkErrorText.describe(e))
        }
    }

    /**
     * 从响应里按路径取出余额。
     *
     * 取不到时**必须**报 known=false：余额真的是 0 和「字段没找到」在界面上是两件事，
     * 前者说明要充值了，后者说明配置填错了——把后者显示成 0 会让人白充钱。
     */
    private fun parseBalance(text: String, query: io.synapse.core.gateway.BalanceQueryConfig): CreditInfo {
        val root = runCatching { JsonParser.parseString(text) }.getOrNull()
            ?: return CreditInfo(0, known = false, detail = "余额响应不是 JSON：${text.trim().take(120)}")
        // 允许用逗号列多个候选字段：各家字段名不统一，写全一点能少改几次配置
        val candidates = query.field.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val paths = candidates.ifEmpty { listOf("") }
        val raw = paths.firstNotNullOfOrNull { pickByPath(root, it) }
            ?: return CreditInfo(
                0,
                known = false,
                detail = "响应里找不到「${query.field}」这个字段（片段：${text.trim().take(160)}）",
            )
        // 上游常把余额包成字符串（`"balance": "12.34"`），所以先按长整型试、再按浮点试。
        // 直接 asLong 在非整数上会抛异常，用 runCatching 兜住。
        val num = runCatching { raw.asString.trim().toLong() }.getOrNull()
            ?: runCatching { raw.asString.trim().toDouble().toLong() }.getOrNull()
            ?: runCatching { raw.asLong }.getOrNull()
            ?: runCatching { raw.asDouble.toLong() }.getOrNull()
            ?: return CreditInfo(0, known = false, detail = "「${query.field}」不是数字：${raw.toString().take(60)}")

        val scale = if (query.scale > 0) query.scale else 1.0
        val value = if (query.divide) (num / scale).toLong() else (num * scale).toLong()
        val unit = query.unit.trim()
        val detail = buildString {
            append("余额 $value")
            if (unit.isNotEmpty()) append(" $unit")
            if (scale != 1.0) {
                append("（上游 $num ")
                append(if (query.divide) "÷" else "×")
                append(if (scale == scale.toLong().toDouble()) scale.toLong().toString() else scale.toString())
                append("）")
            }
        }
        return CreditInfo(value, known = true, detail = detail)
    }

    /**
     * 按点分路径取值，支持数组下标：`data.accounts.0.remain`。
     *
     * 全路径为空时直接返回根节点（响应本身就是个数字的接口）。
     */
    private fun pickByPath(root: com.google.gson.JsonElement, path: String): com.google.gson.JsonElement? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return root
        var current: com.google.gson.JsonElement = root
        for (segment in trimmed.split('.')) {
            if (segment.isEmpty()) return null
            current = when {
                current.isJsonObject -> current.asJsonObject.get(segment) ?: return null
                current.isJsonArray -> {
                    val index = segment.toIntOrNull() ?: return null
                    val array = current.asJsonArray
                    if (index < 0 || index >= array.size()) return null
                    array.get(index)
                }
                else -> return null
            }
        }
        return current.takeIf { it.isJsonPrimitive }
    }

    /**
     * 余额地址：优先级 完整 URL > baseUrl + 相对路径 > baseUrl 本身。
     *
     * 最后一档不是兜底而是常见用法：不少中转站把余额就挂在 baseUrl 上
     * （`https://x.com/v1` 直接回 `{"balance":12}`），这时只要填取值字段就够了。
     */
    private fun balanceUrl(query: io.synapse.core.gateway.BalanceQueryConfig): String {
        val explicit = query.url.trim()
        if (explicit.isNotEmpty()) return explicit
        val base = config.baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return ""
        val path = query.path.trim()
        if (path.isEmpty()) return base
        return if (path.startsWith("/")) base + path else "$base/$path"
    }

    /**
     * 保存前的连通性探针：返回 null 表示「这家能用」，非 null 是可以直接弹给用户的原因。
     *
     * 判据刻意宽松，因为 `/v1/models` **不是** OpenAI 协议里必被实现的接口：
     *
     * | 上游表现 | 判定 | 理由 |
     * |---|---|---|
     * | 200 且带模型 | 通过 | 最理想 |
     * | 200 但列表为空 | 通过 | 有的站要显式开权限才有模型，对话仍可用 |
     * | 401 / 403 | **失败** | Key 或请求头真的不对，用户必须知道 |
     * | 404 / 405 / 5xx / 网络异常 | 通过 | 接口没实现或临时故障，不代表对话不通 |
     *
     * 之所以不把 404 判成失败：很多中转站只实现 `chat/completions`，没有 `models`，
     * 它们完全可用——按 models 判死会把正常的供应商拦在门外。
     */
    fun probeModels(apiKey: String): ProbeOutcome {
        if (config.baseUrl.isBlank()) return ProbeOutcome("接口地址不能为空")
        val result = runCatching { rawModelsStatus(apiKey) }.getOrElse { e ->
            // 网络层异常（DNS/连接拒绝）：这属于「地址可能写错了」，值得提示
            return ProbeOutcome("连不上上游：${NetworkErrorText.describe(e)}")
        }
        val (status, body) = result
        return when {
            status in 200..299 -> ProbeOutcome(null)
            status == 401 || status == 403 -> {
                val hint = extractMessage(body).ifEmpty { "HTTP $status" }
                ProbeOutcome("鉴权失败（HTTP $status）：$hint。请检查 API Key 与自定义请求头")
            }
            // 其余情况：接口没实现/上游抽风，但对话大概率能通，放行
            else -> ProbeOutcome(null)
        }
    }

    /** 打一次 `/models`，拿状态码与响应体（探针用，不解析模型）。 */
    private fun rawModelsStatus(apiKey: String): Pair<Int, String> {
        val conn = openConnection(modelsUrl(config), apiKey, null, "GET", config.headers)
        return try {
            val status = conn.responseCode
            val text = runCatching {
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                HttpSupport.readLimited(stream)
            }.getOrDefault("")
            status to text
        } finally {
            conn.disconnect()
        }
    }

    /** 探针结果：reason 为 null 表示通过。 */
    data class ProbeOutcome(val reason: String?)

    /** 已保存的模型列表（UI 读取展示用）。 */
    fun savedModels(): List<String> = config.models

    /** 接口地址（UI 读取展示用）。 */
    val baseUrl: String get() = config.baseUrl

    /** 支持粘贴裸 API Key（或 `{"apiKey":"…"}` 形式的 JSON）。 */
    override fun importCredentials(raw: String): ProviderAccount? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val apiKey = if (trimmed.startsWith("{")) {
            runCatching { JsonParser.parseString(trimmed).asJsonObject.get("apiKey")?.asString }
                .getOrNull().orEmpty()
        } else {
            trimmed
        }
        if (apiKey.isEmpty()) return null
        return ProviderAccount(id, uidOf(apiKey), config.name, secretOf(apiKey))
    }

    // ------------------------------------------------------------------ 内部

    private fun parseKey(account: ProviderAccount): String? {
        val obj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull()
        return obj?.get("apiKey")?.asString?.takeIf { it.isNotEmpty() }
    }

    private fun requestedModel(body: String): String =
        runCatching { JsonParser.parseString(body).asJsonObject.get("model")?.asString.orEmpty() }
            .getOrDefault("")

    private fun isStreaming(body: String): Boolean =
        runCatching { JsonParser.parseString(body).asJsonObject.get("stream")?.asBoolean ?: false }
            .getOrDefault(false)

    private fun extractMessage(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        obj.objOrNull("error")?.let { error ->
            error.get("message")?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        for (key in listOf("message", "msg", "detail")) {
            obj.get(key)?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return ""
    }

    /** 404 文案在说「路径/端点不存在」，而不是「没有这个模型」。 */
    private fun looksLikeMissingEndpoint(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("endpoint") ||
            lower.contains("invalid url") ||
            lower.contains("no route") ||
            message.contains("接口不存在") ||
            message.contains("路径不存在")
    }

    companion object {
        const val KEY_PREFIX = "key-"

        /**
         * 用给定的 baseUrl 与 key 拉模型列表。
         *
         * 不依赖已保存的配置：新建供应商时用户还没保存，也要能先拉一次看看有哪些模型。
         */
        fun fetchModels(baseUrl: String, apiKey: String, protocol: String = "openai"): List<String> {
            if (baseUrl.isBlank()) return emptyList()
            // 拉模型和发对话必须用同一套协议路径，否则目录能拉到、对话却 404。
            val probeConfig = io.synapse.core.gateway.CustomProviderConfig(
                key = "probe", name = "probe", baseUrl = baseUrl, apiKeys = emptyList(),
                protocol = protocol,
            )
            val conn = try {
                openConnection(modelsUrl(probeConfig), apiKey, null, "GET", emptyList())
            } catch (_: Exception) {
                return emptyList()
            }
            return try {
                if (conn.responseCode !in 200..299) return emptyList()
                val text = HttpSupport.readLimited(conn.inputStream)
                val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
                    ?: return emptyList()
                val data = obj.arrayOrNull("data") ?: return emptyList()
                data.mapNotNull { element ->
                    val item = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
                    item.get("id")?.asString?.takeIf { it.isNotEmpty() }
                }
            } catch (_: Exception) {
                emptyList()
            } finally {
                conn.disconnect()
            }
        }

        private fun openConnection(
            url: String,
            key: String,
            body: String?,
            method: String,
            extraHeaders: List<io.synapse.core.gateway.HttpHeader>,
            proto: io.synapse.core.gateway.ProviderProtocol =
                io.synapse.core.gateway.ProviderProtocols.OPENAI,
        ): HttpURLConnection {
            // 用 LinkedHashMap 而不是 buildMap：用户填的多个同名头（某些站要两条
            // X-Forwarded-* 之类）必须都能留下来，Map 会去重成一条。
            val headers = LinkedHashMap<String, String>()
            headers["Accept"] = "text/event-stream, application/json"
            if (key.isNotEmpty()) {
                when (proto.auth) {
                    io.synapse.core.gateway.ProtocolAuth.BEARER -> headers["Authorization"] = "Bearer $key"
                    // Anthropic 原生要 x-api-key + anthropic-version，缺后者直接 400
                    io.synapse.core.gateway.ProtocolAuth.X_API_KEY -> {
                        headers["x-api-key"] = key
                        headers["anthropic-version"] = "2023-06-01"
                    }
                    // Gemini 的 Key 走查询参数，不在头里（下面拼 URL 时补）
                    io.synapse.core.gateway.ProtocolAuth.QUERY_KEY -> Unit
                }
            }
            if (body != null) headers["Content-Type"] = "application/json"
            // 用户自定义头最后应用：同名覆盖内置值（尤其是 Authorization：
            // 有些站点要 x-api-key 或裸 Key，硬塞 Bearer 一定验不过）
            for (h in extraHeaders) {
                val name = h.name.trim()
                if (name.isEmpty() || !io.synapse.core.gateway.HttpHeader.isValidName(name)) continue
                if (!io.synapse.core.gateway.HttpHeader.isValidValue(h.value)) continue
                // Map 的 key 大小写敏感，HTTP 头大小写不敏感：先清掉大小写不同的同名项，
                // 否则 Authorization 和 authorization 会同时发出去
                headers.keys.filter { it.equals(name, ignoreCase = true) }.forEach { headers.remove(it) }
                headers[name] = h.value
            }
            val finalUrl = if (
                proto.auth == io.synapse.core.gateway.ProtocolAuth.QUERY_KEY &&
                key.isNotEmpty() &&
                !url.contains("key=")
            ) {
                val encoded = java.net.URLEncoder.encode(key, Charsets.UTF_8.name())
                url + (if (url.contains("?")) "&" else "?") + "key=$encoded"
            } else {
                url
            }
            return HttpSupport.open(finalUrl, method, headers, body, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        }

        /**
         * 拼非对话端点的完整地址。
         *
         * baseUrl 的三种常见写法都要能拼对:
         * - `https://x.com/v1`        → `https://x.com/v1/images/generations`(去重 /v1)
         * - `https://x.com`           → `https://x.com/v1/images/generations`(补 /v1)
         * - `https://x.com/v1/`       → 同第一种(尾部斜杠已 trim)
         *
         * 已经带完整端点路径时直接用它(用户可能把 baseUrl 填成了完整地址)。
         */
        private fun passthroughUrl(baseUrl: String, path: String): String =
            io.synapse.core.gateway.ProviderProtocols.joinBaseAndPath(baseUrl, path)

        /**
         * 按协议拼对话地址。Gemini 的路径里有 `{model}`，必须换成这次请求的模型 id，
         * 留着字面量上游会直接 404。
         */
        private fun chatUrl(config: io.synapse.core.gateway.CustomProviderConfig, model: String): String {
            val proto = io.synapse.core.gateway.ProviderProtocols.of(config.protocol)
            var path = proto.chatPath
            if (path.contains("{model}")) {
                val safe = model.trim().ifEmpty { "gemini-2.0-flash" }
                val encoded = java.net.URLEncoder.encode(safe, Charsets.UTF_8.name()).replace("+", "%20")
                path = path.replace("{model}", encoded)
            }
            return io.synapse.core.gateway.ProviderProtocols.joinBaseAndPath(config.baseUrl, path)
        }

        private fun modelsUrl(config: io.synapse.core.gateway.CustomProviderConfig): String {
            val proto = io.synapse.core.gateway.ProviderProtocols.of(config.protocol)
            val path = proto.modelsPath.ifEmpty { "/models" }
            return io.synapse.core.gateway.ProviderProtocols.joinBaseAndPath(config.baseUrl, path)
        }

        /** 自定义供应商的账号 uid 由 key 派生（只用于区分账号，不泄露完整 key）。 */
        fun uidOf(apiKey: String): String =
            KEY_PREFIX + apiKey.hashCode().toUInt().toString(16).padStart(8, '0')

        fun secretOf(apiKey: String): String =
            JsonObject().apply { addProperty("apiKey", apiKey) }.toString()

        /**
         * 从账号 secret（`{"apiKey":"sk-..."}`）里取回明文 Key。
         *
         * 账号池存的是包装后的 JSON，直接拿 secret 当 Key 发 Requests 必被拒。
         * 兼容老数据：不是 JSON 时原样返回（早期版本可能直接存了裸 Key）。
         */
        fun parseKeyOf(secret: String): String? {
            val trimmed = secret.trim()
            if (trimmed.isEmpty()) return null
            if (!trimmed.startsWith("{")) return trimmed
            return runCatching {
                JsonParser.parseString(trimmed).asJsonObject.get("apiKey")?.asString?.takeIf { it.isNotEmpty() }
            }.getOrNull()
        }

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000
    }
}
