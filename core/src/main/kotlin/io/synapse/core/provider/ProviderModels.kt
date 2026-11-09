package io.synapse.core.provider

/** 统一模型条目。 */
data class ProviderModel(
    val id: String,
    val name: String,
    val contextWindow: Long = 0,
    /** provider 私有的附加信息（Trae 的倍率说明、Loomy 的能力位等），由各自 UI 组件解读。 */
    val extra: Map<String, String> = emptyMap(),
) {
    /**
     * 这个模型是不是**生成图片**的（而不是对话）。
     *
     * 为什么要单独判：生图模型（`seedream`、`qwen-image`、`gpt-image`、`agnes-image` 等）
     * 不认 `chat/completions`，拿文本消息去探它必然被上游拒——「模型测试」在生图模型上报
     * 400（`messages 不能为空` / `invalid_request`）就是这么来的。探测与转发都得按这个分流。
     *
     * 判定依据**优先用上游给的字段**（各家口径不同，见下），名称只是兜底：
     * - 腾讯/字节：`extra["tags"]` 或 `extra["category"]` 含 image
     * - 灵犀：`extra["ability"]` 里带「图片/生图/绘图」
     * - 通用：模型 id 里出现 image / seedream / dall-e / flux 等已知词根
     */
    val isImageGeneration: Boolean get() {
        val markers = listOf("image", "生图", "图片", "绘图", "文生图", "t2i")
        for (key in listOf("tags", "category", "ability", "modality", "type", "output")) {
            val value = extra[key]?.lowercase().orEmpty()
            if (value.isEmpty()) continue
            if (markers.any { value.contains(it) }) return true
        }
        val id = id.lowercase()
        // 识图模型的名字里也可能带 image（如 xxx-image-understanding），先排掉这一类：
        // 它们走的是对话端点，误判成生图会让正常的对话测试被跳过。
        if (VISION_ID_HINTS.any { id.contains(it) }) return false
        return IMAGE_ID_HINTS.any { id.contains(it) }
    }

    /**
     * 这个模型是不是**生成视频**的。
     *
     * 与生图同理：视频模型也不认 `chat/completions`，测试必须打视频端点。
     */
    val isVideoGeneration: Boolean get() {
        val markers = listOf("video", "视频", "文生视频")
        for (key in listOf("tags", "category", "ability", "modality", "type", "output")) {
            val value = extra[key]?.lowercase().orEmpty()
            if (value.isEmpty()) continue
            if (markers.any { value.contains(it) }) return true
        }
        val id = id.lowercase()
        return VIDEO_ID_HINTS.any { id.contains(it) }
    }

    /** 这个模型是不是「不认对话端点」的那类（生图 / 生视频）。 */
    val isMediaGeneration: Boolean get() = isImageGeneration || isVideoGeneration

    /**
     * 这个模型该打哪个端点。
     *
     * 模型测试要按它分流：拿对话请求去打 embedding / rerank / 语音模型，上游一律回
     * 400（`messages 不能为空`、`invalid_request`），看起来像「模型挂了」，其实是
     * 打错了地方。判定顺序是先看上游给的能力字段，再看 id 词根。
     */
    val endpointKind: ModelEndpointKind get() {
        val declared = extra["endpoint"]?.trim()?.lowercase().orEmpty()
        if (declared.isNotEmpty()) {
            ModelEndpointKind.byId(declared)?.let { return it }
        }
        // 上游显式声明了「能力」时优先信它：各家口径不同，但都是"能力"语义
        val ability = listOf("ability", "tags", "category", "type", "output", "modality")
            .joinToString(" ") { extra[it].orEmpty() }
            .lowercase()
        when {
            ability.contains("rerank") || ability.contains("重排") -> return ModelEndpointKind.RERANK
            ability.contains("embed") || ability.contains("向量") -> return ModelEndpointKind.EMBEDDING
            ability.contains("transcri") || ability.contains("转写") -> return ModelEndpointKind.TRANSCRIPTION
            ability.contains("speech") || ability.contains("语音合成") -> return ModelEndpointKind.SPEECH
        }
        if (isVideoGeneration) return ModelEndpointKind.VIDEO
        if (isImageGeneration) return ModelEndpointKind.IMAGE

        val id = id.lowercase()
        // 重排必须排在向量前面：`bge-reranker-v2` 同时命中 `bge-`（向量词根）与
        // `rerank`，按 embedding 先判就会把重排模型打去 /v1/embeddings。
        return when {
            RERANK_ID_HINTS.any { id.contains(it) } -> ModelEndpointKind.RERANK
            EMBEDDING_ID_HINTS.any { id.contains(it) } -> ModelEndpointKind.EMBEDDING
            TRANSCRIPTION_ID_HINTS.any { id.contains(it) } -> ModelEndpointKind.TRANSCRIPTION
            SPEECH_ID_HINTS.any { id.contains(it) } -> ModelEndpointKind.SPEECH
            else -> ModelEndpointKind.CHAT
        }
    }

    /** 这个模型是否支持图片输入（识图/多模态）。 */
    val supportsImageInput: Boolean get() {
        val v = extra["vision"]?.trim()?.lowercase()
        val a = extra["attachment"]?.trim()?.lowercase()
        return v == "true" || v == "1" || a == "true" || a == "1"
    }

    companion object {
        /**
         * 生图模型的 id 词根。
         *
         * 只放**确定是生图**的：`vision` / `vl` / `omni` 这类是「能看图」不是「画图」，
         * 混进来会把识图模型错判成生图模型，反而让正常对话探测被跳过。
         */
        private val IMAGE_ID_HINTS = listOf(
            "seedream", "dall-e", "dalle", "gpt-image", "imagen",
            "flux", "stable-diffusion", "sdxl", "qwen-image", "kolors",
            "text2image", "t2i", "cogview", "hunyuan-image", "wanx",
            // 兜底词根：`agnes-image-2.0`、`xxx-image-1` 这类「品牌-用途」命名很常见。
            // 识图模型由 VISION_ID_HINTS 先排掉，不会被这条误伤。
            "image",
        )

        /** 识图（能看图、但仍走对话端点）的 id 词根，用来把「image 兜底词根」的误判挡回去。 */
        private val VISION_ID_HINTS = listOf(
            "vision", "-vl", "vl-", "vlm", "understand", "caption", "ocr", "image-to-text",
        )

        /** 生视频模型的 id 词根。 */
        private val VIDEO_ID_HINTS = listOf(
            "video", "veo-", "sora", "kling", "seedance", "hailuo", "runway", "pika",
        )

        /** 向量模型的 id 词根。 */
        private val EMBEDDING_ID_HINTS = listOf(
            "embed", "embedding", "bge-", "text-embedding", "gte-", "m3e",
        )

        /** 重排模型的 id 词根。 */
        private val RERANK_ID_HINTS = listOf("rerank", "re-rank", "reranker")

        /** 语音转写模型的 id 词根。 */
        private val TRANSCRIPTION_ID_HINTS = listOf(
            "whisper", "transcribe", "transcription", "asr",
        )

        /** 语音合成模型的 id 词根。 */
        private val SPEECH_ID_HINTS = listOf("tts", "text-to-speech", "speech-", "cosyvoice")
    }
}

