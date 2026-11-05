package io.synapse.core.failover

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.store.KeyValueStore
import java.util.concurrent.ConcurrentHashMap

/**
 * 模型禁用表（**按供应商+模型**，跨账号）。
 *
 * ## 与「冷却」的区别（这是两套不同的机制，别混）
 *
 * | | 冷却 ModelCooldownTracker | 禁用 ModelBanTracker |
 * |---|---|---|
 * | 粒度 | 供应商 + **账号** + 模型 | 供应商 + 模型（**全账号**） |
 * | 时长 | 10 分钟（固定） | 24 小时（可配） |
 * | 触发 | 一次模型级限流 | **解封后当天仍连续失败 3 次** |
 * | 落盘 | 否（瞬态） | **是**（重启后依然禁用） |
 * | 语义 | 「这个号用这个模型暂时不行」 | 「这个模型这阵子别用了」 |
 *
 * ## 为什么需要它
 *
 * 冷却只能挡住「这一个号」，换个号又去撞同一个模型；如果上游对这个**模型**整体
 * 有长时间问题（配额池耗尽、模型下线、账号被该模型风控），换号是白费力气：
 * 每次都要试一遍、每次都失败、每次都要等一轮超时。禁用是「认输并记住」：
 * 24 小时内不再路由到它，直接把请求让给别的候选。
 *
 * ## 触发条件（用户定的）
 *
 * 冷却解封之后，**当天**同一个模型再连续失败 3 次 → 禁用 24 小时。
 * 关键在于「解封后」：冷却期内的失败不该累计，那只是同一个问题在重复报。
 *
 * ## 落盘
 *
 * 与冷却不同，禁用**必须落盘**：24 小时的窗口远长于一次 App 启动周期，
 * 不落盘的话用户重启一次 App 就白禁了，又要重新踩 3 次坑。
 */
