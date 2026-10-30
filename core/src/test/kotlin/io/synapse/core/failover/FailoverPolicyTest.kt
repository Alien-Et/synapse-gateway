package io.synapse.core.failover

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FailoverPolicyTest {

    @Test
    fun `指数退避与抖动计算正确`() {
        val policy = RetryConfig(
            maxAttempts = 3,
            initialBackoffMs = 200L,
            maxBackoffMs = 1000L,
            backoffMultiplier = 2.0,
            jitterRatio = 0.2, // ±20%
        )

        // 首次尝试不退避
        assertEquals(0L, policy.computeBackoffMillis(1))

        // 第 2 次尝试基准 200ms，抖动范围 [160..240]
        val b2 = policy.computeBackoffMillis(2)
        assertTrue(b2 in 160L..240L, "第2次退避应在 [160..240]，实际: $b2")

        // 第 3 次尝试基准 400ms，抖动范围 [320..480]
        val b3 = policy.computeBackoffMillis(3)
        assertTrue(b3 in 320L..480L, "第3次退避应在 [320..480]，实际: $b3")

        // 极大重试次数时不超过 maxBackoffMs 上限
        val b10 = policy.computeBackoffMillis(10)
        assertTrue(b10 <= 1200L, "上限受控，实际: $b10")
    }

    @Test
    fun `错误分类与决策动作判定正确`() {
        // 客户端参数错、上下文超长、内容审核被拒：立即终止，绝不重试或换家
        assertEquals(
            FailoverDecision.ABORT_IMMEDIATELY,
            ClassifiedError(400, "invalid_request_error", "bad params").decide(),
        )
        assertEquals(
            FailoverDecision.ABORT_IMMEDIATELY,
            ClassifiedError(400, "context_length_exceeded", "too long").decide(),
        )
        assertEquals(
            FailoverDecision.ABORT_IMMEDIATELY,
            ClassifiedError(422, "content_policy_violation", "safety trigger").decide(),
        )

        // 凭证失效、租户被停用、额度耗尽：立即换家，不浪费本地尝试次数
        assertEquals(
            FailoverDecision.SWITCH_NEXT_PROVIDER,
            ClassifiedError(401, "unauthorized", "token dead").decide(),
        )
        assertEquals(
            FailoverDecision.SWITCH_NEXT_PROVIDER,
            ClassifiedError(403, "account_deactivated", "banned").decide(),
        )
        assertEquals(
            FailoverDecision.SWITCH_NEXT_PROVIDER,
            ClassifiedError(400, "insufficient_quota", "out of credits").decide(),
        )

        // 瞬时故障（网络中断、超时、429限流、5xx崩溃）：当前供应商内退避重试
        assertEquals(
            FailoverDecision.RETRY_CURRENT_PROVIDER,
            ClassifiedError(0, "timeout", "read timeout", isTimeout = true).decide(),
        )
        assertEquals(
            FailoverDecision.RETRY_CURRENT_PROVIDER,
            ClassifiedError(0, "connect_failed", "conn reset", isNetworkFailure = true).decide(),
        )
        assertEquals(
            FailoverDecision.RETRY_CURRENT_PROVIDER,
            ClassifiedError(429, "rate_limit", "too many requests").decide(),
        )
        assertEquals(
            FailoverDecision.RETRY_CURRENT_PROVIDER,
            ClassifiedError(500, "server_error", "internal crash").decide(),
        )
    }

    @Test
    fun `配置序列化与反序列化保持完整`() {
        val original = ModelFailoverSettings(
            enabled = true,
            retry = RetryConfig(maxAttempts = 3, initialBackoffMs = 400L, maxBackoffMs = 3000L),
            circuitBreaker = CircuitBreakerConfig(failureThreshold = 4, openDurationMs = 45_000L),
            concurrency = ConcurrencyConfig(maxPerAccount = 2, maxGlobal = 8, queueTimeoutMs = 30_000L),
            modelCooldown = ModelCooldownConfig(cooldownMs = 120_000L, maxCooldownMs = 600_000L),
        )

        val json = original.toJson().toString()
        val parsed = ModelFailoverSettings.fromJson(json)

        assertEquals(true, parsed.enabled)
        assertEquals(3, parsed.retry.maxAttempts)
        assertEquals(400L, parsed.retry.initialBackoffMs)
        assertEquals(4, parsed.circuitBreaker.failureThreshold)
        assertEquals(45_000L, parsed.circuitBreaker.openDurationMs)
        assertEquals(2, parsed.concurrency.maxPerAccount)
        assertEquals(8, parsed.concurrency.maxGlobal)
        assertEquals(120_000L, parsed.modelCooldown.cooldownMs)

        // 跨供应商 routes 字段已删除：带 routes 的历史 JSON 仍要能正常解析，
        // 只是忽略它（不报错、不丢其它字段）—— 升级不能让老配置直接失效。
        val legacy = """{"enabled":true,"retry":{"maxAttempts":5},"routes":{"m":[{"providerId":"trae","upstreamModel":"x"}]}}"""
        val fromLegacy = ModelFailoverSettings.fromJson(legacy)
        assertEquals(true, fromLegacy.enabled)
        assertEquals(5, fromLegacy.retry.maxAttempts)
    }
}
