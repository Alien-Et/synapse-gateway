package io.synapse.core.provider.cline

import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cline 凭证解析回归。
 *
 * 这里锁住三处「同名不同型」的坑（都是实测踩出来的，改动前先读
 * [ClineCredential] 的类注释）：
 *
 * 1. `workos:` 前缀必须带上，否则上游 401；
 * 2. `expiresAt` 有毫秒数与 ISO 字符串两种形态；
 * 3. 桌面端 `providers.json` 与自存的扁平形态都要能读。
 */
class ClineCredentialTest {

    @Test
    fun `缺 workos 前缀时补上，已带前缀时幂等`() {
        assertEquals("workos:eyJhbGci", ensureTokenPrefix("eyJhbGci"))
        // 幂等：登录返回的带前缀，续期返回的不带，两种都能直接传进来
        assertEquals("workos:eyJhbGci", ensureTokenPrefix("workos:eyJhbGci"))
        assertEquals("workos:eyJhbGci", ensureTokenPrefix("  eyJhbGci  "))
        // 空串不补成 "workos:"（那会变成一个看起来有效的空令牌）
        assertEquals("", ensureTokenPrefix(""))
        assertEquals("", ensureTokenPrefix("   "))
    }

    @Test
    fun `expiresAt 支持毫秒数与秒数`() {
        val millis = parseExpiresAt(JsonObject().apply { addProperty("expiresAt", 1789842888000L) })
        assertEquals(1789842888000L, millis)
        // 秒级时间戳（10 位）也要认：上游两种都出现过
        val seconds = parseExpiresAt(JsonObject().apply { addProperty("expiresAt", 1789842888L) })
        assertEquals(1789842888000L, seconds)
    }

    @Test
    fun `expiresAt 支持 ISO 8601 字符串`() {
        // /auth/refresh 返回的是 ISO 字符串，不是毫秒数（同名不同型）
        val iso = parseExpiresAt(JsonObject().apply { addProperty("expiresAt", "2026-09-19T19:30:04Z") })
        assertEquals(1789846204000L, iso)
        // 带时区偏移也要认
        val offset = parseExpiresAt(JsonObject().apply { addProperty("expiresAt", "2026-09-20T03:30:04+08:00") })
        assertEquals(iso, offset)
    }

    @Test
    fun `expiresAt 缺失或无法解析时返回 0 而不是当前时间`() {
        // 返回当前时间会让凭证立刻被判过期，之后每次请求都去续期
        assertEquals(0L, parseExpiresAt(JsonObject()))
        assertEquals(0L, parseExpiresAt(JsonObject().apply { addProperty("expiresAt", "not-a-date") }))
        assertEquals(0L, parseExpiresAt(JsonObject().apply { addProperty("expiresAt", "") }))
    }

    @Test
    fun `解析扁平形态的凭证`() {
        val credential = parseClineCredential(
            """{"accessToken":"eyJhbGci","refreshToken":"rv","expiresAt":1789842888000,"accountId":"usr-1","email":"a@b.c","name":"测试"}""",
        )
        assertNotNull(credential)
        assertEquals("workos:eyJhbGci", credential.accessToken)
        assertEquals("rv", credential.refreshToken)
        assertEquals(1789842888000L, credential.expiresAt)
        assertEquals("usr-1", credential.accountId)
        assertEquals("a@b.c", credential.email)
    }

    @Test
    fun `解析桌面端 auth 片段（metadata 里嵌 userInfo）`() {
        val credential = parseClineCredential(
            """{"accessToken":"workos:eyJhbGci","refreshToken":"rv","expiresAt":1789842888000,
                "accountId":"usr-2","metadata":{"provider":"cline","userInfo":{"email":"x@y.z","name":"小明"}}}""",
        )
        assertNotNull(credential)
        // 已带前缀时不能被补成 workos:workos:
        assertEquals("workos:eyJhbGci", credential.accessToken)
        assertEquals("x@y.z", credential.email)
        assertEquals("小明", credential.name)
    }

    @Test
    fun `缺少 accessToken 时解析失败`() {
        assertNull(parseClineCredential("""{"refreshToken":"rv"}"""))
        assertNull(parseClineCredential("not json"))
        assertNull(parseClineCredential(""))
    }

    @Test
    fun `写回 secret 永远只落毫秒形态`() {
        val credential = ClineCredential(
            accessToken = "workos:eyJ",
            refreshToken = "rv",
            expiresAt = 1789842888000L,
            accountId = "usr-1",
            email = "",
            name = "",
        )
        val secret = credential.toSecret()
        // 落盘只有一种形态：存两种会让「读回来要不要判断类型」变成每个调用点的心智负担
        assertTrue(secret.contains("\"expiresAt\":1789842888000"), secret)
        // 空字段不写（避免界面显示空白格）
        assertFalse(secret.contains("\"email\""), secret)
        // 往返一致
        val roundTrip = parseClineCredential(secret)
        assertNotNull(roundTrip)
        assertEquals(credential.accessToken, roundTrip.accessToken)
        assertEquals(credential.expiresAt, roundTrip.expiresAt)
    }

    @Test
    fun `过期判定按 skew 窗口`() {
        val now = 1_000_000L
        val credential = ClineCredential("workos:a", "r", now + 60_000, "", "", "")
        // 提前 120 秒刷新 → 已经在窗口内
        assertTrue(credential.expiresWithin(120, now))
        // 提前 30 秒刷新 → 还没到
        assertFalse(credential.expiresWithin(30, now))
        // 没有过期时间时不算过期（不能因为「未知」就反复续期）
        assertFalse(ClineCredential("workos:a", "r", 0, "", "", "").expiresWithin(9999, now))
    }
}
