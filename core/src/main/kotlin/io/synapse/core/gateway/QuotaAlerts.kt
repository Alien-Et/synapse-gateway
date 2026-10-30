package io.synapse.core.gateway

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 一次账号/供应商告警的对外快照。
 *
 * 只含展示所需的路由级信息（供应商中文名、账号昵称、被切走的模型），
 * **不含任何上游协议细节**——和 [ActiveRequest] 同一套边界。
 */
data class QuotaAlert(
    /** 出问题的供应商中文名（如「腾讯国内」）。 */
    val providerName: String,
    /** 出问题的账号昵称；昵称为空时用 uid 兜底。 */
    val accountName: String,
    /** 客户端请求的模型名（原样回显，方便对上是哪一笔请求）。 */
    val model: String,
    /** 问题描述（能直接给人看的那句）。 */
    val reason: String,
) {
    /** 告警级别，决定 App 用「轻提示」还是「强提醒」。 */
    enum class Level {
        /**
         * 单个账号额度耗尽，但同供应商还有别的账号可用 ——
         * 已经自动换号继续跑，请求没断。只需要轻提醒（震动一下 + 静默通知）。
         */
        ACCOUNT_EXHAUSTED,

        /**
         * 整个供应商都没额度了 —— 请求无法在该供应商继续，
         * 需要用强提醒（弹窗 + 震动），让人知道要去补额度了。
         */
        PROVIDER_EXHAUSTED,

        /**
         * 该供应商的账号全部被停用（用户手动关闭 / 凭证失效），一条可用都没有。
         *
         * 与额度的区别：这**不是钱的问题**，是账号被关掉了，用户去「供应商」页
         * 打开账号（或重新登录）就能立刻恢复。所以文案要指向「启动账号」而不是「充值」。
         *
         * 触发点：选号时发现同供应商（同区域）一个可用账号都挑不出来。
         */
        ALL_ACCOUNTS_DISABLED,

        /**
         * 该模型在同家所有账号上都不可用（如 freq limit / 套餐权益拒绝）。
         *
         * 与额度不同：**不是钱的问题**，是模型本身被上游限制住了。
         * 文案要指向「换个模型」，并带上上游给的重置时间。
         */
        MODEL_UNAVAILABLE,

        /**
         * 供应商被熔断隔离，而**同供应商下已经没有别的账号能顶上**。
         *
         * 与 [MODEL_UNAVAILABLE] 的区别：这不是某个模型的问题，是这家整体
         * 连续失败被打进静默期。此时给用户的两条路是「等冷却结束（有明确秒数）」
         * 或「换个供应商的模型」——文案必须两条都给，否则用户只能干等。
         */
        PROVIDER_CIRCUIT_OPEN,
    }
}

/**
 * 账号告警总线（额度耗尽 + 账号停用共用）。
 *
 * 与 [ActiveRequests] 的分工：后者描述「谁正在跑」，这里只负责「谁不能用了」这类事件，
 * **不参与任何调度决策**——告警发不出去（没有监听器、监听器抛异常）绝不影响请求转发。
 *
 * 触发点都在转发层：
 * 1. 上游报 `ErrorKind.QUOTA` → `ACCOUNT_EXHAUSTED` / `PROVIDER_EXHAUSTED`；
 * 2. 选号时同供应商（同区域）一个可用账号都没有 → `ALL_ACCOUNTS_DISABLED`；
 * 3. 模型级限制（freq limit / 套餐拒绝）且同家账号已全部试过 → `MODEL_UNAVAILABLE`。
 *
 * 设计原则：**先自己想办法（换号），实在不行才打扰用户**。
 * 同家还有没试过的账号时，先把请求跑到能成功为止；告警只在真无解时发。
 *
 * 线程约定：可能在任意网关线程被回调，App 侧需自行切到主线程再弹通知/震动。
 */
class QuotaAlerts {

    private val listeners = CopyOnWriteArrayList<(QuotaAlert, QuotaAlert.Level) -> Unit>()

    fun addListener(listener: (QuotaAlert, QuotaAlert.Level) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (QuotaAlert, QuotaAlert.Level) -> Unit) {
        listeners.remove(listener)
    }

    /** 发布一个告警；监听器异常被吞掉，保证不影响转发。 */
    fun emit(alert: QuotaAlert, level: QuotaAlert.Level) {
        if (listeners.isEmpty()) return
        for (listener in listeners) {
            runCatching { listener(alert, level) }
        }
    }
}
