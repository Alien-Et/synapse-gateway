package io.synapse.core.provider

import com.google.gson.JsonObject

/** 登录形态，UI 据此渲染不同的登录入口。 */
enum class AuthKind {
    /** WebView 打开授权页，回调到网关端口（Trae）。 */
    WEBVIEW_CALLBACK,

    /** 设备授权：WebView 打开授权页 + 轮询换取 token（WorkBuddy / CodeBuddy）。 */
    DEVICE_CODE,

    /** 手机号 + 短信验证码（Loomy）。 */
    SMS_CODE,

    /** 标准 OAuth loopback：WebView 拦截回调 URL 拿 code（Antigravity）。 */
    OAUTH_LOOPBACK,

    /** 无需登录，凭证即 API Key（自定义供应商）。 */
    NONE,
}

/** provider 支持的额外动作，UI 据此决定展示哪些按钮。 */
enum class ProviderCapability {
    /** 可刷新账号额度/积分。 */
    CREDIT_REFRESH,

    /** 支持每日签到。 */
    CHECKIN,

    /** 有可批量执行的「任务」（如 Loomy 的新手任务）。 */
    TASKS,

    /** 任务不只是接取/领奖，还能**自动完成**（如 WorkBuddy 成长中心：按埋点配方推进进度后再领奖）。 */
    TASK_AUTO_COMPLETE,

    /** 有积分/额度流水账本，可查逐笔明细（如小浣熊的积分流水）。 */
    POINTS_BILLS,
}

/** 一次鉴权探测的结论。 */
enum class CredentialVerdict {
    /** 凭证可用（能读到额度，或鉴权端点明确通过）。 */
    VALID,

    /** 凭证确实失效（失效措辞/401/403）：只能重新登录。 */
    INVALID,

    /** 该供应商没有可用的鉴权探测端点（如纯 Cookie 鉴权的豆包）。 */
    UNSUPPORTED,

    /** 无法确认（网络错误、超时、上游 5xx）：既不能说好也不能说坏。 */
    UNKNOWN,
}

/**
 * 从一次额度查询的结果判断凭证状态。
 *
 * 判据只有两类，且都有意保守：
 * - `known == true` → 鉴权通过（能读到真实额度，说明凭证被上游接受了）；
 * - 失败说明里出现**明确的失效措辞** → 判定失效。
 *
 * 网络错误、5xx、看不懂的报错一律算 [CredentialVerdict.UNKNOWN]：
 * 一次超时不该把账号重新判死，更不该把坏凭证当成好的放回去。
 */
fun judgeCredentialVerdict(info: CreditInfo): CredentialVerdict {
    if (info.known) return CredentialVerdict.VALID
    val text = "${info.detail} ${info.actionHint}".lowercase()
    return if (INVALID_MARKERS.any { text.contains(it) }) CredentialVerdict.INVALID
    else CredentialVerdict.UNKNOWN
}

/** 明确的失效措辞。只认「登录/session/token 失效」这类确定表述，不认裸状态码片段。 */
private val INVALID_MARKERS = listOf(
    "登录已失效",
    "凭证已失效",
    "凭证失效",
    "未登录",
    "未授权",
    "token 已失效",
    "token已失效",
    "session expired",
    "not logged in",
    "unauthorized",
    "http 401",
    "http 403",
)

/** 供应商专属动作（provider 实现 [Provider.performAction]，UI 组件按名字调用）。 */

/** 每日签到；支持的 provider 与 UI 都按这个名字调用。 */
const val ACTION_CHECKIN = "checkin"

/**
 * 账号自带的任务/成长中心一键领取。
 *
 * 各家含义不同，由 provider 自己解释：Loomy = 新手任务；WorkBuddy = 成长中心（任务+旅行+盲盒）。
 */
const val ACTION_TASKS = "tasks"

/**
 * 自动完成账号的任务（而不只是接取与领奖）。
 *
 * WorkBuddy 的实现：按官方客户端的行为逐条推进成长任务（上报埋点事件 / 发真实对话），
 * 再统一接单、回读进度、领奖。不具备该能力的供应商保持 [ACTION_TASKS] 的原行为。
 */
const val ACTION_COMPLETE_TASKS = "complete_tasks"

