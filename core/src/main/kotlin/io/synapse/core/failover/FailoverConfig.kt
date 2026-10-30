package io.synapse.core.failover

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.random.Random

/** 单供应商内部的退避重试参数 */
data class RetryConfig(
    val maxAttempts: Int = 3,         // 最大尝试次数（含首次请求）
    val initialBackoffMs: Long = 300L,// 初始退避毫秒数
    val maxBackoffMs: Long = 2000L,   // 退避上限
    val backoffMultiplier: Double = 2.0,
    val jitterRatio: Double = 0.25,   // 随机抖动比例 ±25%
) {
    /** 计算第 attempt 次重试前需要休眠的毫秒数（1-based，首次请求 attempt=1 不休眠） */
    fun computeBackoffMillis(attempt: Int): Long {
        if (attempt <= 1) return 0L
        val base = (initialBackoffMs * Math.pow(backoffMultiplier, (attempt - 2).toDouble())).toLong()
        val capped = minOf(base, maxBackoffMs)
        val jitter = (capped * jitterRatio).toLong()
        val delta = if (jitter > 0) Random.nextLong(-jitter, jitter + 1) else 0L
        return maxOf(0L, capped + delta)
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("maxAttempts", maxAttempts)
        addProperty("initialBackoffMs", initialBackoffMs)
        addProperty("maxBackoffMs", maxBackoffMs)
        addProperty("backoffMultiplier", backoffMultiplier)
        addProperty("jitterRatio", jitterRatio)
    }

    companion object {
        fun fromJson(obj: JsonObject?): RetryConfig {
            if (obj == null) return RetryConfig()
            return RetryConfig(
                maxAttempts = obj.get("maxAttempts")?.asInt?.coerceIn(1, 10) ?: 3,
                initialBackoffMs = obj.get("initialBackoffMs")?.asLong?.coerceIn(0L, 10_000L) ?: 300L,
                maxBackoffMs = obj.get("maxBackoffMs")?.asLong?.coerceIn(0L, 60_000L) ?: 2000L,
                backoffMultiplier = obj.get("backoffMultiplier")?.asDouble?.coerceIn(1.0, 5.0) ?: 2.0,
                jitterRatio = obj.get("jitterRatio")?.asDouble?.coerceIn(0.0, 1.0) ?: 0.25,
            )
        }
    }
}

/** 熔断参数 */
data class CircuitBreakerConfig(
    val failureThreshold: Int = 5,    // 触发熔断的连续失败次数
    val openDurationMs: Long = 60_000L,// 熔断打开静默时长（60秒）
    val halfOpenSuccessCount: Int = 2, // 半开状态下连续成功几次才闭合复原
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("failureThreshold", failureThreshold)
        addProperty("openDurationMs", openDurationMs)
        addProperty("halfOpenSuccessCount", halfOpenSuccessCount)
    }

    companion object {
        fun fromJson(obj: JsonObject?): CircuitBreakerConfig {
            if (obj == null) return CircuitBreakerConfig()
            return CircuitBreakerConfig(
                failureThreshold = obj.get("failureThreshold")?.asInt?.coerceIn(1, 100) ?: 5,
                openDurationMs = obj.get("openDurationMs")?.asLong?.coerceIn(1000L, 3600_000L) ?: 60_000L,
                halfOpenSuccessCount = obj.get("halfOpenSuccessCount")?.asInt?.coerceIn(1, 10) ?: 2,
            )
        }
    }
}

/**
 * 模型级冷却参数。
 *
 * 上游对「该模型已超限」类错误只给一句重置文案，格式未验证，所以先给固定时长；
 * 冷却期内该模型直接跳过，不再换号、不计熔断。
 */
data class ModelCooldownConfig(
    /** 一次模型级限流后的冷却毫秒数（默认 10 分钟）。 */
    val cooldownMs: Long = 600_000L,
    /** 冷却时长上限（默认 2 小时），避免误判出超长冷却。 */
    val maxCooldownMs: Long = 7_200_000L,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("cooldownMs", cooldownMs)
        addProperty("maxCooldownMs", maxCooldownMs)
    }

    companion object {
        fun fromJson(obj: JsonObject?): ModelCooldownConfig {
            if (obj == null) return ModelCooldownConfig()
            return ModelCooldownConfig(
                cooldownMs = obj.get("cooldownMs")?.asLong?.coerceIn(1_000L, 7_200_000L) ?: 600_000L,
                maxCooldownMs = obj.get("maxCooldownMs")?.asLong?.coerceIn(1_000L, 7_200_000L) ?: 7_200_000L,
            )
        }
    }
}

/** 模型故障转移总配置 */
data class ModelFailoverSettings(
    val enabled: Boolean = true,
    val retry: RetryConfig = RetryConfig(),
    val circuitBreaker: CircuitBreakerConfig = CircuitBreakerConfig(),
    /** 上游请求并发闸门：全局与单账号在途上限。 */
    val concurrency: ConcurrencyConfig = ConcurrencyConfig(),
    /** 模型级冷却（针对该模型不可用类错误）。 */
    val modelCooldown: ModelCooldownConfig = ModelCooldownConfig(),
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("enabled", enabled)
        add("retry", retry.toJson())
        add("circuitBreaker", circuitBreaker.toJson())
        add("concurrency", concurrency.toJson())
        add("modelCooldown", modelCooldown.toJson())
    }

    companion object {
        const val STORE_KEY = "settings/failover.json"

        fun fromJson(raw: String?): ModelFailoverSettings {
            if (raw.isNullOrBlank()) return ModelFailoverSettings()
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return ModelFailoverSettings()
            return ModelFailoverSettings(
                enabled = obj.get("enabled")?.asBoolean ?: true,
                retry = RetryConfig.fromJson(obj.getAsJsonObject("retry")),
                circuitBreaker = CircuitBreakerConfig.fromJson(obj.getAsJsonObject("circuitBreaker")),
                concurrency = ConcurrencyConfig.fromJson(obj.getAsJsonObject("concurrency")),
                modelCooldown = ModelCooldownConfig.fromJson(obj.getAsJsonObject("modelCooldown")),
            )
        }
    }
}
