package io.synapse.core.provider.codebuddy

import io.synapse.core.provider.ErrorKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 429「该模型频率超限」必须判成 MODEL_LIMIT，而不是 SOFT_RATE。
 *
 * 现场问题：上游回的是
 * ```
 * usage exceeds frequency limit, but don't worry, your usage will reset at
 * 2026-10-31 19:26:07 UTC+8, alternatively, you can switch to the other models...
 * ```
 * 但代码只认业务码 6004，这种没带 code 的回包被判成 SOFT_RATE →
 * 网关只在**同一个账号**上退避重试，不会换号。
 *
 * 而这类限制实际是**按账号**算的：换个号立刻能跑。判错的直接后果就是
 * 主人看到的「同供应商明明还有别的号，却没换」。
 */
class CodeBuddyRateLimitClassifyTest {

    private val provider = CodeBuddyProvider()

    @Test
    fun `没有业务码但文案是 usage exceeds frequency limit 时判模型级限制`() {
        val body = """{"error":{"message":"usage exceeds frequency limit, but don't worry, """ +
            """your usage will reset at 2026-10-31 19:26:07 UTC+8, alternatively, """ +
            """you can switch to the other models to continue using it."}}"""
        assertEquals(ErrorKind.MODEL_LIMIT, provider.classify(429, body).kind)
    }

    @Test
    fun `带 6004 业务码仍判模型级限制`() {
        assertEquals(
            ErrorKind.MODEL_LIMIT,
            provider.classify(429, """{"code":6004,"msg":"该模型已超限"}""").kind,
        )
    }

    @Test
    fun `账号级限流仍判可退避重试`() {
        // 没有模型字样、也没有 6004 → 是整账号限流，应在同一个号上退避，而不是换号
        val body = """{"error":{"message":"too many requests, please slow down"}}"""
        assertEquals(ErrorKind.SOFT_RATE, provider.classify(429, body).kind)
    }

    @Test
    fun `非 429 的模型级超限文案同样识别`() {
        val body = """{"msg":"该模型已超限，请稍后再试"}"""
        assertEquals(ErrorKind.MODEL_LIMIT, provider.classify(400, body).kind)
    }

    @Test
    fun `凭证失效优先于限流判断`() {
        assertEquals(ErrorKind.SESSION_DEAD, provider.classify(401, """{"msg":"unauthorized"}""").kind)
        assertEquals(ErrorKind.SESSION_DEAD, provider.classify(403, """{"msg":"forbidden"}""").kind)
    }
}
