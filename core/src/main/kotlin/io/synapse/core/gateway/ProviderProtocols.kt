package io.synapse.core.gateway

/**
 * 自定义供应商支持的**上游协议**。
 *
 * ## 为什么需要它
 *
 * `protocol` 字段以前存了但完全没用——所有自定义供应商都被当成标准 OpenAI，
 * 于是接智谱/Anthropic/Gemini/ Cohere 这类「非完全兼容」的大厂时，用户只能靠
 * 手填请求头、手改 baseUrl Suffix 去凑，凑错了还不知道错在哪。
 *
 * 现在协议决定三件事：
 * 1. **端点路径**（`chat/completions` vs `messages` vs `:generateContent`）
 * 2. **鉴权头**（`Authorization: Bearer` vs `x-api-key` vs `?key=`）
 * 3. **默认 baseUrl**（用户填域名就能用，不必去查该家的完整路径）
 *
 * 其余（请求体、响应体转换）仍由 [io.synapse.core.provider.custom.CustomProvider]
 * 统一按 OpenAI 语义处理：这些大厂的「OpenAI 兼容层」现在都很完善，
 * 差别主要就在 URL 和鉴权，包一层完整的双向协议转换反而更容易出错。
 */
data class ProviderProtocol(
    val id: String,
    val label: String,
    /** 对话端点的相对路径（相对 baseUrl 根）。 */
    val chatPath: String,
    /** 模型列表端点；空串表示该协议没有这个接口。 */
    val modelsPath: String,
    /** 鉴权方式。 */
    val auth: ProtocolAuth,
    /** 用户只填域名时用的默认补全。 */
    val defaultBaseUrl: String,
    /** 该协议额外支持的端点路径（会并入 provider 的 passthroughPaths）。 */
    val extraPaths: List<String> = emptyList(),
    /** 给用户看的一句说明（编辑页下拉框的副标题）。 */
    val hint: String,
)

/** 鉴权方式：决定 API Key 怎么放进请求里。 */
enum class ProtocolAuth {
    /** 标准 `Authorization: Bearer <key>`。 */
    BEARER,

    /** `x-api-key: <key>`（Anthropic 原生）。 */
    X_API_KEY,

    /** 查询参数 `?key=<key>`（Gemini 原生）。 */
    QUERY_KEY,
}

object ProviderProtocols {

    val OPENAI = ProviderProtocol(
        id = "openai",
        label = "OpenAI 兼容",
        chatPath = "/v1/chat/completions",
        modelsPath = "/v1/models",
        auth = ProtocolAuth.BEARER,
        defaultBaseUrl = "https://api.openai.com/v1",
        hint = "绝大多数中转站、自建网关、DeepSeek / Moonshot / 通义兼容端点",
    )

    val ANTHROPIC = ProviderProtocol(
        id = "anthropic",
        label = "Anthropic Claude",
        chatPath = "/v1/messages",
        modelsPath = "/v1/models",
        auth = ProtocolAuth.X_API_KEY,
        defaultBaseUrl = "https://api.anthropic.com/v1",
        hint = "Claude 原生 Messages API；需要 x-api-key 与 anthropic-version 头",
    )

    val GEMINI = ProviderProtocol(
        id = "gemini",
        label = "Google Gemini",
        chatPath = "/v1beta/models/{model}:generateContent",
        modelsPath = "/v1beta/models",
        auth = ProtocolAuth.QUERY_KEY,
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        hint = "Gemini 原生 generateContent；Key 走 ?key= 查询参数",
    )

    val ZHIPU = ProviderProtocol(
        id = "zhipu",
        label = "智谱 GLM",
        chatPath = "/api/paas/v4/chat/completions",
        modelsPath = "",
        auth = ProtocolAuth.BEARER,
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        extraPaths = listOf("/api/paas/v4/embeddings", "/api/paas/v4/images/generations"),
        hint = "智谱开放平台；同时支持向量与文生图 CogView",
    )

    val DASHSCOPE = ProviderProtocol(
        id = "dashscope",
        label = "阿里云百炼",
        chatPath = "/compatible-mode/v1/chat/completions",
        modelsPath = "/compatible-mode/v1/models",
        auth = ProtocolAuth.BEARER,
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        extraPaths = listOf("/compatible-mode/v1/embeddings", "/api/v1/services/aigc/text2image/image-synthesis"),
        hint = "通义千问兼容模式；生图走独立的异步任务接口",
    )

