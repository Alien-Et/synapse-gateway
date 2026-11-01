package io.synapse.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.store.KeyValueStore
import io.synapse.core.util.arrayOrNull
import io.synapse.core.util.objOrNull

/** 网关的全局运行参数（与具体供应商无关）。 */
data class GatewaySettings(
    val port: Int = DEFAULT_PORT,
    /** 客户端调用网关时要带的 API Key；为空表示不校验。 */
    val apiKey: String = "",
    /** 「无 Key 调用」开关：允许客户端不填 Key 直连。 */
    val allowNoKey: Boolean = true,
    /**
     * 是否同时监听局域网（关闭则只绑 127.0.0.1）。
     *
     * 默认 false（仅本机）：局域网暴露会把网关端口开给同网段所有设备，
     * 而默认 allowNoKey=true 时任何设备都能直接调用，风险过大。
     * [fromJson] 用 [boolOr] 读旧配置：已存过值的用户（无论 true/false）保持原值，
     * 仅当字段缺失（老版本升级/首次运行）时才落到这个新默认。
     */
    val exposeLan: Boolean = false,
    /** 「只看可用模型」开关：隐藏上游的非对话内部条目。 */
    val onlyUsableModels: Boolean = false,
    /** token 预刷新窗口（秒）：临期多久内才刷新。 */
    val refreshSkewSeconds: Long = 300L,
    /** 单次请求最多换号次数。 */
    val maxRotate: Int = 3,
    /** 详细日志：记录每次调用的请求/转发/发送/返回全链路原文，用于排查 503 类问题；会增加存储占用。 */
    val verboseLogging: Boolean = false,
    /** 调用记录保留天数（1~3650）；服务运行时每日自动清理超过该天数的记录。 */
    val logRetentionDays: Int = DEFAULT_RETENTION_DAYS,
    /** 调用记录开关：关闭后不再写入每条调用的记录（含请求/响应原文），已存记录不受影响。 */
    val callLoggingEnabled: Boolean = true,
    /** 请求日志环形缓冲容量（条）。 */
    val requestLogCapacity: Int = DEFAULT_REQUEST_LOG_CAPACITY,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("port", port)
        addProperty("apiKey", apiKey)
        addProperty("allowNoKey", allowNoKey)
        addProperty("exposeLan", exposeLan)
        addProperty("onlyUsableModels", onlyUsableModels)
        addProperty("refreshSkewSeconds", refreshSkewSeconds)
        addProperty("maxRotate", maxRotate)
        addProperty("verboseLogging", verboseLogging)
        addProperty("logRetentionDays", logRetentionDays)
        addProperty("callLoggingEnabled", callLoggingEnabled)
        addProperty("requestLogCapacity", requestLogCapacity)
    }

    companion object {
        const val DEFAULT_PORT = 8790
        const val STORE_KEY = "settings/gateway.json"

        const val DEFAULT_RETENTION_DAYS = 30
        const val MIN_RETENTION_DAYS = 1
        const val MAX_RETENTION_DAYS = 3650

        const val DEFAULT_REQUEST_LOG_CAPACITY = 300
        const val MIN_REQUEST_LOG_CAPACITY = 50
        const val MAX_REQUEST_LOG_CAPACITY = 5000

        fun clampRequestLogCapacity(value: Int): Int =
            value.coerceIn(MIN_REQUEST_LOG_CAPACITY, MAX_REQUEST_LOG_CAPACITY)

        /**
         * 预刷新窗口上限（秒）。
         *
         * 窗口被当成「临期多久内刷新」真实使用后，过大的值会让 CodeBuddy / Raccoon 等
         * 每次请求都刷新，故存量配置里的 24h 也在读取时收敛到这个上限。
         */
        const val MAX_REFRESH_SKEW_SECONDS = 3600L

        fun fromJson(raw: String): GatewaySettings {
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return GatewaySettings()
            val defaults = GatewaySettings()
            return GatewaySettings(
                port = obj.intOr("port", defaults.port),
                apiKey = obj.strOr("apiKey", defaults.apiKey),
                allowNoKey = obj.boolOr("allowNoKey", defaults.allowNoKey),
                exposeLan = obj.boolOr("exposeLan", defaults.exposeLan),
                onlyUsableModels = obj.boolOr("onlyUsableModels", defaults.onlyUsableModels),
                refreshSkewSeconds = clampRefreshSkew(obj.longOr("refreshSkewSeconds", defaults.refreshSkewSeconds)),
                maxRotate = obj.intOr("maxRotate", defaults.maxRotate),
                verboseLogging = obj.boolOr("verboseLogging", defaults.verboseLogging),
                callLoggingEnabled = obj.boolOr("callLoggingEnabled", defaults.callLoggingEnabled),
                requestLogCapacity = clampRequestLogCapacity(obj.intOr("requestLogCapacity", defaults.requestLogCapacity)),
                logRetentionDays = clampRetentionDays(obj.intOr("logRetentionDays", defaults.logRetentionDays)),
            )
        }

        /** 保留天数超出范围时夹回合法区间，避免 0 天把记录全删光或天文数字永不清理。 */
        fun clampRetentionDays(days: Int): Int = days.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)

        /** 预刷新窗口夹回 [0, MAX_REFRESH_SKEW_SECONDS]，避免负值或超大值造成刷新风暴。 */
        fun clampRefreshSkew(seconds: Long): Long = seconds.coerceIn(0L, MAX_REFRESH_SKEW_SECONDS)
    }
}

