package io.synapse.core.provider.mcloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.provider.AggregatedChatCall
import io.synapse.core.provider.AuthKind
import io.synapse.core.provider.ChatCall
import io.synapse.core.provider.CreditInfo
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.FailedChatCall
import io.synapse.core.provider.HumanCheckChallenge
import io.synapse.core.provider.HumanCheckSupport
import io.synapse.core.provider.OpenAiSseAggregator
import io.synapse.core.provider.Provider
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderCapability
import io.synapse.core.provider.ProviderHooks
import io.synapse.core.provider.ProviderModel
import io.synapse.core.provider.PointsBill
import io.synapse.core.provider.PointsBillListView
import io.synapse.core.provider.ProviderModelCatalogView
import io.synapse.core.provider.SmsLoginSupport
import io.synapse.core.provider.StreamFailureChatCall
import io.synapse.core.provider.StreamingChatCall
import io.synapse.core.provider.UpstreamError
import io.synapse.core.provider.isStreamingBody
import io.synapse.core.provider.requestedModelOf
import io.synapse.core.util.NetworkErrorText
import io.synapse.core.util.str

/**
 * 中国移动云盘（139 云）AI 通道。
 *
 * ## 它是什么
 * 「中国移动云盘」App 里的智能助手，背后接了 qwen3.x / GLM / DeepSeek / 火山 Seed 四家模型，
 * 通过 `apikey/get` 下发一个标准 OpenAI 兼容 Key。对网关来说它就是一家
 * 「OpenAI 兼容 + 短信登录」的供应商，和讯飞、腾讯走同一套调度。
 *
 * ## 登录形态
 * 手机号 + 短信验证码（[AuthKind.SMS_CODE]），所以实现 [SmsLoginSupport]。
 * 与讯飞不同的是，这里登录前**还要过一道滑动拼图**（[MCloudAuthClient.fetchSlidePuzzle]），
 * 网关会把图交给界面渲染，拖完再发码。
 *
 * ## 凭证续期
 * 账号里存着两样东西：长期的 `apiKey`（对话用）和短期的 `basicCredential`（换 Key 用）。
 * [refreshAccount] 在 basic 快过期时自动续，续不上就让账号失效、提示重登——
 * 这是「防止登录失效」的全部机制，别在别处偷偷重登（重登必须用户给验证码）。
 */
