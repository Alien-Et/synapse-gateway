package io.synapse.core.failover

import kotlin.random.Random

/**
 * 「这次失败该不该换到别家」的独立判定器。
 *
 * ## 为什么单独一个类
 *
 * 现有路由循环（GatewayHttpServer 里的 while 循环）里已经有三层逻辑：同供应商换号、
 * 熔断顶班、跨区域兜底。把「跨供应商」直接塞进去会让那个循环更难读，也更容易改坏既有行为。
 *
 * 所以这里只做一件事：**输入当前状态，输出「换 / 不换，换到谁」**。路由侧只需要在
 * 原本决定 break 的地方问一句这个类，其余逻辑一行不动。关闭时它永远返回 null，
 * 路由侧行为与改造前完全一致。
 *
 * ## 触发条件（用户勾选的四类全要）
 *
 * 1. 当前供应商**熔断中**，且本家已无其它区域/账号可用；
 * 2. 当前供应商**所有账号都不可用**（停用 / 额度耗尽）且选不出人；
 * 3. 当前模型在该供应商**所有账号上都处于冷却**；
 * 4. 上游 **5xx / 网络层**连续失败到重试次数用尽。
 *
 * 前三类是「这家已经彻底没法用」，第 4 类是「这家看起来活着但一直在 5xx」——都算
 * “确认不可用”，才值得动用跨供应商这个会造成「路由语义偏移」的手段。
 *
 * ## 明确**不**触发的场景
 *
 * - 客户端自身错误（400 / 参数非法 / 内容审核 / 上下文超长）：换家也一样错，
 *   换了反而把错误记到别家头上，还会污染别家的熔断计数；
 * - 只要本家还有可用账号，就交给原有的换号逻辑，本类不插手。
 */
class CrossProviderSwitchDecider(
    private val config: () -> CrossProviderFailoverConfig,
    private val random: Random = Random.Default,
) {

    /** 应当换供应商的结论。[candidate] 为选中的落点。 */
    data class Verdict(
        val candidate: CrossProviderCandidate,
        /** 给日志与界面看的简短原因（为什么会走到跨供应商）。 */
        val reason: String,
    )

    /**
     * 判断是否该换。
     *
     * @param attempted 本次请求已经试过的 `providerId|model`（防止 A→B→A 来回跳）。
     * @param trigger 触发原因，见类注释里的四类。
     * @param creditsOf 仅在「按额度」策略下用于取剩余额度。
     */
    fun decide(
        trigger: Trigger,
        attempted: Set<String>,
        creditsOf: (CrossProviderCandidate) -> Long? = { null },
    ): Verdict? {
        val cfg = config()
        // 开关关着，或没有候选：直接不参与（默认路径）
        if (!cfg.active) return null
        if (!trigger.qualifies) return null
        val remaining = cfg.candidates.filter { it.key !in attempted }
        if (remaining.isEmpty()) return null
        val picked = cfg.strategy.pick(remaining, creditsOf, random) ?: return null
        return Verdict(picked, trigger.describe())
    }

    /** 触发原因。 */
    sealed interface Trigger {
        /** 该原因是否足以动用跨供应商。 */
        val qualifies: Boolean
        fun describe(): String

        /** 供应商熔断，且本家已无其它区域/账号可用。 */
        data class ProviderCircuitOpen(val providerId: String) : Trigger {
            override val qualifies: Boolean get() = true
            override fun describe() = "$providerId 熔断且无其它可用账号"
        }

        /** 所有账号都不可用（停用 / 额度耗尽），选不出人。 */
        data class NoUsableAccount(val providerId: String, val detail: String = "") : Trigger {
            override val qualifies: Boolean get() = true
            override fun describe() =
                if (detail.isEmpty()) "$providerId 无可用账号" else "$providerId 无可用账号（$detail）"
        }

        /** 该模型在本供应商所有账号上都冷却中。 */
        data class ModelFullyCooling(
            val providerId: String,
            val model: String,
            val waitSeconds: Long,
        ) : Trigger {
            override val qualifies: Boolean get() = true
            override fun describe() = "$providerId/$model 全部账号冷却中（约 ${waitSeconds}s）"
        }

        /** 上游连续 5xx / 网络失败，重试用尽。 */
        data class UpstreamExhausted(val providerId: String, val lastError: String = "") : Trigger {
            override val qualifies: Boolean get() = true
            override fun describe() =
                if (lastError.isEmpty()) "$providerId 上游持续失败" else "$providerId 上游持续失败（$lastError）"
        }

        /**
         * 客户端自身错误：**不该换家**。
         *
         * 换过去还是会错（参数非法、内容审核、上下文超长都是请求本身的问题），
         * 而且会把一次用户输入错误记到无辜的供应商头上。
         */
        data class ClientError(val status: Int, val reason: String = "") : Trigger {
            override val qualifies: Boolean get() = false
            override fun describe() = "客户端错误 $status，不跨供应商"
        }
    }
}