/** 单个供应商的设置。 */
data class ProviderSettings(
    val enabled: Boolean = true,
    /** 该供应商的默认模型（模型名不带 `provider/` 前缀时用）。 */
    val defaultModel: String = "",
    /** 供应商私有设置（如 Trae 的客户端版本号），key/value 由各自 UI 组件维护。 */
    val options: Map<String, String> = emptyMap(),
) {
    fun option(key: String, fallback: String): String =
        options[key]?.takeIf { it.isNotEmpty() } ?: fallback

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("enabled", enabled)
        addProperty("defaultModel", defaultModel)
        val opts = JsonObject()
        for ((key, value) in options) opts.addProperty(key, value)
        add("options", opts)
    }

    companion object {
        fun fromJson(raw: String?): ProviderSettings {
            if (raw.isNullOrEmpty()) return ProviderSettings()
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return ProviderSettings()
            val options = LinkedHashMap<String, String>()
            obj.objOrNull("options")?.entrySet()?.forEach { (key, value) ->
                if (value.isJsonPrimitive) options[key] = value.asString
            }
            return ProviderSettings(
                enabled = obj.get("enabled")?.asBoolean ?: true,
                defaultModel = obj.get("defaultModel")?.asString.orEmpty(),
                options = options,
            )
        }
    }
}