class MCloudProvider(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val hooks: ProviderHooks = ProviderHooks(),
    private val authClient: MCloudAuthClient = MCloudAuthClient(),
    private val keyClient: MCloudKeyClient = MCloudKeyClient(),
    private val chatClient: MCloudChatClient = MCloudChatClient(),
    /** App 端国密接口；会员中心的发码接口已废弃，登录实际走这条。 */
    private val appClient: MCloudAppClient = MCloudAppClient(),
    /** 额度查询（AI tokens 权益）。 */
    private val creditClient: MCloudCreditClient = MCloudCreditClient(),
) : Provider, SmsLoginSupport, HumanCheckSupport {

    init {
        // 把详细日志接到 provider 的 hooks 上，排查 thirdlogin 这类「上游拒绝」问题时
        // 能看到真实的请求头、加密后的 body 和响应原文
        appClient.verbose = { tag, text -> hooks.onVerbose(tag, text) }
    }

    override val id: String = ID
    override val displayName: String = "移动云盘"
    override val authKind: AuthKind = AuthKind.SMS_CODE
    override val capabilities: Set<ProviderCapability> = setOf(
        ProviderCapability.CREDIT_REFRESH,
        // 消耗明细：上游 getAiTokenConsumptionRecords 能给出逐条 tokens 记录
        ProviderCapability.POINTS_BILLS,
    )

    // ------------------------------------------------------------------ 模型

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        // 模型清单在登录时就跟着 apikey 一起落盘了，不需要再打上游：
        // 上游 `apikey/get` 是唯一权威来源，模型目录接口（/v1/models）反而不给倍率。
        val mcloud = account?.let { parseOrNull(it) }
            ?: return ProviderModelCatalogView(emptyList(), fromFallback = true, error = "还没有登录账号")
        if (mcloud.models.isEmpty()) {
            return ProviderModelCatalogView(emptyList(), fromFallback = true, error = "这个账号还没拉到模型清单，请点刷新")
        }
        return ProviderModelCatalogView(
            models = mcloud.models.map { m ->
                ProviderModel(
                    id = m.code,
                    name = m.name.ifEmpty { m.code },
                    contextWindow = 0,
                    extra = buildMap {
                        if (m.ability.isNotEmpty()) put("ability", m.ability)
                        // 倍率与腾讯/字节/讯飞共用同一套 extra 字段名，界面渲染逻辑才能复用
                        val rate = formatRate(m.tokenRatio)
                        if (rate.isNotEmpty()) put("rate", rate + "×")
                        if (m.iconUrl.isNotEmpty()) put("iconUrl", m.iconUrl)
                    },
                )
            },
            fromFallback = false,
            error = "",
        )
    }

    /** 把上游给的倍率字符串规整成展示用（`0.18` → `0.18`，`1.0` → `1`）。 */
    private fun formatRate(raw: String): String {
        val value = raw.trim().toDoubleOrNull() ?: return ""
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            String.format(java.util.Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }
    }

    /**
     * 把模型名归一成上游编码。
     *
     * 上游编码（`blian_qwen38_flash`）和展示名（`qwen3.8-Flash`）都能被客户端写进 `model`，
     * 但上游只认编码。这里按「找到就换、找不到原样放行」处理：
     * 认不出的名字透传出去，让上游回 404 比静默改模型诚实。
     *
     * 没有账号上下文时没法查清单，只能剥掉前缀后原样返回。
     */
    override fun resolveModel(requested: String): String {
        val model = requested.trim().substringAfter('/').trim()
        if (model.isEmpty() || model == "auto") return ""
        return model
    }

    /** 带账号上下文的归一化：能查到就用上游编码。 */
    private fun resolveModelWith(account: MCloudAccount?, requested: String): String {
        val model = resolveModel(requested)
        if (model.isEmpty() || account == null) return model
        return account.models
            .firstOrNull { it.code == model || it.name.equals(model, ignoreCase = true) }
            ?.code
            ?: model
    }

    override fun hosts(): List<String> = listOf("yun.139.com")

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val mcloud = parseOrNull(account) ?: return FailedChatCall(401, "账号凭证无法解析")
        val streaming = isStreamingBody(openAiBody)

        // 模型名从展示名映射回上游编码（用户可能填 `qwen3.8-Flash` 或 `blian_qwen38_flash`，都要认）
        val body = withResolvedModel(openAiBody, mcloud)

        val call = try {
            chatClient.openStream(mcloud, body)
        } catch (e: MCloudApiException) {
            return FailedChatCall(e.status, e.body)
        }
        if (call.status !in 200..299) {
            hooks.onLog("移动云盘 对话失败（HTTP ${call.status}）：${call.errorBody.take(500)}")
            return FailedChatCall(call.status, call.errorBody)
        }

        // 上游可能回 JSON（非流式，或 HTTP 200 但带业务错误码）
        if (!call.isEventStream) {
            val text = runCatching { call.stream?.use { it.readBytes().toString(Charsets.UTF_8) } }
                .getOrNull() ?: call.errorBody
            call.close()
            // 200 + 业务错误码：转成流内失败，让网关换号/换供应商
            val errCode = errorCodeOf(text)
            if (errCode.isNotEmpty() && errCode != "0" && !text.contains("\"choices\"")) {
                return StreamFailureChatCall(
                    200,
                    UpstreamError(classifyCode(errCode, text), MCloudErrors.extractMessage(text) ?: "上游拒绝了本次请求", 0),
                )
            }
            return AggregatedChatCall(200, text)
        }

        if (streaming) {
            return StreamingChatCall(200, call.stream!!) { call.close() }
        }
        val aggregated = try {
            OpenAiSseAggregator.aggregate(call.stream!!, resolveModelForAggregate(mcloud, body))
        } finally {
            call.close()
        }
        return AggregatedChatCall(200, aggregated)
    }

    /** 把请求体里的 `model` 换成上游真实编码；换不了就原样透传，让上游回 404 比静默改模型诚实。 */
    private fun withResolvedModel(body: String, account: MCloudAccount): String {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return body
        val requested = obj.str("model")
        if (requested.isEmpty()) {
            // 没指定模型就用第一个，避免上游回「model 不能为空」
            val fallback = account.models.firstOrNull()?.code ?: return body
            obj.addProperty("model", fallback)
            return obj.toString()
        }
        val resolved = resolveModelWith(account, requested)
        if (resolved.isNotEmpty() && resolved != requested) {
            obj.addProperty("model", resolved)
            return obj.toString()
        }
        return body
    }

    private fun resolveModelForAggregate(account: MCloudAccount, body: String): String {
        val requested = requestedModelOf(body)
        return account.models.firstOrNull { it.code == requested || it.name.equals(requested, ignoreCase = true) }
            ?.name
            ?: requested
    }

    /** 取对话响应里的业务错误码（`code` / `error.code`）；正常响应返回空串。 */
    private fun errorCodeOf(body: String): String {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        return obj.str("code").ifEmpty { obj.getAsJsonObject("error")?.str("code").orEmpty() }
    }

    private fun classifyCode(code: String, body: String): ErrorKind = when {
        code == MCloudConstants.CODE_APIKEY_INVALID || code == MCloudConstants.CODE_APIKEY_MISSING ->
            ErrorKind.SESSION_DEAD
        code == MCloudConstants.CODE_BAD_REQUEST -> ErrorKind.CLIENT
        body.contains("余额不足") || body.contains("额度不足") || body.contains("已用完") -> ErrorKind.QUOTA
        body.contains("频繁") || body.contains("稍后再试") -> ErrorKind.SOFT_RATE
        else -> ErrorKind.CLIENT
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val code = MCloudErrors.resultCodeOf(body)
        val kind = when {
            status == 401 || status == 403 -> MCloudErrorKind.SESSION_DEAD
            status == 429 -> MCloudErrorKind.SOFT_RATE
            status >= 500 -> MCloudErrorKind.SERVER
            status == 0 -> MCloudErrorKind.NETWORK
            code == MCloudConstants.CODE_APIKEY_INVALID || code == MCloudConstants.CODE_APIKEY_MISSING ->
                MCloudErrorKind.SESSION_DEAD
            else -> MCloudErrorKind.CLIENT
        }
        val exception = MCloudApiException(status, kind, body, code)
        return UpstreamError(kind.toErrorKind(), NetworkErrorText.describe(exception))
    }

    // ------------------------------------------------------------------ 凭证续期

    /**
     * 自动续期：Basic 凭证快到期，或 apikey 被上游判无效时调用。
     *
     * 返回 null 表示没法自动续（Basic 也过期了），上层会把账号标记为需要重新登录。
     * [skewSeconds] 是提前量，账号剩余有效期小于它才动手。
     */
    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val mcloud = parseOrNull(account) ?: return null
        val now = nowMillis()
        val remaining = mcloud.basicExpiresAtMillis - now
        val shouldRefresh = mcloud.basicCredential.isEmpty() ||
            (mcloud.basicExpiresAtMillis > 0 && remaining <= skewSeconds * 1000L)
        if (!shouldRefresh) return null

        return try {
            val fresh = keyClient.refreshKey(mcloud, now)
            hooks.onLog("移动云盘 已自动续期调用密钥（${fresh.phone}）")
            toProviderAccount(fresh)
        } catch (e: Exception) {
            hooks.onLog("移动云盘 自动续期失败（${mcloud.phone}）：${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ 额度

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val mcloud = parseOrNull(account) ?: return CreditInfo(0, known = false, detail = "账号凭证无法解析")
        val now = nowMillis()
        val canRefresh = mcloud.canAutoRefresh(now)

        // 真实额度来自上游权益接口（抓包实测）：
        //   queryAvailableBenefitV2 → benefitValue（总量）/ benefitUsed（已用）
        //   queryMonthlyTokenConsumption → 本月累计
        // 两者都是 tokens，不是次数。查不到时退回「未知」而不是 0。
        val probe = runCatching { creditClient.fetchCreditDetailed(mcloud) }
        val credit = probe.getOrNull()?.credit
        val trace = probe.getOrNull()?.trace.orEmpty()
        val failure = probe.exceptionOrNull()

        // 失败诊断必须落进 detail：refreshCredits 在 known=false 时会用 detail 当失败原因展示，
        // 只写 logWarn 的话用户和日志两边都看不到真正原因（之前就是这样查不出问题）。
        val diag = buildString {
            if (failure != null) append("异常 ${failure.message ?: failure::class.simpleName}")
            if (trace.isNotEmpty()) {
                if (isNotEmpty()) append("；")
                append(trace.joinToString("；"))
            }
        }
        hooks.onLog("移动云盘 额度查询（${mcloud.phone}）：${diag.ifEmpty { "无诊断信息" }}")

        if (credit != null && credit.totalTokens > 0) {
            val detail = buildCreditDetail(credit)
            return CreditInfo(
                balance = credit.remainTokens,
                known = true,
                detail = detail,
                actionHint = if (canRefresh) "" else "点「重新登录」用手机号收验证码登录一次",
                memberLevel = credit.memberLevel,
            )
        }

        // 权益接口查不到（未开通 AI 权益 / 凭证过期 / 网络异常）：
        // 保留原来的「不可知」语义，不编数字；detail 带上诊断，用户能自己看懂卡在哪
        return CreditInfo(
            balance = 0,
            known = false,
            detail = buildString {
                append(if (canRefresh) "额度查询失败" else "登录凭证已过期，需要重新登录")
                if (diag.isNotEmpty()) append("：$diag")
            },
            actionHint = if (canRefresh) "点「刷新额度」重试" else "点「重新登录」用手机号收验证码登录一次",
        )
    }

    /**
     * 把额度快照拼成给人看的一行明细。
     *
     * 格式：`剩余 1088.3万 / 总量 1100万 · 本月已用 11.7万（2026-11）`
     * 大数字换算成「万」更易读，小于 1 万时显示原值。
     */
    private fun buildCreditDetail(credit: MCloudCredit): String {
        val parts = mutableListOf<String>()
        parts += "剩余 ${formatTokens(credit.remainTokens)} tokens"
        parts += "总量 ${formatTokens(credit.totalTokens)}"
        if (credit.monthlyKnown) {
            parts += "本月已用 ${formatTokens(credit.monthlyTokens)}（${credit.monthLabel}）"
        } else {
            parts += "累计已用 ${formatTokens(credit.usedTokens)}"
        }
        if (!credit.isActive && credit.benefitStatus.isNotEmpty()) {
            parts += "权益未生效"
        }
        return parts.joinToString(" · ")
    }

    /** tokens 数值美化：≥1 万用「万」，≥1 亿用「亿」，保留一位小数。 */
    private fun formatTokens(tokens: Long): String {
        val v = tokens.toDouble()
        return when {
            v >= 100_000_000 -> String.format("%.2f亿", v / 100_000_000)
            v >= 10_000 -> String.format("%.1f万", v / 10_000)
            else -> tokens.toString()
        }
    }

    /**
     * AI tokens 消耗明细（`vip.yun.139.com/m4c/benefit/getAiTokenConsumptionRecords`）。
     *
     * 复用 `POINTS_BILLS` 这套界面：每行是「模型名 · tokens · 系数 · 时间」，
     * `points` 字段放的是 **tokens 消耗量（负数）**，`bizType` 放模型系数。
     * 只取 `action=1`（消耗），`action=4` 是月底权益到期清理，不属于使用。
     */
    override fun pointsBills(account: ProviderAccount): PointsBillListView? {
        val mcloud = parseOrNull(account) ?: return null
        return try {
            val records = creditClient.fetchRecords(mcloud, pageSize = POINTS_BILLS_LIMIT)
            PointsBillListView(
                bills = records.mapIndexed { index, r ->
                    PointsBill(
                        id = "${r.timeText}-$index",
                        title = buildString {
                            append(r.modelName.ifEmpty { "AI 对话" })
                            if (r.coefficient.isNotEmpty()) append("（系数 ${r.coefficient}）")
                        },
                        points = -r.tokens,
                        bizType = r.comments,
                        createdAtMillis = parseRecordTime(r.timeText),
                    )
                },
                error = if (records.isEmpty()) "上游没有返回消耗记录" else "",
            )
        } catch (e: Exception) {
            hooks.onLog("移动云盘 消耗明细拉取失败：${e.message}")
            PointsBillListView(emptyList(), error = NetworkErrorText.describe(e, "ypqy.mcloud.139.com"))
        }
    }

    /** 解析上游时间串（`2026-10-31 18:11:23`，本地时区）。失败返回 0。 */
    private fun parseRecordTime(text: String): Long {
        if (text.isEmpty()) return 0L
        return runCatching {
            java.time.LocalDateTime.parse(text.replace(' ', 'T'))
                .atZone(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(0L)
    }

    // ------------------------------------------------------------------ 短信登录

    /**
     * 生成人机校验入口（实现 [HumanCheckSupport]）。
     *
     * 走 **WebView 模式**，不是自绘滑块：移动云盘的滑块是阿里云验证码 3.0，
     * 跑在 `yun.139.com/fe/captcha/` 的 H5 页面里。实测这条路是官方 App 自己在用的
     * （`LoginActivity.jumpVerifyWeb` → `WebView.loadUrl`）。
     *
     * 早先尝试过的 `vip.yun.139.com/m4c/login/getSlidePuzzleCode`（RSA 加密手机号）**不通**：
     * 用 RSA 打回 `500 服务器内部错误`，用 AES 打回 `-1 参数解密失败`。
     * 那不是当前版本的流程，别再走回头路。
     */
    override fun fetchHumanCheck(phone: String): HumanCheckChallenge {
        val url = MCloudCaptcha.buildVerifyUrl(
            phone = phone.filter { it.isDigit() },
            device = device(),
        )
        return HumanCheckChallenge(
            webUrl = url,
            // H5 页面验证通过后会自己调这个接口发码，响应 success=true 就是成功信号
            successUrlHint = MCloudConstants.PATH_SEND_SMS,
            // 页面按 UA 判断宿主，必须是官方 App 的 WebView 形态
            userAgent = device().webViewUserAgent,
        )
    }

    /**
     * 人机校验是否在 WebView 里自己把码发掉了。
     *
     * 为 true 时 [sendSmsCode] 会**跳过实际发码**，只回一个占位 msgid ——
     * 因为 H5 页面已经发过了（实测其响应里没有 msgid 字段，上游也不强校验），
     * 再发一次会白白消耗 `remainGetTimes` 次数。
     */
    @Volatile
    private var smsSentByWebView = false

    /**
     * 界面确认校验页里发码成功后回调（实现 [HumanCheckSupport.onHumanCheckPassed]）。
     *
     * 置位后下一次 [sendSmsCode] 会跳过实际发码。
     */
    override fun onHumanCheckPassed() {
        smsSentByWebView = true
    }

    /**
     * 发短信验证码。
     *
     * 走 App 端国密接口（`/user/sms/getSmsCode`），**不是**会员中心那个
     * `getDyncPasswd` —— 后者实测恒返回 `500 服务器内部错误`，上游已弃用。
     * 这里踩过坑：早期按网页端接口实现，每次发码都弹「服务器内部错误」。
     *
     * **特殊分支**：如果校验 WebView 里已经把码发出去了（[smsSentByWebView]），
     * 这里就不再重复发——上游对同一号码有次数限制（`remainGetTimes`），
     * 多发一次就少一次，而且第二次大概率直接被风控挡掉。
     */
    override fun sendSmsCode(phone: String, ccode: String): String {
        val digits = normalizePhone(phone)
        if (smsSentByWebView) {
            smsSentByWebView = false
            hooks.onLog("移动云盘 验证码已由校验页发出（${maskPhone(digits)}）")
            // H5 的发码响应里没有 msgid 字段，上游登录时也不强校验，回空串即可
            return ""
        }
        val sent = appClient.sendSmsCode(digits, device())
        hooks.onLog("移动云盘 验证码已发送（${maskPhone(digits)}）")
        return sent.msgId
    }

    /**
     * 验证码登录。
     *
     * 先用 App 端国密接口换 Basic 凭证，再用它去会员中心换长期 apikey。
     * 两步都成功才算登录完成 —— 只拿到 Basic 而没换到 key 的账号是不可用的，
     * 保存下来只会让用户在「额度正常但发不出请求」上浪费时间。
     */
    override fun loginBySmsCode(phone: String, code: String, msgid: String, ccode: String): ProviderAccount {
        val digits = normalizePhone(phone)
        val login = try {
            appClient.loginBySmsCode(digits, code.trim(), msgid, device())
        } catch (e: Exception) {
            // 失败必须把细节打出来：上游对 thirdlogin 的拒绝常常是「加密方式不对」或
            // 「字段名不对」，光看一句「上游拒绝了本次请求」根本无从下手。
            hooks.onLog("移动云盘 登录失败（${maskPhone(digits)}）：${e.message}")
            if (e is MCloudApiException) {
                hooks.onLog("移动云盘 失败详情：${e.debugSummary()}")
            } else {
                hooks.onLog("移动云盘 失败异常：${e::class.simpleName}: ${e.stackTraceToString().take(600)}")
            }
            throw e
        }
        hooks.onLog("移动云盘 登录成功（${maskPhone(digits)}），正在换取调用密钥")
        val account = keyClient.fetchKey(
            LoginCredential(basic = login.basic, userId = login.userId, nickname = login.nickname),
            digits,
            nowMillis(),
        )
        return toProviderAccount(account)
    }

    /** 只支持大陆 11 位手机号；其它号码在本地就拦掉，别白跑一次上游。 */
    private fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        if (digits.length != 11) {
            throw IllegalStateException("移动云盘只支持中国大陆 11 位手机号")
        }
        return digits
    }

    /** 每个账号一个稳定设备号：上游按设备做风控，频繁变化会被判定异常。 */
    private fun device(): MCloudDevice = MCloudDevice(deviceId = deviceId)

    /** 设备号按 Provider 实例生成一次，进程内稳定。 */
    private val deviceId: String by lazy { MCloudDevice.random().deviceId }

    // ------------------------------------------------------------------ 导入

    override fun importCredentials(raw: String): ProviderAccount? {
        // 允许直接粘贴 `{phone, apiKey}` 或 `{apikey: "..."}`，方便用户把抓包到的 Key 直接塞进来
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val key = obj.str("apiKey").ifEmpty { obj.str("apikey") }
        if (key.isEmpty()) return null
        val phone = obj.str("phone")
        val account = MCloudAccount(
            uid = obj.str("uid").ifEmpty { phone.ifEmpty { "mcloud-${key.take(8)}" } },
            phone = phone,
            apiKey = key,
            modelUrl = obj.str("modelUrl").ifEmpty {
                "${MCloudConstants.AI_BASE}/api/llm/${MCloudConstants.CHANNEL_ID}${MCloudConstants.PATH_CHAT_COMPLETIONS}"
            },
            basicCredential = obj.str("basicCredential"),
            nickname = obj.str("nickname"),
            models = emptyList(),
            createdAtMillis = nowMillis(),
            basicExpiresAtMillis = if (obj.str("basicCredential").isNotEmpty()) {
                nowMillis() + MCloudAccount.BASIC_TTL_MILLIS
            } else {
                0L
            },
        )
        return toProviderAccount(account)
    }

    // ------------------------------------------------------------------ 内部

    private fun toProviderAccount(account: MCloudAccount): ProviderAccount = ProviderAccount(
        providerId = ID,
        uid = account.uid,
        nickname = account.nickname.ifEmpty { maskPhone(account.phone) },
        secret = account.toJson().toString(),
    )

    /** 没昵称时用脱敏手机号（138****8000）当展示名，总比一串 uid 好认。 */
    private fun maskPhone(phone: String): String =
        if (phone.length == 11) "${phone.take(3)}****${phone.takeLast(4)}" else phone

    internal fun parseOrNull(account: ProviderAccount): MCloudAccount? = MCloudAccount.parse(account.secret)

    private fun MCloudErrorKind.toErrorKind(): ErrorKind = when (this) {
        MCloudErrorKind.SESSION_DEAD -> ErrorKind.SESSION_DEAD
        MCloudErrorKind.QUOTA -> ErrorKind.QUOTA
        MCloudErrorKind.SOFT_RATE -> ErrorKind.SOFT_RATE
        MCloudErrorKind.CLIENT -> ErrorKind.CLIENT
        MCloudErrorKind.SERVER -> ErrorKind.SERVER
        MCloudErrorKind.NETWORK -> ErrorKind.NETWORK
    }

    companion object {
        const val ID = "mcloud"

        /** 消耗明细最大条数（与商汤积分流水保持一致）。 */
        private const val POINTS_BILLS_LIMIT = 20
    }
}