/**
 * 一个模型该走哪个端点。
 *
 * 与 [ProviderModel.endpointKind] 配套：模型测试、能力探测都按它分流，
 * 不要再用「不是对话就都当生图」这种二分法——embedding / rerank / 语音
 * 打错端点同样会回 400。
 */
enum class ModelEndpointKind(
    /** 给用户看的端点名。 */
    val label: String,
    /** OpenAI 风格的路径（相对 baseUrl）。 */
    val path: String,
) {
    CHAT("对话", "/v1/chat/completions"),
    IMAGE("图片生成", "/v1/images/generations"),
    VIDEO("视频生成", "/v1/videos/generations"),
    EMBEDDING("文本向量", "/v1/embeddings"),
    RERANK("重排", "/v1/rerank"),
    TRANSCRIPTION("语音转文字", "/v1/audio/transcriptions"),
    SPEECH("语音合成", "/v1/audio/speech"),
    ;

    companion object {
        /** 按 id/名字查（上游若直接声明了端点类型，用它）。 */
        fun byId(raw: String): ModelEndpointKind? = entries.firstOrNull {
            it.name.equals(raw, ignoreCase = true) || it.path.endsWith("/$raw", ignoreCase = true)
        }
    }
}

/** 模型目录视图。 */
data class ProviderModelCatalogView(
    val models: List<ProviderModel>,
    val fromFallback: Boolean,
    val error: String,
)