/** 用户手动导入的 OpenAI 兼容供应商。 */
data class CustomProviderConfig(
    /** 稳定短名，作为 provider id 的后缀（`custom:<key>`）。 */
    val key: String,
    val name: String,
    val baseUrl: String,
    val apiKeys: List<String>,
    val protocol: String = PROTOCOL_OPENAI,
    /** 手填或从 `/v1/models` 拉取的模型名列表。 */
    val models: List<String> = emptyList(),
    val enabled: Boolean = true,
    /**
     * 额外请求头（按书写顺序发送，可多条同名）。
     *
     * 中转站/自建网关经常要求一些非标准头：`User-Agent`、`X-Api-Key`、
     * 租户号、渠道号等，光有 `Bearer` 进不去。这里让用户自己补，
     * 而不是逼他去改客户端或让我们硬编。
     *
     * **`Authorization` 由 API Key 自动生成**，用户如果也写了一条 `Authorization`，
     * 以用户写的为准（有些站点要求 `x-api-key` 而不是 `Bearer`，甚至有要求裸 Key 的）。
     * 这里只做覆盖、不做拼接——两个 Auth 头同时发出去，服务端多半直接 400。
     */
    val headers: List<HttpHeader> = emptyList(),
    /**
     * 余额查询的配置；为 null 表示这家不查余额。
     *
     * 各家查余额的路径、字段、包装结构都不一样，而且多半**不是** OpenAI 标准协议
     * （OpenAI 自己都没有余额接口）。所以这里做成一条可配置的 GET 请求 +
     * 一个「从 JSON 里挑出数字」的取值路径，而不是塞一堆供应商分支。
     */
    val balanceQuery: BalanceQueryConfig? = null,
) {
    val providerId: String get() = "custom:$key"

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("key", key)
        addProperty("name", name)
        addProperty("baseUrl", baseUrl)
        add("apiKeys", JsonArray().apply { apiKeys.forEach { add(it) } })
        addProperty("protocol", protocol)
        add("models", JsonArray().apply { models.forEach { add(it) } })
        addProperty("enabled", enabled)
        add("headers", JsonArray().apply { headers.forEach { add(it.toJson()) } })
        balanceQuery?.let { add("balanceQuery", it.toJson()) }
    }

    companion object {
        const val PROTOCOL_OPENAI = "openai"

        fun fromJson(obj: JsonObject): CustomProviderConfig? {
            val key = obj.get("key")?.asString.orEmpty()
            // 只卡 key，**不卡 baseUrl**。
            //
            // 导入账号时，本机还没有的自定义供应商会被补建一条「地址待填」的占位配置
            // （旧导出文件里根本没存地址）。这里要是连 baseUrl 一起卡，占位配置写下去
            // 立刻就被读丢了：账号导入 → 报「供应商没创建」→ 用户以为要手动先建 →
            // 可他就是因为没创建才要导入。死循环就在这一行。
            //
            // 地址留空不影响安全：真正发请求前的 openChat 会明确拒掉并提示补全。
            if (key.isEmpty()) return null
            val baseUrl = obj.get("baseUrl")?.asString.orEmpty()
            val keys = obj.arrayOrNull("apiKeys")?.mapNotNull { it.asString } ?: emptyList()
            val models = obj.arrayOrNull("models")?.mapNotNull { it.asString } ?: emptyList()
            return CustomProviderConfig(
                key = key,
                name = obj.get("name")?.asString.orEmpty().ifEmpty { key },
                baseUrl = baseUrl,
                apiKeys = keys,
                protocol = obj.get("protocol")?.asString.orEmpty().ifEmpty { PROTOCOL_OPENAI },
                models = models,
                enabled = obj.get("enabled")?.asBoolean ?: true,
                headers = obj.arrayOrNull("headers")
                    ?.mapNotNull { el ->
                        runCatching { HttpHeader.fromJson(el.asJsonObject) }.getOrNull()
                    }
                    .orEmpty(),
                balanceQuery = obj.objOrNull("balanceQuery")?.let { BalanceQueryConfig.fromJson(it) },
            )
        }
    }
}

/** 一条自定义请求头。 */
data class HttpHeader(
    val name: String,
    val value: String,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("name", name)
        addProperty("value", value)
    }

    companion object {
        /**
         * HTTP 头的名字只允许 token 字符（RFC 7230 field-name）。
         *
         * 不校验的后果不是「报错」而是「静默失效」：带空格或冒号的名字发出去，
         * 有些客户端会直接把这个头丢掉，用户看到的是「明明配了却不生效」。
         */
        private val NAME_PATTERN = Regex("^[!#$%&'*+\\-.^_`|~0-9A-Za-z]+$")

        fun isValidName(name: String): Boolean = NAME_PATTERN.matches(name)

        /** 值里不能有换行/回车：那是 HTTP 头注入（CRLF injection）的入口。 */
        fun isValidValue(value: String): Boolean = value.none { it == '\r' || it == '\n' }

        fun fromJson(obj: JsonObject): HttpHeader? {
            val name = obj.get("name")?.asString?.trim().orEmpty()
            if (name.isEmpty() || !isValidName(name)) return null
            val value = obj.get("value")?.asString.orEmpty()
            if (!isValidValue(value)) return null
            return HttpHeader(name, value)
        }
    }
}

/**
 * 自定义供应商的余额查询配置。
 *
 * 做法是「一条 GET + 一条取值路径」，而不是按供应商硬编：
 * 中转站的余额接口五花八门（`/api/user/self`、`/v1/credits`、`/dashboard/billing/...`），
 * 字段名也从 `balance` 到 `quota.data.remain` 都有。硬编只能覆盖已知的几家，
 * 让用户自己填两个字段反而能覆盖全部。
 */
