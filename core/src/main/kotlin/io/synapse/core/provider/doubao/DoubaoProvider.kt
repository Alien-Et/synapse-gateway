package io.synapse.core.provider.doubao

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.provider.AggregatedChatCall
import io.synapse.core.provider.AuthKind
import io.synapse.core.provider.ChatCall
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
import io.synapse.core.provider.StreamingChatCall
import io.synapse.core.provider.StreamFailureChatCall
import io.synapse.core.provider.UpstreamError
import io.synapse.core.util.HttpSupport
import io.synapse.core.util.NetworkErrorText
import io.synapse.core.util.arrayOrNull
import io.synapse.core.util.asObjectOrNull
import io.synapse.core.util.objOrNull
import io.synapse.core.util.stringOrNull
import java.net.URLEncoder
import java.util.UUID

/**
 * 豆包（`com.larus.nova`）供应商。
 *
 * ## 边界
 *
 * 收标准 OpenAI 请求体，返回 OpenAI 语义的 [ChatCall]。
 * 豆包的 `/chat/completion` 是「9 个顶层键 + SSE 事件流」，转换全部封在本类内部。
 *
 * ## 三条线路
 *
 * 线路由**用户在供应商界面选**（存 provider options，不进凭证）：
 *
 * | 线路 | 模型示例 | 关键字段 |
 * |---|---|---|
 * | 生文·专家 | `seed` | `need_deep_think=3` + `model_item_key="3"` + `reasoning_effort="4"` |
 * | 生文·极速 | `seed` | `need_deep_think=0` + `model_item_key="0"` |
 * | 生图 | `seedream-4-5` | `conversation_mode="creation"` + `model_item_key="9"` |
 * | 生视频 | `seedance_v2.0_mini` | 同生图 + **异步 push**（协议已实测，见 docs/13） |
 *
 * ⚠️ `need_deep_think` 与 `model_item_key` **必须一起改**：
 * 实测专家模式两者都是 3、极速模式两者都是 0，只改一个会让请求自相矛盾。
 *
 * ⚠️ 不要用 `agent_mode` 区分专家/极速 —— 实测两者都是 `"2"`。
 *
 * ## 固定窗口
 *
 * `conversation_id` 首次由服务端在 `SSE_ACK` 里下发，之后复用即「固定在一个对话窗口」。
 * 它跟着账号走，存在凭证里；`fixedWindow=false` 时每次都传本地占位 id，等于每轮开新会话。
 *
 * ## 未验证的部分（如实标注，不假装能用）
 *
 * - **生视频是异步的**：协议已完全实测（`creations[].type=2`、`video.status=3`、
 *   `video.download_url`），但豆包先回「预计等待 10 分钟」，生成完再 push 结果。
 *   所以本轮对话通常拿不到视频 —— 这是产品行为，不是失败。
 */
