package io.synapse.core.gateway

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 系统吐司（Toast）事件总线。
 *
 * ## 为什么要单独一条总线，而不是复用 quotaAlerts
 *
 * [QuotaAlerts] 是「额度/账号不可用」的**强提醒**（横幅 + 震动 + 常驻通知），
 * 语义上是「需要用户去做什么」；这里要的是「告诉用户系统刚刚自动做了什么」——
 * 比如「当前模型已冷却，已故障转移到腾讯的 xxx」。这类信息量大但不紧急，
 * 用 Toast 一闪而过最合适，不该弹横幅打断人。
 *
 * ## 事件类型（可扩展）
 *
 * [NoticeKind] 枚举了目前支持的类型。新增提醒场景只要加一个枚举值 + 在转发层
 * 发一条 [NoticeEvent]，App 侧不需要改接线。
 *
 * ## 去重（三套提醒的统一口径）
 *
 * 同一个 key 在 [DEDUP_WINDOW_MS] 内只发一次：批量请求同时触发故障转移时，
 * 连着弹十条一样的 Toast 只会让人烦。key 由调用方给（通常是「类型|模型」）。
 *
 * 项目里共有三层去重，各有分工、但**窗口统一用 [DEDUP_WINDOW_MS]**：
 * 1. [io.synapse.core.failover.ModelCooldownTracker] + GatewayHttpServer 的
 *    cooldownAlerted —— 按「一轮冷却」去重，解封后自动失效（业务级，最严格）；
 * 2. [QuotaAlerts] → App 侧 QuotaAlertNotifier —— 横幅/震动/常驻通知去重；
 * 3. 本类 —— 系统 Toast 去重。
 * 第 1 层是语义去重（一轮冷却只提醒一次），第 2/3 层是防刷屏（时间窗），
 * 它们叠加不冲突：第 1 层拦住的事件根本不会走到后两层。
 *
 * 线程约定：回调可能在任意网关线程，App 侧需自行切主线程再弹 Toast。
 */
enum class NoticeKind {
    /** 当前模型被冷却，已自动换号/换供应商。 */
    COOLDOWN_FAILOVER,

    /** 当前模型被禁用，已自动转到候选模型。 */
    BANNED_FAILOVER,

    /** 模型禁用刚生效（第一次触发）。 */
    MODEL_BANNED,

    /** 上游熔断，已换同家其它账号继续。 */
    CIRCUIT_FAILOVER,

    /** 额度耗尽，已自动换号。 */
    QUOTA_FAILOVER,

    /** 跨供应商故障转移已发生。 */
    CROSS_PROVIDER_FAILOVER,

    /** 账号凭证失效被自动停用。 */
    ACCOUNT_DISABLED,

    /** 通用信息。 */
    INFO,
}

/**
 * 一条吐司事件。
 *
 * @property message 已经拼好的中文句子，可直接显示（不含技术细节/URL/状态码）
 * @property dedupKey 去重键；为空表示**不去重**（一次性事件通常留空）
 */
data class NoticeEvent(
    val kind: NoticeKind,
    val message: String,
    val dedupKey: String = "",
)

class NoticeEvents(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val listeners = CopyOnWriteArrayList<(NoticeEvent) -> Unit>()
    private val lastEmitted = ConcurrentHashMap<String, Long>()

    fun addListener(listener: (NoticeEvent) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (NoticeEvent) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * 发一条吐司。相同 [NoticeEvent.dedupKey] 在窗口内只发一次。
     *
     * **永不抛异常**：提醒发不出去绝不能影响请求转发（和 quotaAlerts 同一纪律）。
     */
    fun emit(event: NoticeEvent) {
        val now = nowMillis()
        if (event.dedupKey.isNotEmpty()) {
            val last = lastEmitted[event.dedupKey]
            if (last != null && now - last < DEDUP_WINDOW_MS) return
            lastEmitted[event.dedupKey] = now
            // 顺手清掉过期条目，避免长跑后 map 无限涨
            if (lastEmitted.size > 200) {
                lastEmitted.entries.removeIf { now - it.value > DEDUP_WINDOW_MS * 2 }
            }
        }
        for (listener in listeners) {
            runCatching { listener(event) }
        }
    }

    /** 便捷入口：构造一条事件并发出。 */
    fun emit(kind: NoticeKind, message: String, dedupKey: String = "") {
        emit(NoticeEvent(kind, message, dedupKey))
    }

    /** 清空去重记录（设置页改了配置、或用户主动「重置提醒」时用）。 */
    fun clearDedup() {
        lastEmitted.clear()
    }

    companion object {
        /** 同类提醒的去重窗口：2 分钟。 */
        const val DEDUP_WINDOW_MS = 120_000L
    }
}