data class BalanceQueryConfig(
    /** 完整地址；留空表示用供应商地址推导（baseUrl 去掉 `/v1` 后拼 [path]）。 */
    val url: String = "",
    /** [url] 为空时的相对路径，例如 `/api/user/self`。 */
    val path: String = "",
    /**
     * 取值路径，点分写法，如 `data.quota` / `data.remain`。
     *
     * 每一段都支持数组下标（`data.accounts.0.remain`）。为空表示直接取根节点
     * （响应本身就是个数字时用）。
     */
    val field: String = "balance",
    /**
     * 换算倍数：上游给的是「最小单位」（如某些站的 quota 是 500000 = $1），
     * 想显示成元/点数就填倍数。默认 1，表示原样显示。
     */
    val scale: Double = 1.0,
    /**
     * 上游数值是否要除以倍数而不是乘。
     *
     * 这里不猜：`quota=500000` 到底等于 1 元还是 500000 点，只有用户知道。
     */
    val divide: Boolean = false,
    /** 单位名（显示用），如「点」「元」。空串表示不显示单位。 */
    val unit: String = "",
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("url", url)
        addProperty("path", path)
        addProperty("field", field)
        addProperty("scale", scale)
        addProperty("divide", divide)
        addProperty("unit", unit)
    }

    companion object {
        fun fromJson(obj: JsonObject): BalanceQueryConfig? {
            val url = obj.get("url")?.asString.orEmpty().trim()
            val path = obj.get("path")?.asString.orEmpty().trim()
            val field = obj.get("field")?.asString.orEmpty().trim()
            // 三样全空 = 没配，别落一条空配置占地方
            if (url.isEmpty() && path.isEmpty() && field.isEmpty()) return null
            return BalanceQueryConfig(
                url = url,
                path = path,
                field = field.ifEmpty { "balance" },
                scale = obj.get("scale")?.takeIf { it.isJsonPrimitive }?.asDouble?.takeIf { it > 0 } ?: 1.0,
                divide = obj.get("divide")?.asBoolean ?: false,
                unit = obj.get("unit")?.asString.orEmpty(),
            )
        }
    }
}

/** 设置的读写：全局、各供应商、自定义供应商三类分开存。 */
class SettingsRepository(private val store: KeyValueStore) {

    fun load(): GatewaySettings {
        val raw = store.read(GatewaySettings.STORE_KEY) ?: return GatewaySettings()
        return GatewaySettings.fromJson(raw)
    }

    fun save(settings: GatewaySettings) {
        store.write(GatewaySettings.STORE_KEY, settings.toJson().toString())
    }

    fun loadProvider(id: String): ProviderSettings =
        ProviderSettings.fromJson(store.read(providerKey(id)))

    fun saveProvider(id: String, settings: ProviderSettings) {
        store.write(providerKey(id), settings.toJson().toString())
    }

    fun loadCustomProviders(): List<CustomProviderConfig> {
        val raw = store.read(CUSTOM_KEY) ?: return emptyList()
        val array = runCatching { JsonParser.parseString(raw).asJsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            CustomProviderConfig.fromJson(obj)
        }
    }

    fun saveCustomProviders(list: List<CustomProviderConfig>) {
        store.write(CUSTOM_KEY, JsonArray().apply { list.forEach { add(it.toJson()) } }.toString())
    }

    private fun providerKey(id: String) = "settings/provider/$id.json"

    private companion object {
        const val CUSTOM_KEY = "settings/custom.json"
    }
}

private fun JsonObject.strOr(key: String, fallback: String): String {
    val v = get(key) ?: return fallback
    return if (v.isJsonNull) fallback else runCatching { v.asString }.getOrDefault(fallback)
}

private fun JsonObject.intOr(key: String, fallback: Int): Int {
    val v = get(key) ?: return fallback
    if (v.isJsonNull) return fallback
    return runCatching { v.asInt }.getOrDefault(fallback)
}

private fun JsonObject.longOr(key: String, fallback: Long): Long {
    val v = get(key) ?: return fallback
    if (v.isJsonNull) return fallback
    return runCatching { v.asLong }.getOrDefault(fallback)
}

private fun JsonObject.boolOr(key: String, fallback: Boolean): Boolean {
    val v = get(key) ?: return fallback
    if (v.isJsonNull) return fallback
    return runCatching { v.asBoolean }.getOrDefault(fallback)
}
