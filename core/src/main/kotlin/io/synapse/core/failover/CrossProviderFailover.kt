package io.synapse.core.failover

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.random.Random

/**
 * 跨供应商故障转移 —— 与「跨区域」「同供应商换号」完全独立的一套逻辑。
 *
 * ## 它解决什么
 *
 * 原来的设计是**绝不跨供应商**：路由到哪家就是哪家，写「灵犀/qwen-max」就只会发给灵犀。
 * 这保证了标签语义（不会出现「写了腾讯却发到谷歌」），但也意味着这家挂了请求就直接失败。
 *
 * 这个模块给用户一个**显式开启**的出口：自己挑几个候选模型，排好优先级；
 * 当前供应商确认不可用时，按用户选的策略换到候选里的下一个。
 *
 * ## 与其它两套机制的分工（三者互不干扰）
 *
 * - 同供应商换号：一家之内换账号，始终生效，本模块不动它。
 * - 跨区域：同供应商的国内/国外站点，由 `crossRegionFailover` 控制，本模块不动它。
 * - 跨供应商（本模块）：只在前两者都救不回来（本家所有区域、所有账号都不可用）时才起作用；
 *   关闭时（默认）行为与改造前一致。
 *
 * ## 为什么默认关闭
 *
 * 跨供应商会直接打破「我写的是哪家就发哪家」的预期。用户必须先明确知道自己在做什么、
 * 手动勾选候选模型，才该发生这件事。默认开启等于悄悄改变了路由语义。
 */
data class CrossProviderFailoverConfig(
    /** 总开关，默认关闭。关闭时整套逻辑不参与任何决策。 */
    val enabled: Boolean = false,

    /**
     * 候选模型列表（按用户勾选顺序保存，顺序在「按顺序」策略下就是优先级）。
     *
     * 每个条目是 `providerId` + `model`（供应商内部模型名，不含路由前缀），
     * 例如 `("mcloud", "qwen3.5-plus")`。存内部名而不是展示名，避免改了展示名后失效。
     */
    val candidates: List<CrossProviderCandidate> = emptyList(),

    /** 换到候选时的挑选策略。 */
    val strategy: CrossProviderStrategy = CrossProviderStrategy.IN_ORDER,
) {

    /** 是否有可用配置（开关打开且至少有一个候选）。 */
    val active: Boolean get() = enabled && candidates.isNotEmpty()

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("enabled", enabled)
        addProperty("strategy", strategy.name)
        add("candidates", JsonArray().apply {
            candidates.forEach { add(it.toJson()) }
        })
    }

    companion object {
        const val STORE_KEY = "settings/cross-provider-failover.json"

        fun fromJson(raw: String?): CrossProviderFailoverConfig {
            if (raw.isNullOrBlank()) return CrossProviderFailoverConfig()
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return CrossProviderFailoverConfig()
            return CrossProviderFailoverConfig(
                enabled = obj.get("enabled")?.asBoolean ?: false,
                candidates = obj.getAsJsonArray("candidates")?.mapNotNull { element ->
                    runCatching { element.asJsonObject }.getOrNull()?.let { CrossProviderCandidate.fromJson(it) }
                }.orEmpty(),
                strategy = runCatching {
                    CrossProviderStrategy.valueOf(obj.get("strategy")?.asString.orEmpty())
                }.getOrDefault(CrossProviderStrategy.IN_ORDER),
            )
        }
    }
}

/**
 * 勾选 / 取消一个候选，返回新配置。
 *
 * 勾上时**追加到末尾**：候选顺序就是「按顺序」策略的优先级，用户先勾的应该先用。
 * 同一个候选重复勾不会产生重复条目（幂等），所以界面连点也不会把列表撑出两份。
 * 取消时按 [CrossProviderCandidate.key] 移除。
 *
 * 放在 core 而不是界面里，是为了让「顺序 / 去重 / 幂等」这三条能被测试钉住——
 * 它们直接决定故障转移时先发给谁。
 */
fun CrossProviderFailoverConfig.withCandidateToggled(
    candidate: CrossProviderCandidate,
    checked: Boolean,
): CrossProviderFailoverConfig = if (checked) {
    if (candidates.any { it.key == candidate.key }) this
    else copy(candidates = candidates + candidate)
} else {
    copy(candidates = candidates.filterNot { it.key == candidate.key })
}

/** 一个跨供应商候选：某家供应商的某个模型。 */data class CrossProviderCandidate(
    val providerId: String,
    val model: String,
) {
    /** 用于界面展示与去重的 key。 */
    val key: String get() = "$providerId|$model"

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("providerId", providerId)
        addProperty("model", model)
    }

    companion object {
        fun fromJson(obj: JsonObject): CrossProviderCandidate? {
            val providerId = obj.get("providerId")?.asString.orEmpty()
            val model = obj.get("model")?.asString.orEmpty()
            if (providerId.isEmpty() || model.isEmpty()) return null
            return CrossProviderCandidate(providerId, model)
        }
    }
}

/** 候选的挑选策略。 */
enum class CrossProviderStrategy(
    val label: String,
    /** 给用户看的一句话说明（设置页直接显示）。 */
    val hint: String,
) {
    /** 严格按用户勾选的顺序，第一个可用就用它。 */
    IN_ORDER("按顺序", "严格按勾选顺序，第一个可用的就用它。最可预测。"),

    /** 随机挑一个可用候选（分摊各家的额度消耗）。 */
    RANDOM("随机", "在可用候选里随机挑一个，把请求摊到多家，避免集中消耗某一家。"),

    /** 优先挑剩余额度最多的那家。 */
    BY_CREDITS("按额度", "优先用剩余额度最多的那家，尽量别在快用完的账号上耗。"),
    ;

    /**
     * 按策略从 [candidates] 里挑出一个。
     *
     * [creditsOf] 只在 [BY_CREDITS] 下被调用，返回该候选的剩余额度（未知返回 null）。
     * 未知额度的候选排在已知的后面，而不是被当成 0 排除掉——「不知道」不等于「没有」。
     *
     * [random] 可注入，方便测试随机策略。
     */
    fun pick(
        candidates: List<CrossProviderCandidate>,
        creditsOf: (CrossProviderCandidate) -> Long? = { null },
        random: Random = Random.Default,
    ): CrossProviderCandidate? {
        if (candidates.isEmpty()) return null
        return when (this) {
            IN_ORDER -> candidates.first()
            RANDOM -> candidates[random.nextInt(candidates.size)]
            BY_CREDITS -> candidates
                .map { it to creditsOf(it) }
                // 已知额度从大到小；未知额度一律排最后（保持用户给的相对顺序）
                .sortedWith(
                    compareByDescending<Pair<CrossProviderCandidate, Long?>> { it.second != null }
                        .thenByDescending { it.second ?: 0L },
                )
                .first().first
        }
    }
}