/** 账号额度/积分快照。 */
data class CreditInfo(
    val balance: Long,
    /** false 表示上游未返回可解析的额度字段，界面显示「—」而不是 0。 */
    val known: Boolean = true,
    val detail: String = "",
    /**
     * 需要用户动手时的**可操作指引**（不含技术细节，直接告诉用户点哪里、做什么）。
     *
     * 与 [detail] 分工：detail 是给排查用的技术描述（含状态码、上游原文摘要），
     * actionHint 是给普通用户看的「下一步怎么做」。界面把它单独排在前面并高亮，
     * 避免用户被一坨 JSON 劝退——典型场景是 Google 403 `VALIDATION_REQUIRED`
     * （账号要人机验证），这时唯一有用的话就是「打开哪个网址、验证完回来刷新」。
     *
     * 为空表示没有额外操作建议。
     */
    val actionHint: String = "",
    /**
     * 需要用户动手时，可直接打开的操作链接（如 Google 的 `validation_url`）。
     *
     * 有这个字段时，界面会把它渲染成一个「一键去验证」按钮，用 Custom Tabs 在应用内打开
     * ——用户不用复制链接、不用切 App。为空表示该提示没有配套链接，只能按文字自己操作。
     *
     * 只接受 https 链接：这里的内容来自上游响应，绝不能让它变成可执行的任意 scheme。
     */
    val actionUrl: String = "",
    /**
     * 会员身份名（如「白银会员」），**由上游服务器下发**，不是硬编码。
     *
     * 界面把它渲染成账号名右侧的彩色胶囊标签；空串表示不是会员或查不到，不显示标签。
     */
    val memberLevel: String = "",
)

/** provider 专属动作的结果。 */
data class ProviderActionResult(
    val ok: Boolean,
    val message: String,
    val unsupported: Boolean = false,
) {
    companion object {
        fun success(message: String = "") = ProviderActionResult(true, message)
        fun failure(message: String) = ProviderActionResult(false, message)
        fun unsupported(action: String) =
            ProviderActionResult(false, "该供应商不支持「$action」", unsupported = true)
    }
}

/**
 * 一个成长任务的快照，供 UI 展示「有哪些任务、哪些做了、哪些没做」。
 *
 * 字段命名对齐 WorkBuddy（腾讯 CodeBuddy）的 `/v2/activity/growth/tasks`：
 * `task_code` / `title` / `description` / `current` / `target` / `accept_status` /
 * `locked` / `claimed` / `reward_credit`。其它供应商可自行映射。
 */
data class ProviderTask(
    val code: String,
    val title: String,
    val description: String = "",
    /** 当前进度与目标；[target] 为 0 表示该任务无进度概念。 */
    val current: Long = 0,
    val target: Long = 0,
    /** 上游接取状态（如空串 / claimed / accepted / completed）。 */
    val status: String = "",
    /** 未解锁（前置条件未达成）。 */
    val locked: Boolean = false,
    /** 已领取奖励。 */
    val claimed: Boolean = false,
    val rewardCredit: Long = 0,
) {
    /** 进度达标（有目标且当前进度已达）。 */
    val progressDone: Boolean get() = target > 0 && current >= target

    /** 已完成：已领取，或进度已达标。 */
    val completed: Boolean get() = claimed || progressDone

    /** 可领取奖励：已达标且尚未领取。 */
    val claimable: Boolean get() = !claimed && progressDone

    /** 进度文案：有目标显示「当前/目标」，已领取显示「已领取」，否则回退上游状态或「未知」。 */
    fun progressText(): String = when {
        target > 0 -> "$current/$target"
        claimed -> "已领取"
        status.isNotEmpty() -> status
        else -> "未知"
    }
}

/**
 * 一个账号的成长任务列表快照。
 */
data class ProviderTaskListView(
    val tasks: List<ProviderTask>,
    /**
     * 活动是否在期（对齐上游 `in_period`）：true 在期、false 不在期、**null 表示上游没给这个字段**。
     *
     * 用三态而不是默认 false：不是每个任务接口都返回 in_period，默认 false 会让界面把
     * 「上游没说」当成「活动已结束」展示。
     */
    val inPeriod: Boolean? = null,
    /** 非空表示拉取失败（凭证失效、上游报错等），界面据此提示。 */
    val error: String = "",
)

/**
 * 一笔积分/额度流水。
 *
 * 字段命名对齐小浣熊的 `/api/web/points/v1/bills`：`bill_id` / `event_name` /
 * `biz_type` / `points` / `created_at`。
 *
 * 与 [QuotaPack] 的分工：额度包回答「还剩多少」，流水回答「每一笔怎么来的」。
 */
data class PointsBill(
    val id: String,
    /** 展示名（上游 `event_name`，缺失时回退业务类型）。 */
    val title: String,
    /** 正数入账、负数支出，符号与上游一致。 */
    val points: Long,
    /** 上游业务类型（如 `daily_grant` 发放 / `daily_expire` 过期）。 */
    val bizType: String = "",
    /** 发生时间（毫秒；0 表示上游没给或无法解析）。 */
    val createdAtMillis: Long = 0,
)

/**
 * 一个账号的积分流水快照。
 *
 * [error] 非空表示拉取失败，[bills] 为空不代表没有流水。
 */
data class PointsBillListView(
    val bills: List<PointsBill>,
    val error: String = "",
)