/**
 * 一个额度包/账本条目。
 *
 * 字段缺失一律用 0（金额类）或空串（文本类），不要编造。
 */
data class QuotaPack(
    val name: String,
    /** 来源分组（如「每日签到」「团队」）。 */
    val group: String = "",
    val limit: Long = 0,
    val used: Long = 0,
    val remain: Long = 0,
    /** 过期时间（秒，0 表示不过期/未知）。 */
    val expireAt: Long = 0,
    /**
     * 仅限某些模型使用的独立配额（如 Gemini 4 Argon 的周额度）；空串表示全账号共享。
     * 单独列出是为了不让用户把「某个模型用完」误认为「整个账号已耗尽」。
     */
    val scope: String = "",
)

/**
 * 一个上游供应商。
 *
 * 抽象边界刻意定在「OpenAI 请求体 → OpenAI 格式响应」：[openChat] 接收标准 OpenAI 请求体，
 * 内部完成与上游私有协议的**双向**转换，返回的 [ChatCall] 已是 OpenAI 语义
 * （流式 SSE 或聚合后的 completion）。因此网关层（选号、容灾调度、透传）对 provider 差异零感知，
 * 新增供应商只需实现本接口并在 [ProviderRegistry] 登记一行。
 */
interface Provider {
    /** 稳定标识，也是模型名的前缀（如 `trae/Seed-2.1-Code`）。 */
    val id: String

    /** 界面展示名。 */
    val displayName: String

    val authKind: AuthKind

    val capabilities: Set<ProviderCapability> get() = emptySet()

    /** 模型目录；无账号或拉取失败时返回空列表。 */
    fun listModels(account: ProviderAccount?): ProviderModelCatalogView

    /**
     * 丢弃模型目录缓存，下次 [listModels] 必须重新打上游（界面刷新按钮用）。
     *
     * 实现方应保留最后一次成功结果作为失败兜底：本方法只保证不再命中缓存，
     * 不保证上游此刻可用。
     */
    fun invalidateModelCache() = Unit

    /** 把客户端传来的模型名归一化为上游真实名；查不到原样放行，交给上游判定。 */
    fun resolveModel(requested: String): String

    /** 是否是「非对话用途」的内部条目（开启「只看可用模型」时隐藏）。 */
    fun isInternalModel(id: String): Boolean = false

    /** 发起一次对话。 */
    fun openChat(account: ProviderAccount, openAiBody: String): ChatCall

    /**
     * 该供应商支持的**非对话端点**（OpenAI 兼容路径，如 `/v1/images/generations`）。
     *
     * 网关收到这些路径的请求时会按模型名选号，再调 [passthrough] 原样转发。
     * 默认空集 = 只支持对话，其它路径直接报「该供应商不支持」。
     *
     * 为什么用「声明路径 + 一个通用转发」而不是给每个端点写一个方法：
     * 生图/语音/向量/重排这些端点的**转发逻辑完全相同**（拼 URL、带 Key、透传
     * body 与响应），差别只在路径。写十几个方法等于把同一段代码抄十几遍，
     * 新增端点还要改接口。
     */
    val passthroughPaths: Set<String> get() = emptySet()

    /**
     * 把一次非对话请求原样转发给上游。
     *
     * [path] 是完整的 OpenAI 风格路径（如 `/v1/images/generations`，已去掉 baseUrl 前缀）；
     * [body] 是客户端原始请求体（JSON 字符串，可能为空——GET 类端点）；
     * [method] 是 HTTP 方法。
     *
     * 默认返回 null 表示**不支持**——调用方据此给用户一句明确的「该供应商不支持该接口」，
     * 而不是把请求打到错误的端点上换回一句看不懂的 400。
     *
     * 返回的 [ChatCall] 语义与对话一致：非 2xx 时 [ChatCall.errorBody] 是上游错误原文，
     * 2xx 时 [ChatCall.aggregated] 是**原始响应体**（不解析、不改写，包括图片的二进制
     * base64 字段）；流式端点（如语音合成）用 [ChatCall.stream]。
     */
    fun passthrough(
        account: ProviderAccount,
        method: String,
        path: String,
        body: String?,
    ): ChatCall? = null

    /** 预刷新凭证；返回新账号表示 token 发生轮换（调用方需原子写回）。 */
    fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? = null

