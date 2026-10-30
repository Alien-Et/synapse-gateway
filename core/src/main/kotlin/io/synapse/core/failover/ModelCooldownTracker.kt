package io.synapse.core.failover

import java.util.concurrent.ConcurrentHashMap

/**
 * 模型级冷却表（**按账号**）。
 *
 * 某些上游错误是**模型级**的：`429 code=6004`（「该模型已超限」）、Trae `1005`
 * （套餐权益拒绝）、以及上游明确说「你已用完该模型的本次配额」。
 *
 * 但「模型级」不等于「整个供应商都不能用这个模型」——上游的频率/配额
 * 绝大多数是**按账号**计算的，同一个模型换个账号往往立刻能用。
 *
 * 所以这里按 `供应商|账号|模型` 三键记账，语义分两层：
 *
 * 1. [markCooling] 只是「**这个号**暂时别用这个模型」；
 * 2. [isCooling] 判断「整个供应商是否已经**所有号**都在冷却」——
 *    只有当池里每个可用账号都在冷却时，才对外说该模型不可用。
 *
 * 这样轮换时永远是「先换号，同家号都撞墙了才冷却整家」，符合主人要求的
 * 「出现熔断/冷却时立即在同供应商下换号继续」。
 *
 * 与熔断器/健康指标一致：**仅内存**。冷却本就是瞬态状态，重启即清空。
 */
class ModelCooldownTracker(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /** 键：`providerId|accountUid|model`；值：冷却截止毫秒。 */
    private val until = ConcurrentHashMap<String, Long>()

    private fun key(providerId: String, uid: String, model: String) = "$providerId|$uid|$model"

    /**
     * 该 (供应商, 模型) 是否**整体**不可用。
     *
     * 传了 [accountUid] 时只判断这一个号；不传（或传 null）时取保守语义：
     * 只要还有**一个**号的冷却记录没到期，就认为该模型仍可能可用（返回 false），
     * 由转发层去挑那个没被冷却的号。真正「整家都冷却」的判定由
     * [GatewayEngine 侧的 `allCooling`] 配合账号池完成。
     */
    fun isCooling(providerId: String, model: String, accountUid: String? = null): Boolean {
        val now = nowMillis()
        if (accountUid != null) return isCoolingFor(providerId, accountUid, model, now)
        // 未指定账号：任何一个号的冷却都算「该模型在这家还没有被判死」
        val prefix = "$providerId|"
        val suffix = "|$model"
        for ((k, deadline) in until) {
            if (!k.startsWith(prefix) || !k.endsWith(suffix)) continue
            if (now < deadline) return true
        }
        return false
    }

    /** 精确判断某个账号的该模型是否在冷却；到期项顺手清掉。 */
    fun isCoolingFor(providerId: String, accountUid: String, model: String, now: Long = nowMillis()): Boolean {
        val k = key(providerId, accountUid, model)
        val deadline = until[k] ?: return false
        if (now >= deadline) {
            until.remove(k, deadline)
            return false
        }
        return true
    }

    /**
     * 判断该供应商的该模型是否**所有给定账号**都在冷却。
     *
     * [uids] 传「当前池里可用的账号」；为空时不作判断（返回 false），
     * 避免把「一个账号都没有」误报成「全都在冷却」。
     */
    fun allCooling(providerId: String, model: String, uids: Collection<String>): Boolean {
        if (uids.isEmpty()) return false
        val now = nowMillis()
        return uids.all { uid -> isCoolingFor(providerId, uid, model, now) }
    }

    /** 记某个账号的该模型冷却 [durationMs] 毫秒；[durationMs] <= 0 时不记。 */
    fun markCooling(providerId: String, accountUid: String, model: String, durationMs: Long) {
        if (durationMs <= 0) return
        until[key(providerId, accountUid, model)] = nowMillis() + durationMs
    }

    /** 成功一次即解除该账号该模型的冷却（上游已恢复可用）。 */
    fun clear(providerId: String, accountUid: String, model: String) {
        until.remove(key(providerId, accountUid, model))
    }

    /** 解除该供应商该模型下所有账号的冷却。 */
    fun clearAll(providerId: String, model: String) {
        val prefix = "$providerId|"
        val suffix = "|$model"
        until.keys
            .filter { it.startsWith(prefix) && it.endsWith(suffix) }
            .forEach { until.remove(it) }
    }

    /** 当前仍在冷却中的条目：`providerId|uid|model` → 剩余毫秒。供界面展示。 */
    fun snapshot(): Map<String, Long> {
        val now = nowMillis()
        val out = LinkedHashMap<String, Long>()
        for ((k, deadline) in until) {
            val remaining = deadline - now
            if (remaining > 0) out[k] = remaining else until.remove(k, deadline)
        }
        return out
    }

    /** 某供应商该模型下仍在冷却的账号集合。 */
    fun coolingAccounts(providerId: String, model: String): Set<String> {
        val now = nowMillis()
        val prefix = "$providerId|"
        val suffix = "|$model"
        val out = LinkedHashSet<String>()
        for ((k, deadline) in until) {
            if (!k.startsWith(prefix) || !k.endsWith(suffix)) continue
            if (now >= deadline) {
                until.remove(k, deadline)
                continue
            }
            out += k.removePrefix(prefix).removeSuffix(suffix)
        }
        return out
    }
}