    val VOLCENGINE = ProviderProtocol(
        id = "volcengine",
        label = "火山方舟",
        chatPath = "/api/v3/chat/completions",
        modelsPath = "/api/v3/models",
        auth = ProtocolAuth.BEARER,
        defaultBaseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        extraPaths = listOf("/api/v3/images/generations", "/api/v3/embeddings"),
        hint = "豆包 / Seed 系列；生图用 seedream 模型",
    )

    val COHERE = ProviderProtocol(
        id = "cohere",
        label = "Cohere",
        chatPath = "/v1/chat",
        modelsPath = "",
        auth = ProtocolAuth.BEARER,
        defaultBaseUrl = "https://api.cohere.com/v1",
        extraPaths = listOf("/v1/rerank", "/v1/embed"),
        hint = "Command 系列；以重排（rerank）与向量见长",
    )

    val DEEPSEEK = ProviderProtocol(
        id = "deepseek",
        label = "DeepSeek",
        chatPath = "/chat/completions",
        modelsPath = "/models",
        auth = ProtocolAuth.BEARER,
        defaultBaseUrl = "https://api.deepseek.com",
        hint = "DeepSeek 官方；baseUrl 已含 /v1 时自动去重",
    )

    val MOONSHOT = ProviderProtocol(
        id = "moonshot",
        label = "Moonshot Kimi",
        chatPath = "/v1/chat/completions",
        modelsPath = "/v1/models",
        auth = ProtocolAuth.BEARER,
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        hint = "Kimi 系列，OpenAI 兼容",
    )

    val ALL: List<ProviderProtocol> = listOf(
        OPENAI,
        ANTHROPIC,
        GEMINI,
        ZHIPU,
        DASHSCOPE,
        VOLCENGINE,
        COHERE,
        DEEPSEEK,
        MOONSHOT,
    )

    /** 按 id 查协议；未知 id 一律回退到 OpenAI（老配置里可能存了任意字符串）。 */
    fun of(id: String): ProviderProtocol =
        ALL.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OPENAI

    /**
     * 把用户填的 Base URL 和协议路径拼成最终地址。
     *
     * 中转站和官方文档对 Base URL 的写法不统一，三种都要拼对，且**不能先把 `/v1` 剥掉**：
     *
     * - `https://x.com` + `/v1/chat/completions` → `https://x.com/v1/chat/completions`
     * - `https://x.com/v1` + `/v1/chat/completions` → `https://x.com/v1/chat/completions`（重叠的 `/v1` 只留一次）
     * - `https://x.com/v1` + `/chat/completions` → `https://x.com/v1/chat/completions`（用户写的版本段保留）
     * - `https://open.bigmodel.cn/api/paas/v4` + `/api/paas/v4/chat/completions` → 不重复版本段
     *
     * 以前先 `removeSuffix("/v1")` 再贴协议路径：模型列表走的是不带协议的 `/v1/models`，能拉到；
     * 对话却被拼到 `/chat/completions` 或 `/api/paas/v4/api/paas/v4/...`，上游回
     * “The requested endpoint was not found”。模型 id 本身没问题，换到别的客户端就能用。
     */
    fun joinBaseAndPath(baseUrl: String, path: String): String {
        var base = baseUrl.trim().trimEnd('/')
        var normalized = path.trim()
        if (normalized.isEmpty()) return base
        if (!normalized.startsWith("/")) normalized = "/$normalized"
        if (base.isEmpty()) return normalized
        if (base.endsWith(normalized, ignoreCase = true)) return base
        // 用户有时把完整端点粘进 Base URL（.../chat/completions、.../models）。先剥掉再按目标路径重拼。
        val endpointTails = listOf(
            "/chat/completions",
            "/completions",
            "/messages",
            "/models",
            ":streamGenerateContent",
            ":generateContent",
        )
        for (tail in endpointTails) {
            if (base.endsWith(tail, ignoreCase = true)) {
                base = base.dropLast(tail.length).trimEnd('/')
                break
            }
        }
        if (base.endsWith(normalized, ignoreCase = true)) return base
        val parts = normalized.trim('/').split('/').filter { it.isNotEmpty() }
        var consumed = 0
        for (k in parts.size downTo 1) {
            val prefix = "/" + parts.take(k).joinToString("/")
            if (base.endsWith(prefix, ignoreCase = true)) {
                consumed = k
                break
            }
        }
        val rest = parts.drop(consumed)
        return if (rest.isEmpty()) base else base + "/" + rest.joinToString("/")
    }

    /** 给用户看的协议选项（id → 名字）。 */
    fun choices(): List<Pair<String, String>> = ALL.map { it.id to it.label }
}