    /**
     * 修正历史遗留的账号标识（uid 口径变过时自愈）；null 表示无需迁移。
     *
     * 引擎启动后会给每个账号问一次：返回的账号若 uid 变了，就按新 uid 重新登记、删掉旧条目，
     * 凭证与账号状态都保留。实现方只在**能确定**旧值确实是旧口径产物时才返回，拿不准就返回 null——
     * 宁可留一条等用户手动处理的旧账号，也不去动用户的凭证。
     */
    fun migrateAccount(account: ProviderAccount): ProviderAccount? = null

    /** 统一错误分类，供调度层决定重试与换号策略。 */
    fun classify(status: Int, body: String): UpstreamError

    /** 账号额度/积分；无此能力返回 null。 */
    fun creditInfo(account: ProviderAccount): CreditInfo? = null

    /**
     * 鉴权探测：这份凭证现在**还能不能用**。
     *
     * 只在一个低频场景被调用：用户手动打开一个「凭证失效」账号的开关时，先确认它是不是
     * 真的失效了 —— 避免用户明明还能用、却因为一次瞬时故障被一直挡在门外。
     *
     * 默认实现走 [creditInfo]：能读到额度就说明鉴权通过（额度端点和对话端点共用同一套凭证）。
     * 读不到时再看失败说明里有没有明确的失效措辞；网络错误一律算「无法确认」，
     * 绝不因为一次超时就把账号重新判死（也绝不把坏凭证误判成好的）。
     */
    fun verifyCredential(account: ProviderAccount): CredentialVerdict {
        val info = creditInfo(account) ?: return CredentialVerdict.UNSUPPORTED
        return judgeCredentialVerdict(info)
    }


    /** 额度包明细（可选）；没有额度包概念的 provider 返回空列表。 */
    fun creditPacks(account: ProviderAccount): List<QuotaPack> = emptyList()

    /**
     * 成长任务明细（可选）；不支持任务中心的 provider 返回 null。
     *
     * 与 [performAction] 的 `ACTION_TASKS` 不同：这里只**读取**任务列表与完成度，
     * 不发任何写操作，供 UI 展示「有哪些任务、哪些做了、哪些没做」。
     */
    fun taskList(account: ProviderAccount): ProviderTaskListView? = null

    /**
     * 积分/额度流水明细（可选）；不支持账本的 provider 返回 null。
     *
     * 与 [creditPacks] 的分工：后者是「还剩多少」的额度包视角，这里是「每一笔怎么来的」流水视角。
     */
    fun pointsBills(account: ProviderAccount): PointsBillListView? = null

    /**
     * 该供应商用到的上游域名后缀（不带子域前缀）。
     *
     * 代理设置按「供应商」开关，实际判定就是拿目标域名与这里的后缀匹配，
     * 所以新增供应商时要把它的上游域名补上，否则代理开关对它无效。
     */
    fun hosts(): List<String> = emptyList()

    /** provider 专属动作（签到、兑换码等）。默认不支持。 */
    fun performAction(account: ProviderAccount, action: String, payload: JsonObject): ProviderActionResult =
        ProviderActionResult.unsupported(action)

    /**
     * 这个账号是否适用该动作。默认适用。
     *
     * 用于批量动作选目标：区域型供应商的**国际版本来就没有签到/成长中心**，
     * 应该在选目标时就把它排掉，而不是批量跑完了才逐个报「国际版没有⋯」——
     * 那种报错既无用又刷屏。
     */
    fun supportsAction(account: ProviderAccount, action: String): Boolean = true

    /** 粘贴 JSON 凭证导入；不支持则返回 null。 */
    fun importCredentials(raw: String): ProviderAccount? = null
}

/**
 * 账号有区域之分的供应商（如 WorkBuddy 的国内 cn / 国外 global）。
 *
 * 区域型供应商在模型页拆成多个独立前缀（`codebuddy-cn`、`codebuddy-global`），
 * 网关选号时只取指定区域的账号；实现方负责判断单个账号属于哪个区域。
 */
interface RegionAwareSupport {
    /** 判断一个账号属于哪个区域（如 cn/global）；无法判定时返回 null。 */
    fun regionOf(account: ProviderAccount): String?

    /** 该供应商支持的区域列表（如 [cn, global]）。 */
    fun regions(): List<String>
}