class ModelBanTracker(
    private val store: KeyValueStore? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 键：`providerId|model`；值：禁用截止毫秒。 */
    private val until = ConcurrentHashMap<String, Long>()

    /**
     * 当天的连续失败计数。
     *
     * 键：`providerId|model`；值：`(计数, 当日零点毫秒)`。带上日期是为了「跨天自动清零」——
     * 只在自增时比较日期，不必起定时器。
     */
    private val consecutiveFailures = ConcurrentHashMap<String, FailureCounter>()

    private data class FailureCounter(val count: Int, val dayStart: Long)

    private fun key(providerId: String, model: String) = "$providerId|$model"

    init {
        restore()
    }

    // ---------------------------------------------------------------- 查询

    /** 该供应商的该模型是否处于禁用中。 */
    fun isBanned(providerId: String, model: String): Boolean {
        val deadline = until[key(providerId, model)] ?: return false
        if (nowMillis() >= deadline) {
            // 到期即清（顺手落盘，避免陈旧条目一直被读）
            until.remove(key(providerId, model))
            persist()
            return false
        }
        return true
    }

    /** 剩余禁用毫秒数；未禁用返回 0。 */
    fun remainingBanMs(providerId: String, model: String): Long {
        if (!isBanned(providerId, model)) return 0L
        val deadline = until[key(providerId, model)] ?: return 0L
        return (deadline - nowMillis()).coerceAtLeast(0L)
    }

    /** 当前所有禁用条目（`providerId|model` → 剩余毫秒）。供界面展示。 */
    fun snapshot(): Map<String, Long> {
        val now = nowMillis()
        val out = LinkedHashMap<String, Long>()
        var changed = false
        for ((k, deadline) in until) {
            val remaining = deadline - now
            if (remaining > 0) {
                out[k] = remaining
            } else {
                until.remove(k, deadline)
                changed = true
            }
        }
        if (changed) persist()
        return out
    }

    // ---------------------------------------------------------------- 记录

    /**
     * 记一次「该模型的失败」，返回**是否因此触发了禁用**。
     *
     * 只有调用方判定为「真正的失败」（模型级不可用、上游持续报错）时才该调这里；
     * 客户端自身错误（400/参数非法/内容审核）不该累计——换哪个模型都一样错。
     *
     * @param threshold 连续失败多少次触发禁用
     * @param banDurationMs 禁用时长
     */
    fun recordFailure(
        providerId: String,
        model: String,
        threshold: Int,
        banDurationMs: Long,
    ): Boolean {
        if (threshold <= 0 || banDurationMs <= 0) return false
        val now = nowMillis()
        val dayStart = startOfDay(now)
        val k = key(providerId, model)

        val next = consecutiveFailures.compute(k) { _, current ->
            // 跨天自动清零：昨天攒的失败不该算到今天头上
            if (current == null || current.dayStart != dayStart) {
                FailureCounter(1, dayStart)
            } else {
                FailureCounter(current.count + 1, dayStart)
            }
        } ?: return false

        if (next.count < threshold) return false

        // 达到阈值：禁用并清空计数（否则下一天会立刻又满）
        until[k] = now + banDurationMs
        consecutiveFailures.remove(k)
        persist()
        return true
    }

    /** 成功一次即清零该模型的连续失败计数（说明它其实能用）。 */
    fun recordSuccess(providerId: String, model: String) {
        val k = key(providerId, model)
        if (consecutiveFailures.remove(k) != null) {
            // 计数清空不需要落盘：它本来就是「当天」的瞬态数据
        }
    }

    /** 当前连续失败次数（仅用于测试与诊断）。 */
    fun failureCount(providerId: String, model: String): Int {
        val k = key(providerId, model)
        val counter = consecutiveFailures[k] ?: return 0
        // 跨天读到的旧计数按 0 处理
        return if (counter.dayStart == startOfDay(nowMillis())) counter.count else 0
    }

    /** 手动解除某个模型的禁用（界面上「解除禁用」按钮）。 */
    fun unban(providerId: String, model: String) {
        val removed = until.remove(key(providerId, model)) != null
        consecutiveFailures.remove(key(providerId, model))
        if (removed) persist()
    }

    /** 清空全部禁用（设置页「全部解除」）。 */
    fun clearAll() {
        until.clear()
        consecutiveFailures.clear()
        persist()
    }

    // ---------------------------------------------------------------- 落盘

    private fun persist() {
        val store = store ?: return
        val obj = JsonObject()
        val now = nowMillis()
        for ((k, deadline) in until) {
            if (deadline > now) obj.addProperty(k, deadline)
        }
        runCatching { store.write(STORE_KEY, obj.toString()) }
    }

    private fun restore() {
        val store = store ?: return
        val raw = runCatching { store.read(STORE_KEY) }.getOrNull() ?: return
        val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return
        val now = nowMillis()
        for ((k, value) in obj.entrySet()) {
            val deadline = runCatching { value.asLong }.getOrNull() ?: continue
            // 读进来时顺手丢掉已过期的：App 关了三天再打开，那些禁用早该失效了
            if (deadline > now) until[k] = deadline
        }
    }

    companion object {
        const val STORE_KEY = "settings/model-bans.json"

        /** 默认连续失败阈值。 */
        const val DEFAULT_THRESHOLD = 3

        /** 默认禁用时长：24 小时。 */
        const val DEFAULT_BAN_DURATION_MS = 24L * 60 * 60 * 1000

        const val MIN_THRESHOLD = 1
        const val MAX_THRESHOLD = 20

        /** 禁用时长上下限：最短 1 小时，最长 30 天。 */
        const val MIN_BAN_DURATION_MS = 60L * 60 * 1000
        const val MAX_BAN_DURATION_MS = 30L * 24 * 60 * 60 * 1000

        fun clampThreshold(value: Int): Int = value.coerceIn(MIN_THRESHOLD, MAX_THRESHOLD)

        fun clampBanDuration(value: Long): Long =
            value.coerceIn(MIN_BAN_DURATION_MS, MAX_BAN_DURATION_MS)

        /**
         * 当天零点（本地时区）。
         *
         * 用本地零点而不是 UTC：用户说的「今天」是自己时区的今天，
         * 用 UTC 会让东八区的人在早上 8 点前遇到「跨天」。
         */
        fun startOfDay(millis: Long): Long {
            val zone = java.util.TimeZone.getDefault()
            val calendar = java.util.Calendar.getInstance(zone).apply {
                timeInMillis = millis
                set(java.util.Calendar.HOUR_OF_DAY, 0)
                set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0)
                set(java.util.Calendar.MILLISECOND, 0)
            }
            return calendar.timeInMillis
        }
    }
}
