package io.synapse.core.provider

/**
 * 网关对外暴露的全部 OpenAI 兼容端点。
 *
 * 集中声明的好处:新增一个端点只改这里一处,网关路由、Provider 能力声明、
 * 界面上的「支持哪些接口」全都从这里取,不会出现「路由加了但 provider 不知道自己支持」。
 *
 * ## 分两类
 *
 * - [EndpointKind.CHAT]:走完整的选号 + 容灾 + 流式透传链路(openChat)。
 * - [EndpointKind.PASSTHROUGH]:由 provider 的 passthrough() 原样转发。
 *
 * ## 为什么生图/语音/向量不做容灾轮换
 *
 * 它们没有「换个号重试」的意义:图片生成一次就扣一次费,失败了换号重试会出现
 * 「同一张图生成两遍、扣两次钱」。所以这些端点只选一个可用账号发出去,
 * 失败就如实报错,不自动重试。
 */
enum class EndpointKind {
    /** 对话:选号 + 容灾 + 流式透传。 */
    CHAT,

    /** 其它:选一个可用账号,原样转发,不重试。 */
    PASSTHROUGH,
}

/**
 * 一个对外端点。
 *
 * @property path 客户端调用的路径(不含 baseUrl),如 `/v1/images/generations`
 * @property kind 走哪条链路
 * @property label 界面/日志上的人话名字
 * @property aliases 别名路径:各家客户端写法不同,全都认才能「配好 baseUrl 就能用」
 * @property binaryResponse 是否可能返回二进制/流(如语音合成返回 audio/mpeg)
 */
data class ApiEndpoint(
    val path: String,
    val kind: EndpointKind,
    val label: String,
    val aliases: List<String> = emptyList(),
    val binaryResponse: Boolean = false,
) {
    /** 这个端点所有可接受的路径写法。 */
    val allPaths: List<String> get() = listOf(path) + aliases
}

object ApiEndpoints {

    // ---------------------------------------------------------------- 对话

    val CHAT = ApiEndpoint(
        path = "/v1/chat/completions",
        kind = EndpointKind.CHAT,
        label = "对话",
        aliases = listOf("/chat/completions"),
    )

    // ---------------------------------------------------------------- 生图

    val IMAGES_GENERATIONS = ApiEndpoint(
        path = "/v1/images/generations",
        kind = EndpointKind.PASSTHROUGH,
        label = "文生图",
        aliases = listOf("/images/generations"),
    )

    val IMAGES_EDITS = ApiEndpoint(
        path = "/v1/images/edits",
        kind = EndpointKind.PASSTHROUGH,
        label = "图片编辑",
        aliases = listOf("/images/edits"),
    )

    val IMAGES_VARIATIONS = ApiEndpoint(
        path = "/v1/images/variations",
        kind = EndpointKind.PASSTHROUGH,
        label = "图片变体",
        aliases = listOf("/images/variations"),
    )

    // ---------------------------------------------------------------- 语音

    val AUDIO_TRANSCRIPTIONS = ApiEndpoint(
        path = "/v1/audio/transcriptions",
        kind = EndpointKind.PASSTHROUGH,
        label = "语音转文字",
        aliases = listOf("/audio/transcriptions"),
    )

    val AUDIO_TRANSLATIONS = ApiEndpoint(
        path = "/v1/audio/translations",
        kind = EndpointKind.PASSTHROUGH,
        label = "语音翻译",
        aliases = listOf("/audio/translations"),
    )

    val AUDIO_SPEECH = ApiEndpoint(
        path = "/v1/audio/speech",
        kind = EndpointKind.PASSTHROUGH,
        label = "语音合成",
        aliases = listOf("/audio/speech"),
        binaryResponse = true,
    )

    // ---------------------------------------------------------------- 向量与重排

    val EMBEDDINGS = ApiEndpoint(
        path = "/v1/embeddings",
        kind = EndpointKind.PASSTHROUGH,
        label = "文本向量",
        aliases = listOf("/embeddings"),
    )

    val RERANK = ApiEndpoint(
        path = "/v1/rerank",
        kind = EndpointKind.PASSTHROUGH,
        label = "重排",
        // 各家重排接口路径不统一:Jina/Cohere 用 /rerank,部分中转站挂 /v1/rerank,
        // 智谱用 /paas/v4/rerank。三种都认,用户不用改 baseUrl 就能试。
        aliases = listOf("/rerank", "/v1/paas/v4/rerank", "/paas/v4/rerank"),
    )

    // ---------------------------------------------------------------- 内容审核

    val MODERATIONS = ApiEndpoint(
        path = "/v1/moderations",
        kind = EndpointKind.PASSTHROUGH,
        label = "内容审核",
        aliases = listOf("/moderations"),
    )

    // ---------------------------------------------------------------- 视频

    val VIDEO_GENERATIONS = ApiEndpoint(
        path = "/v1/videos/generations",
        kind = EndpointKind.PASSTHROUGH,
        label = "文生视频",
        // 可灵 / 即梦 / Seedance 这类国内视频模型常见路径
        aliases = listOf("/videos/generations", "/v1/video/generations", "/video/generations"),
    )

    // ---------------------------------------------------------------- 元信息

    val MODELS = ApiEndpoint(
        path = "/v1/models",
        kind = EndpointKind.PASSTHROUGH,
        label = "模型列表",
        aliases = listOf("/models"),
    )

    /** 所有透传类端点(不含对话)。 */
    val PASSTHROUGH: List<ApiEndpoint> = listOf(
        IMAGES_GENERATIONS,
        IMAGES_EDITS,
        IMAGES_VARIATIONS,
        AUDIO_TRANSCRIPTIONS,
        AUDIO_TRANSLATIONS,
        AUDIO_SPEECH,
        EMBEDDINGS,
        RERANK,
        MODERATIONS,
        VIDEO_GENERATIONS,
        MODELS,
    )

    /**
     * 全部端点(对话 + 透传)。
     *
     * MODELS 也在这里:网关侧 /v1/models 有自己的实现(要返回开放名单、补上路由前缀),
     * 所以路由时它会先被专门分支截住,不会走到通用透传。但 provider 声明能力时仍需要它。
     */
    val ALL: List<ApiEndpoint> = listOf(CHAT) + PASSTHROUGH

    /** 按路径查端点(含别名);未知名返回 null。 */
    fun match(path: String): ApiEndpoint? {
        val normalized = path.trim().trimEnd('/').ifEmpty { "/" }
        return ALL.firstOrNull { endpoint ->
            endpoint.allPaths.any { it.equals(normalized, ignoreCase = true) }
        }
    }

    /** 该路径是否是需要走容灾的对话端点。 */
    fun isChat(path: String): Boolean = match(path)?.kind == EndpointKind.CHAT

    /** 端点的展示名;未知路径原样返回。 */
    fun labelOf(path: String): String = match(path)?.label ?: path
}