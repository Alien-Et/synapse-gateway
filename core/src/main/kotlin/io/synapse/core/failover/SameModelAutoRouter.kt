package io.synapse.core.failover

import io.synapse.core.provider.RoutedModel
import kotlin.random.Random

/**
 * 按模型名自动找「同名模型」的跨供应商路由 —— 手动候选池之外的第二条候选来源。
 *
 * ## 它解决什么
 *
 * 原有的 [CrossProviderFailoverConfig.candidates] 要用户**手写**「A 家的 x 挂了 → 换 B 家的 y」，
 * 换过去的是不是同一个模型、能力对不对，全靠用户自己盯。而同一个模型常同时挂在多家
 * （`deepseek-4.1` 可能同时在灵犀、商汤、字节上架），用户只想说一句「用 deepseek-4.1，
 * 别管哪家」，坏一家就自动换下一家。
 *
 * ## 边界：这个类只回答一个问题
 *
 * 「给定一个模型名，别家有没有同名的？」——只此一件。
 *
 * **不参与**选号、熔断、冷却、禁用、错误分类、日志、吐司。列表怎么用、何时用，
 * 由 `CrossProviderSwitchDecider.Trigger` 与路由循环决定，所以这里做成纯函数式：
 * 单测能钉死，也不会把复杂度带进 `GatewayHttpServer` 的主循环。
 *
 * ## 「同名」怎么判
 *
 * [normalize] 转小写并只保留字母数字，于是 `DeepSeek-4.1` / `deepseek_4.1` / `deepseek4.1`
 * 视为同一个模型。
 *
 * **刻意不做**「模糊包含」匹配：`deepseek-v3` 不会命中 `deepseek-v3.2`。
 * 那属于猜模型 —— 猜错就是把用户请求发到另一个模型上，比直接报错更糟。
 *
 * ## 目录必须来自内存快照
 *
 * [catalog] 绝不能在故障转移路径上现拉上游：`GatewayEngine.models()` 会真打每家接口
 * （单家读超时 300s），失败重试时现拉等于把一次超时放大成几十秒卡死。
 * 快照为空时本类返回空列表 = 不参与，行为退化成原来的「不跨供应商」。
 */
class SameModelAutoRouter(
    private val catalog: () -> List<RoutedModel>,
) {

    /**
     * 找出 [currentProviderId] 之外、与 [model] 同名的候选。
     *
     * @param attempted 本次请求已试过的 `providerId|model`，用于防 A→B→A 来回跳。
     *   同一家供应商只保留第一条（区域型供应商的国内/国外会各出若干条，取先出现的即可）。
     */
    fun candidatesFor(
        currentProviderId: String,
        model: String,
        attempted: Set<String> = emptySet(),
    ): List<CrossProviderCandidate> {
        val key = normalize(model)
        if (key.isEmpty()) return emptyList()
        return catalog()
            .asSequence()
            .filter { it.providerId != currentProviderId }
            .filter { normalize(it.model.id) == key }
            .map { CrossProviderCandidate(it.providerId, it.model.id) }
            .filter { it.key !in attempted }
            .distinctBy { it.key }
            .toList()
    }

    /**
     * 完整的「换不换」判定：触发条件成立 **且** 找得到同名模型，才给落点。
     *
     * 复用 [CrossProviderSwitchDecider.Trigger]（触发原因的分类与话术都不变），
     * 只把候选来源从「用户勾选」换成「按名匹配」—— 两个判定器共用同一套触发语义，
     * 将来加新的触发类型时两边同时获得。
     */
    fun decide(
        trigger: CrossProviderSwitchDecider.Trigger,
        currentProviderId: String,
        model: String,
        attempted: Set<String>,
        strategy: CrossProviderStrategy = CrossProviderStrategy.IN_ORDER,
        creditsOf: (CrossProviderCandidate) -> Long? = { null },
        random: Random = Random.Default,
    ): CrossProviderSwitchDecider.Verdict? {
        if (!trigger.qualifies) return null
        val pool = candidatesFor(currentProviderId, model, attempted)
        if (pool.isEmpty()) return null
        val picked = strategy.pick(pool, creditsOf, random) ?: return null
        return CrossProviderSwitchDecider.Verdict(
            candidate = picked,
            reason = "${trigger.describe()} → 同名模型自动路由",
        )
    }

    companion object {
        /**
         * 模型名归一化：小写 + 只保留字母数字。
         *
         * `DeepSeek-4.1` / `deepseek_4.1` / `deepseek4.1` → `deepseek41`，视为同名；
         * `deepseek-v3` → `deepseekv3`，与 `deepseek-v3.2`（`deepseekv32`）不相等。
         */
        fun normalize(model: String): String = model.lowercase().filter { it.isLetterOrDigit() }
    }
}
