package io.synapse.core.gateway

/**
 * 常见大厂/中转站的**余额接口预设**。
 *
 * 以前接入余额要用户自己填「地址 + 点分取值路径 + 倍数」，得先去翻该家的文档；
 * 预设把这些已知形状收进来，编辑页选一个就自动补全，用户只填 API Key。
 *
 * 这里**只记录公开的接口形状**（路径、字段名、HTTP 方法），不含任何密钥或私有协议。
 * 各家可能改版，所以预设始终可以被用户手动覆盖——[BalanceQueryConfig] 的其它字段
 * 优先级更高，预设只负责「填空」而不是「锁死」。
 */
object BalancePresets {

    data class Preset(
        val id: String,
        val label: String,
        /** 相对 baseUrl 的路径（不含 baseUrl 本身）。 */
        val path: String,
        val field: String,
        val method: String = "GET",
        val body: String = "",
        val scale: Double = 1.0,
        val divide: Boolean = false,
        val unit: String = "",
        val hint: String,
    )

    /** OpenAI 官方没有余额接口，这两个是下游客户端（RikkaHub 等）约定的事实标准路径。 */
    val OPENAI_SUBSCRIPTION = Preset(
        id = "openai_subscription",
        label = "OpenAI 订阅总额度",
        path = "/v1/dashboard/billing/subscription",
        field = "hard_limit_usd",
        unit = "$",
        hint = "官方 billing/subscription，取 hard_limit_usd",
    )

    val OPENAI_GRANTS = Preset(
        id = "openai_grants",
        label = "OpenAI 剩余额度",
        path = "/v1/dashboard/billing/credit_grants",
        field = "total_available",
        unit = "$",
        hint = "官方 credit_grants，取 total_available",
    )

    /** New API / One API / 各类中转站最通用的一条。 */
    val NEW_API = Preset(
        id = "new_api",
        label = "New API / 中转站",
        path = "/api/user/self",
        field = "data.quota",
        // 中转站 quota 常是「积分最小单位」，500000 = $1；除以 500000 才是美元
        scale = 500_000.0,
        divide = true,
        unit = "$",
        hint = "One API / New API 系：data.quota，按 500000 折算",
    )

    val NEW_API_STATUS = Preset(
        id = "new_api_status",
        label = "New API /api/status",
        path = "/api/status",
        field = "data.quota",
        hint = "部分中转站把余额挂在 /api/status",
    )

    val DEEPSEEK = Preset(
        id = "deepseek",
        label = "DeepSeek 官方",
        path = "/user/balance",
        field = "balance_infos.0.total_balance",
        unit = "元",
        hint = "取 balance_infos[0].total_balance",
    )

    val MOONSHOT = Preset(
        id = "moonshot",
        label = "Moonshot Kimi",
        path = "/v1/users/me/balance",
        field = "data.available_balance",
        unit = "元",
        hint = "Kimi 官方余额接口",
    )

    val ZHIPU = Preset(
        id = "zhipu",
        label = "智谱 GLM",
        path = "/api/paas/v4/account/balance",
        field = "data.balance",
        hint = "智谱开放平台账户余额",
    )

    val DASHSCOPE = Preset(
        id = "dashscope",
        label = "阿里云百炼",
        path = "/api/v1/account/balance",
        field = "data.remaining_quota",
        hint = "百炼账户剩余额度",
    )

    val SILICONFLOW = Preset(
        id = "siliconflow",
        label = "硅基流动",
        path = "/v1/user/info",
        field = "data.balance",
        unit = "元",
        hint = "SiliconCloud /v1/user/info",
    )

    /** 有的中转站余额就挂在 baseUrl 根上，只要给字段路径。 */
    val ROOT_JSON = Preset(
        id = "root_json",
        label = "根路径 JSON（自定义字段）",
        path = "",
        field = "balance",
        hint = "baseUrl 直接回余额 JSON；字段可在下面改",
    )

    /** 响应体本身就是一个数字，或整段文本当数字解析。 */
    val RAW_NUMBER = Preset(
        id = "raw_number",
        label = "响应就是数字",
        path = "",
        field = "",
        hint = "接口直接返回一个数字，不需要取字段",
    )

    val ALL: List<Preset> = listOf(
        NEW_API,
        NEW_API_STATUS,
        OPENAI_SUBSCRIPTION,
        OPENAI_GRANTS,
        DEEPSEEK,
        MOONSHOT,
        ZHIPU,
        DASHSCOPE,
        SILICONFLOW,
        ROOT_JSON,
        RAW_NUMBER,
    )

    fun of(id: String): Preset? = ALL.firstOrNull { it.id == id }

    /** 给编辑页下拉框用：id → 名称。 */
    fun choices(): List<Pair<String, String>> = listOf("" to "自定义") + ALL.map { it.id to it.label }

    /**
     * 把预设「填进」用户配置：只补用户没填的项。
     *
     * 用户手改过的字段优先——预设是省事的起点，不是锁死的规定。
     */
    fun apply(config: BalanceQueryConfig): BalanceQueryConfig {
        val preset = of(config.preset) ?: return config
        return config.copy(
            path = config.path.ifEmpty { preset.path },
            field = config.field.ifEmpty { preset.field }.ifEmpty { "balance" },
            method = if (config.method == "GET") preset.method else config.method,
            body = config.body.ifEmpty { preset.body },
            scale = if (config.scale == 1.0) preset.scale else config.scale,
            divide = config.divide || preset.divide,
            unit = config.unit.ifEmpty { preset.unit },
        )
    }
}
