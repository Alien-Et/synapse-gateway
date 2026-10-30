package io.synapse.core.provider.loomy

/** 上游模型条目（字段取自官方客户端 `normalizeFetchedModelMetadata`）。 */
data class LoomyModel(
    val id: String,
    val name: String,
    val contextLength: Long = 0L,
    val outputLength: Long = 0L,
    val reasoning: Boolean = false,
    val toolCall: Boolean = false,
    val attachment: Boolean = false,
    /**
     * 上游没有独立的倍率字段，倍率**内嵌在 `name` 里**：`"Kimi k2.6 （x6.5）"`、`"GLM 5.3 Flash(x0.8)"`。
     * 解析后取出的数值（如 6.5、0.8）；没有该后缀时为 null。
     */
    val rate: Double? = null,
    /** 名称里的活动文案（如「限时免费」）；没有则为 null。 */
    val rateTag: String? = null,
)