class DoubaoProvider(
    /** 生文线路：`expert` / `fast`（供应商设置里的 option）。 */
    private val line: () -> String = { LINE_FAST },
    /** 是否固定在一个对话窗口。 */
    private val fixedWindow: () -> Boolean = { true },
    private val authClient: DoubaoAuthClient = DoubaoAuthClient(),
    private val hooks: ProviderHooks = ProviderHooks(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider, io.synapse.core.provider.SmsLoginSupport {

    override val id: String = ID
    override val displayName: String = "豆包"
    override val authKind: AuthKind = AuthKind.SMS_CODE
    override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.CREDIT_REFRESH)

    override fun hosts(): List<String> = listOf("doubao.com")

    // ------------------------------------------------------------------ 模型

    /**
     * 模型目录。
     *
     * 豆包的目录不是「一个接口拉全量」：`/alice/bot/get_bot_list_v2` 给的是 bot 列表，
     * 真正的模型选择器条目由服务端 `default_select_model_map` 下发（chat=3 / creation=9 / office=9）。
     * 这一版先用**静态清单**，把三条线路各给一个代表模型 —— 宁可少列，
     * 也不要列一堆实际打不通的条目。
     */
    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        if (account == null) return ProviderModelCatalogView(emptyList(), fromFallback = false, error = "")
        val models = listOf(
            ProviderModel(
                id = MODEL_SEED,
                name = "豆包 Seed（生文）",
                extra = mapOf("tags" to "chat"),
            ),
            ProviderModel(
                id = MODEL_SEEDREAM,
                name = "Seedream 4.5（生图）",
                extra = mapOf("tags" to "image"),
            ),
            ProviderModel(
                id = MODEL_SEEDANCE,
                name = "Seedance 2.0 Mini（生视频·异步）",
                extra = mapOf("tags" to "video"),
            ),
        )
        return ProviderModelCatalogView(models, fromFallback = false, error = "")
    }

    /** 模型名原样返回：不做「短名自动补线路」，那等于替用户决定。 */
    override fun resolveModel(requested: String): String = requested.trim()

    override fun isInternalModel(id: String): Boolean = false

    /**
     * 粘贴导入凭证。
     *
     * 接受两种输入：本 Provider 自己 [DoubaoCredential.toSecret] 出来的 JSON，
     * 或用户直接从抓包里复制的**原始 Cookie 串**。
     * uid 优先取 Cookie 里的 `__tea__ug__uid`；取不到时用 Cookie 的哈希兜底 ——
     * 账号池要求 uid 非空，宁可给一个稳定哈希，也不要因为「没登录态」拒绝导入。
     */
    override fun importCredentials(raw: String): ProviderAccount? {
        val credential = DoubaoCredential.parse(raw) ?: return null
        val uid = credential.uid.ifEmpty {
            "doubao-" + credential.cookie.hashCode().toUInt().toString(16)
        }
        return ProviderAccount(
            providerId = ID,
            uid = uid,
            nickname = "豆包",
            secret = credential.toSecret(),
        )
    }

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = DoubaoCredential.parse(account.secret)
            ?: return FailedChatCall(0, "豆包凭证无效：缺少 Cookie 或 d_ticket")
        val request = runCatching { JsonParser.parseString(openAiBody).asJsonObject }.getOrNull()
            ?: return FailedChatCall(0, "请求体不是合法 JSON")

        val requestedModel = request.stringOrNull("model").orEmpty().ifEmpty { MODEL_SEED }
        val media = mediaKindOf(requestedModel)
        val streaming = request.get("stream")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

        val sessionId = if (fixedWindow() && credential.conversationId.isNotEmpty()) {
            credential.conversationId
        } else {
            // 本地占位：`main_<uuid>_<botId>-local`，与官方客户端一致。
            // 服务端会在 SSE_ACK 里回真实 id。
            "main_${UUID.randomUUID()}_${credential.botId}-local"
        }

        val body = buildUpstreamBody(request, credential, sessionId, media)

        val conn = try {
            HttpSupport.open(
                url = chatUrl(credential),
                method = "POST",
                headers = chatHeaders(credential),
                body = body,
                connectTimeoutMs = CONNECT_TIMEOUT_MS,
                readTimeoutMs = READ_TIMEOUT_MS,
            )
        } catch (e: Exception) {
            hooks.onLog("豆包请求异常：${NetworkErrorText.logText(e, HOST)}")
            return FailedChatCall(0, NetworkErrorText.describe(e, HOST))
        }

        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            hooks.onLog("豆包请求异常：${NetworkErrorText.logText(e, HOST)}")
            return FailedChatCall(0, NetworkErrorText.describe(e, HOST))
        }

        if (status !in 200..299) {
            val text = runCatching { HttpSupport.readBody(conn) }.getOrDefault("")
            conn.disconnect()
            hooks.onLog("豆包上游拒绝（HTTP $status）：${text.take(400)}")
            return FailedChatCall(status, text)
        }

        val raw = conn.inputStream
        val translator = DoubaoStreamTranslator(
            model = requestedModel,
            onConversationId = { newId ->
                // 服务端分配的会话 id 落盘，之后才能「固定窗口」
                if (newId.isNotEmpty() && newId != credential.conversationId) {
                    hooks.onAccountUpdated(
                        account.copy(secret = credential.copy(conversationId = newId).toSecret()),
                    )
                }
            },
        )
        val stream = LineTransformStream(
            source = raw,
            transform = translator::translate,
            onFinish = translator::finish,
        )
        val streamingCall = StreamingChatCall(status, stream) { conn.disconnect() }

        if (streaming) return streamingCall

        // 客户端要非流式：本地聚合（豆包没有非流式响应形态，主链路恒走 SSE）
        val aggregated = try {
            OpenAiSseAggregator.aggregate(streamingCall.stream!!, requestedModel)
        } catch (e: Exception) {
            hooks.onLog("豆包聚合响应失败：${e.message}")
            streamingCall.close()
            return FailedChatCall(0, "聚合豆包响应失败：${e.message}")
        } finally {
            runCatching { streamingCall.close() }
        }
        // HTTP 200 不代表成功：豆包被频控时会在 SSE 里回 STREAM_ERROR
        // （710022002「当前服务访问频繁」）。翻译器已把它转成 OpenAI 顶层 error 帧；
        // 非流式路径要从 translator 里取回同一份 failure，如实交给网关换号/退避，
        // 绝不能当成「空回复」或「凭证失效」——那会误导用户去重新登录。
        translator.failure?.let { return StreamFailureChatCall(200, it) }
        if (OpenAiSseAggregator.isEmptyCompletion(aggregated)) {
            return FailedChatCall(200, "豆包返回空回复（上游 200 但没有任何内容）")
        }
        return AggregatedChatCall(200, aggregated)
    }

    // ------------------------------------------------------------------ 短信登录（SmsLoginSupport）

    /** 多账号暂存：msgid（`sel:<sec_uid>`）→ 第二次登录需要的 ticket/key。 */
    private val pendingMulti = java.util.concurrent.ConcurrentHashMap<String, DoubaoAuthClient.LoginOutcome.MultiAccount>()

    override fun sendSmsCode(phone: String, ccode: String): String {
        val result = authClient.sendSmsCode(phone, ccode)
        // SmsLoginSupport 的 msgid 语义 = 登录时回传的中间凭证。
        // 豆包的 encodedMobile 本身可以从 phone 重新算出，但传一遍更稳（与上游会话绑定）。
        return result.encodedMobile
    }

    override fun loginBySmsCode(
        phone: String,
        code: String,
        msgid: String,
        ccode: String,
    ): ProviderAccount {
        // 「选中账号」的 sel token 可能落在 msgid（标准通道）或 code（当前 UI 的传法），
        // 抽成纯函数由单测锁死，避免再出现「只判一个通道 → 选号必失败」的回归。
        val selToken = DoubaoAuthClient.extractSelToken(msgid, code)
        val outcome = if (selToken.isNotEmpty()) {
            val pending = pendingMulti.remove(selToken)
                ?: throw IllegalStateException("多账号会话已过期或不存在，请重新登录")
            // 一个选号会话给每个候选都注册了 key（都指向同一份 outcome）。
            // Collection.remove(o) 只删**一个**匹配元素，清不干净；按引用逐 key 移除，
            // 也避免 data class 的 equals 把另一批内容恰好相同的会话误删。
            for (entry in pendingMulti.entries) {
                if (entry.value === pending) pendingMulti.remove(entry.key)
            }
            authClient.loginSelect(
                phone = phone,
                ccode = ccode,
                msgid = selToken,
                mobileTicket = pending.mobileTicket,
                smsCodeKey = pending.smsCodeKey,
            )
        } else {
            authClient.loginBySmsCode(phone, code, msgid, ccode)
        }
        return when (outcome) {
            is DoubaoAuthClient.LoginOutcome.Success -> {
                val credential = outcome.credential
                // Set-Cookie 有可能不含主凭证（上游异常 / 中间代理改写），此时落库一个
                // 「能存进去但一调就 401」的废账号，比直接报错更难排查。登录即校验。
                if (!credential.hasTicket) {
                    throw DoubaoLoginException("登录响应未下发主凭证 d_ticket，请重新登录")
                }
                val uid = credential.uid.ifEmpty {
                    "doubao-" + credential.cookie.hashCode().toUInt().toString(16)
                }
                ProviderAccount(
                    providerId = ID,
                    uid = uid,
                    nickname = "豆包",
                    secret = credential.toSecret(),
                )
            }
            is DoubaoAuthClient.LoginOutcome.MultiAccount -> {
                // 多账号：候选信息塞进异常消息传递（SmsLoginSupport 只允许返回
                // ProviderAccount 或抛异常，UI 端解析 `MULTI_ACCOUNT|` 前缀）。
                // ⚠️ 旧实现只注册「第一个账号」的 key，用户选第 2/3 个时
                // pendingMulti.remove() 必然落空。改成**每个候选都注册一份**。
                val first = outcome.subAccounts.firstOrNull()
                    ?: throw IllegalStateException("多账号响应里没有候选账号")
                outcome.subAccounts.forEach { sub ->
                    if (sub.secUid.isNotEmpty()) {
                        pendingMulti[DoubaoAuthClient.SEL_PREFIX + sub.secUid] = outcome
                    }
                }
                throw DoubaoMultiAccountException(
                    accounts = outcome.subAccounts,
                    mobileTicket = outcome.mobileTicket,
                    smsCodeKey = outcome.smsCodeKey,
                    hintMsgid = DoubaoAuthClient.SEL_PREFIX + first.secUid,
                )
            }
        }
    }

    // ------------------------------------------------------------------ 请求构造

    /** 媒体类型：`chat` / `image` / `video`。 */
    private fun mediaKindOf(model: String): String {
        val lower = model.lowercase()
        return when {
            lower.contains("seedream") || lower.contains("image") || lower.contains("draw") -> "image"
            lower.contains("seedance") || lower.contains("video") -> "video"
            else -> "chat"
        }
    }

    /**
     * 白名单重建请求体（不是原样透传）。
     *
     * 豆包只认有限字段，多余的会触发参数校验。九个顶层键里，
     * `chat_query_option` 是线路控制的核心，必须按用户选的线路精确构造。
     */
    private fun buildUpstreamBody(
        request: JsonObject,
        credential: DoubaoCredential,
        sessionId: String,
        media: String,
    ): String {
        val now = nowMillis()
        val expert = line() == LINE_EXPERT && media == "chat"
        val conversationMode = if (media == "chat") "chat" else "creation"
        val modelItemKey = when (media) {
            "image", "video" -> MODEL_ITEM_CREATION
            else -> if (expert) MODEL_ITEM_EXPERT else MODEL_ITEM_FAST
        }

        val clientMeta = JsonObject().apply {
            addProperty("local_conversation_id", sessionId)
            addProperty("conversation_id", sessionId)
            addProperty("bot_id", credential.botId)
            addProperty("scene_type", 0)
            addProperty("biz_type", "")
            addProperty("section_id", "")
            add("local_permissions", JsonArray())
            addProperty("last_section_id", "")
            addProperty("last_message_index", 0)
        }

        val chatQueryOption = JsonObject().apply {
            addProperty("send_message_scene", "keyboard")
            addProperty("create_time_ms", now)
            addProperty("is_audio", false)
            addProperty("answer_with_suggest", true)
            addProperty("agent_mode", 2)
            addProperty("mode_id", "1")
            // 极速模式实测会开语音播报；这里跟随同一口径
            addProperty("tts_switch", !expert)
            addProperty("need_deep_think", if (expert) 3 else 0)
            addProperty("from_suggest", false)
            addProperty("click_clear_context", false)
            addProperty("is_regen", false)
            addProperty("no_replace_for_regen", false)
            addProperty("regen_instruction", "")
            addProperty("unique_key", "")
            addProperty("is_replace", false)
            addProperty("resend_for_regen", false)
            addProperty("memory_type", 0)
            addProperty("scene_type", 0)
            // 只有专家模式才初始化会话线路；极速模式实测整个字段消失
            if (expert) {
                add("conversation_init_ext", JsonObject().apply {
                    addProperty("model_item_key", modelItemKey)
                    addProperty("mode_id", "1")
                    addProperty("reasoning_effort", "4")
                })
            }
            add("model_config", JsonObject().apply {
                addProperty("model_item_key", modelItemKey)
                add("model_extra_params", JsonObject())
                addProperty("reasoning_effort", if (expert) 4 else 4)
            })
            add("chat_entry_source", JsonObject().apply {
                addProperty("source_type", 0)
                addProperty("is_first_open_in_source_page", false)
                addProperty("is_chat_flow_collapsed", false)
                add("biz_ext", JsonObject().apply {
                    addProperty("model_item_key", modelItemKey)
                    addProperty("provider_id", "")
                    addProperty("mode_id", "1")
                    addProperty("reasoning_effort", if (expert) "4" else "4")
                    addProperty("conversation_mode", conversationMode)
                })
            })
            add("aggregate_params", JsonObject().apply {
                addProperty("model_item_key", modelItemKey)
                addProperty("provider_id", "")
                addProperty("mode_id", "1")
                addProperty("reasoning_effort", if (expert) "4" else "4")
                addProperty("conversation_mode", conversationMode)
            })
            add("queue_client_state", JsonObject().apply {
                addProperty("queue_enabled", false)
                addProperty("queue_is_empty", true)
                addProperty("queue_submit_mode", 2)
            })
        }

        val ext = JsonObject().apply {
            addProperty("create_time_ms", now.toString())
            addProperty("record_status", "1")
            addProperty("wiki", "2")
            addProperty("search_engine_type", "4")
            addProperty("media_search_type", "1")
            addProperty("answer_with_suggest", "1")
            addProperty("system_language", "zh")
            addProperty("sec_scene", "")
            addProperty("inner_log_id", logId(now))
            addProperty("is_app_background", "0")
            addProperty("is_douyin_installed", "1")
            addProperty("is_luna_installed", "0")
            addProperty("send_message_scene", "keyboard")
            addProperty("commerce_credit_config_enable", "0")
            addProperty("need_deep_think", if (expert) "3" else "0")
            addProperty("agent_mode", "2")
            if (!expert) addProperty("tts", "1")
        }

        val trackContext = JsonObject().apply {
            addProperty("business_send_start_timestamp", now)
            add("common_tags", JsonObject().apply {
                addProperty("c_trace_id", UUID.randomUUID().toString().replace("-", ""))
                addProperty("scene", "keyboard")
                addProperty("agent_mode", "2")
            })
        }

        val out = JsonObject().apply {
            add("client_meta", clientMeta)
            add("request_messages", toRequestMessages(request, credential))
            add("chat_query_option", chatQueryOption)
            add("ext", ext)
            add("chat_ability", JsonObject())
            add("user_context", JsonArray())
            add("track_context", trackContext)
            add("remote_ext_black_list", JsonArray())
            addProperty("is_resend_scene", false)
        }
        return out.toString()
    }

    /**
     * OpenAI `messages[]` → 豆包 `request_messages[]`。
     *
     * 只把**最后一条 user** 作为本次发送内容：豆包的会话上下文由服务端按
     * `conversation_id` 维护，重复上传整段历史会让上下文翻倍。
     * 若客户端坚持整包历史（`fixedWindow=false` 的无状态模式），
     * 则退化成「把历史拼成一段文本」——不如有状态准确，但至少不丢信息。
     */
    private fun toRequestMessages(request: JsonObject, credential: DoubaoCredential): JsonArray {
        val messages = request.arrayOrNull("messages") ?: JsonArray()
        val text = extractPrompt(messages)
        val block = JsonObject().apply {
            addProperty("block_type", BLOCK_TEXT)
            addProperty("block_id", UUID.randomUUID().toString())
            add("content", JsonObject().apply {
                add("text_block", JsonObject().apply {
                    addProperty("text", text)
                    addProperty("text_block_scene", 0)
                })
            })
            addProperty("is_finish", false)
        }
        val message = JsonObject().apply {
            addProperty("local_message_id", UUID.randomUUID().toString())
            add("content_block", JsonArray().apply { add(block) })
            addProperty("reply_id", "")
            addProperty("sender_id", credential.uid)
            addProperty("user_type", 1)
            addProperty("source_from_asr", false)
            addProperty("message_status_local", -1)
            addProperty("message_status", 0)
            add("message_specific_ext", JsonObject().apply {
                addProperty("before_content_type", 1)
                addProperty("send_message_scene", "keyboard")
            })
        }
        return JsonArray().apply { add(message) }
    }

    /** 取最后一条 user 的文本；没有 user 时把所有消息按角色拼起来兜底。 */
    private fun extractPrompt(messages: JsonArray): String {
        var lastUser = ""
        val all = StringBuilder()
        for (element in messages) {
            val msg = element.asObjectOrNull() ?: continue
            val role = msg.stringOrNull("role").orEmpty()
            val content = contentText(msg)
            if (content.isEmpty()) continue
            if (role == "user") lastUser = content
            all.append(if (role == "user") "" else "[$role] ").append(content).append('\n')
        }
        return lastUser.ifEmpty { all.toString().trim() }
    }

    /** 兼容 `content` 是字符串与 `[{type:"text",text:"..."}]` 两种形态。 */
    private fun contentText(msg: JsonObject): String {
        msg.stringOrNull("content")?.let { return it }
        val arr = msg.arrayOrNull("content") ?: return ""
        val sb = StringBuilder()
        for (element in arr) {
            val part = element.asObjectOrNull() ?: continue
            part.stringOrNull("text")?.let { sb.append(it) }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ HTTP

    private fun chatUrl(credential: DoubaoCredential): String =
        "$BASE_URL/chat/completion?${commonQuery(credential)}&flow_im_arch=v2&is_retry=0"

    /**
     * 公共 query 参数。
     *
     * 这一套是豆包的**全站身份**（`aid` / `app_name` / 设备参数），
     * 缺任何一项都可能被判成非官方客户端。值来自实测抓包。
     */
    private fun commonQuery(credential: DoubaoCredential): String {
        val params = linkedMapOf(
            "device_platform" to "android",
            "os" to "android",
            "ssmix" to "a",
            "cdid" to credential.cdid.ifEmpty { "8d39bab5-a4ac-41b2-b9b2-53fccbb889df" },
            "channel" to "oppo_482431_64",
            "aid" to "482431",
            "app_name" to "nova_ai",
            "version_code" to "15070001",
            "version_name" to "15.7.0",
            "manifest_version_code" to "15070040",
            "update_version_code" to "15070040",
            "resolution" to "1080*2208",
            "dpi" to "444",
            "device_type" to "PJE110",
            "device_brand" to "OnePlus",
            "language" to "zh",
            "os_api" to "36",
            "os_version" to "16",
            "ac" to "wifi",
            "dora_status" to "0",
            "app_language" to "zh",
            "carrier_region" to "cn",
            "flow_app_variant" to "doubao",
            "user_is_login" to "1",
            "_rticket" to nowMillis().toString(),
        )
        return params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    private fun chatHeaders(credential: DoubaoCredential): Map<String, String> = mapOf(
        "Cookie" to credential.cookie,
        "User-Agent" to USER_AGENT,
        "Content-Type" to "application/json; charset=UTF-8",
        "Accept" to "text/event-stream",
        "x-ttnet-bypass-to" to "-1",
        "Accept-Encoding" to "identity",
    )

    // ------------------------------------------------------------------ 错误分类

    override fun classify(status: Int, body: String): UpstreamError {
        val raw = extractMessage(body).ifEmpty { "上游 HTTP $status" }
        val kind = when {
            status == 401 || status == 403 -> ErrorKind.SESSION_DEAD
            status == 402 -> ErrorKind.QUOTA
            status == 429 -> ErrorKind.SOFT_RATE
            status in 500..599 -> ErrorKind.SERVER
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, explain(status, kind, raw))
    }

    /** 给上游错误补中文语义；原文保留在 `｜上游原文：` 之后供排查。 */
    private fun explain(status: Int, kind: ErrorKind, raw: String): String {
        if (raw.any { it.code in 0x4E00..0x9FFF }) return raw
        val hint = when {
            status == 401 -> "豆包登录凭证已失效（Cookie 里的 d_ticket 过期或被吊销），需要重新导入凭证"
            status == 403 -> "豆包以 403 拒绝：可能是设备指纹不匹配，或该账号被风控"
            status == 402 || kind == ErrorKind.QUOTA -> "豆包额度已用完"
            status == 429 -> "豆包限流，稍后自动恢复"
            status in 500..599 -> "豆包上游服务异常"
            else -> return raw
        }
        return "$hint｜上游原文：$raw"
    }

    private fun extractMessage(body: String): String {
        if (body.isBlank()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        obj.stringOrNull("msg")?.takeIf { it.isNotBlank() }?.let { return it }
        obj.stringOrNull("message")?.takeIf { it.isNotBlank() }?.let { return it }
        obj.objOrNull("error")?.stringOrNull("message")?.takeIf { it.isNotBlank() }?.let { return it }
        return ""
    }

    private fun logId(now: Long): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
            .format(java.util.Date(now))
        return stamp + UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
    }

    companion object {
        const val ID = "doubao"
        const val BASE_URL = "https://api-normal.doubao.com"
        private const val HOST = "api-normal.doubao.com"

        /** 供应商设置里的线路取值。 */
        const val LINE_EXPERT = "expert"
        const val LINE_FAST = "fast"

        /** 静态模型 id。 */
        const val MODEL_SEED = "seed"
        const val MODEL_SEEDREAM = "seedream-4-5"
        const val MODEL_SEEDANCE = "seedance_v2.0_mini"

        /** 服务端 `default_select_model_map` 实测值。 */
        private const val MODEL_ITEM_EXPERT = "3"
        private const val MODEL_ITEM_FAST = "0"
        private const val MODEL_ITEM_CREATION = "9"

        private const val BLOCK_TEXT = 10000

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000

        private const val USER_AGENT = "com.larus.nova/15070040 (Linux; U; Android 16; zh_CN)"
    }
}
