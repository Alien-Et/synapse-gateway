package io.synapse.core.failover

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class BreakerState { CLOSED, OPEN, HALF_OPEN }

class ProviderCircuitBreaker(
    val providerId: String,
    private val config: () -> CircuitBreakerConfig,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val state = AtomicReference(BreakerState.CLOSED)
    private val failureCount = AtomicInteger(0)
    private val halfOpenSuccessCount = AtomicInteger(0)
    private val lastStateChangeTime = AtomicLong(nowMillis())

    /** 检查是否允许请求通过 */
    fun allowRequest(): Boolean {
        val now = nowMillis()
        val cfg = config()
        when (state.get()) {
            BreakerState.CLOSED -> return true
            BreakerState.OPEN -> {
                // 检查熔断静默期是否已过；若已过则原子尝试转入 HALF_OPEN 阶段
                if (now - lastStateChangeTime.get() >= cfg.openDurationMs) {
                    if (state.compareAndSet(BreakerState.OPEN, BreakerState.HALF_OPEN)) {
                        lastStateChangeTime.set(now)
                        halfOpenSuccessCount.set(0)
                        return true
                    }
                }
                return false
            }
            BreakerState.HALF_OPEN -> {
                // 半开阶段允许探针请求通过
                return true
            }
        }
    }

    fun recordSuccess() {
        failureCount.set(0)
        val cfg = config()
        if (state.get() == BreakerState.HALF_OPEN) {
            if (halfOpenSuccessCount.incrementAndGet() >= cfg.halfOpenSuccessCount) {
                // 探针连续达标，自愈恢复到 CLOSED
                state.set(BreakerState.CLOSED)
                lastStateChangeTime.set(nowMillis())
            }
        }
    }

    fun recordFailure() {
        val count = failureCount.incrementAndGet()
        val now = nowMillis()
        val cfg = config()
        if (state.get() == BreakerState.HALF_OPEN || count >= cfg.failureThreshold) {
            state.set(BreakerState.OPEN)
            lastStateChangeTime.set(now)
        }
    }

    fun currentState(): BreakerState = state.get()

    /**
     * 熔断已打开时还剩多少毫秒恢复；未打开或半开时返回 0。
     *
     * 只用于给用户看「大概多久能恢复」，不参与状态机本身——
     * 真正的开合仍由 [allowRequest] / [recordFailure] 决定。
     */
    fun remainingOpenMs(): Long {
        if (state.get() != BreakerState.OPEN) return 0L
        val elapsed = nowMillis() - lastStateChangeTime.get()
        return (config().openDurationMs - elapsed).coerceAtLeast(0L)
    }
}
