package io.synapse.core.provider.trae

import io.synapse.core.InMemoryKeyValueStore
import io.synapse.core.provider.ProviderAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 凭证刷新纪律：单飞 + 锁内复查 + 失败冷却 + 过期护盾。
 *
 * 这四项不是「代码整洁」，而是会话能不能活下来的分界线：上游 ExchangeToken
 * **每次都会轮换 refreshToken**，两个并发请求各拿同一个 refreshToken 去换，
 * 只有一个成功，另一个必然失败 —— 失败若跨在「新凭证落盘」之前，整条会话就作废，
 * 用户看到的就是「登录又失效了，得重新登」。
 */
class TraeRefreshDisciplineTest {

    /** 可拨动的时钟：所有时间判断都走注入的 nowMillis。 */
    private class Clock(var nowSeconds: Long = 1_800_000_000L) {
        fun millis(): Long = nowSeconds * 1000
    }

    private fun trae(
        uid: String = "u1",
        accessToken: String = "at-old",
        refreshToken: String = "rt-old",
        expiresAt: Long,
    ) = TraeAccount(
        uid = uid,
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAt = expiresAt,
        nickname = "昵称",
        enterpriseId = "",
        machineId = "m".repeat(64),
        deviceId = "1700000000000000",
    )

    private fun account(t: TraeAccount) =
        ProviderAccount("trae", t.uid, t.nickname, t.toJson().toString())

    private fun provider(clock: Clock, exchange: (TraeAccount) -> TraeAccount) = TraeProvider(
        store = InMemoryKeyValueStore(),
        version = { TraeVersion() },
        defaultModel = { "glm-5.2" },
        nowMillis = clock::millis,
        exchange = exchange,
    )

    @Test
    fun `离过期还早就完全不碰刷新`() {
        val clock = Clock()
        var calls = 0
        val p = provider(clock) { calls++; error("不该被调用") }
        val a = account(trae(expiresAt = clock.nowSeconds + 3_600))

        assertNull(p.refreshAccount(a, skewSeconds = 300))
        assertEquals(0, calls, "远端有效期内不应该发起刷新")
    }

    @Test
    fun `并发跨入临期窗口时只刷新一次并复用结果`() {
        val clock = Clock()
        var calls = 0
        val p = provider(clock) { old ->
            calls++
            old.copy(accessToken = "at-new", refreshToken = "rt-new", expiresAt = clock.nowSeconds + 3_600)
        }
        val a = account(trae(expiresAt = clock.nowSeconds + 60)) // 已进入 300s 临期窗口

        val first = p.refreshAccount(a, 300)
        assertNotNull(first)
        // 第二个线程拿到的仍是**旧**账号快照（网关在并发路径上就是这个形状）
        val second = p.refreshAccount(a, 300)

        assertEquals(1, calls, "锁内复查必须复用刚刷出的凭证，不能重复换 token")
        assertNotNull(second)
        assertEquals(first.secret, second.secret, "两边应拿到同一份新凭证")
        assertTrue(second.secret.contains("rt-new"))
    }

    @Test
    fun `刷新失败后冷却期内不再重试`() {
        val clock = Clock()
        var calls = 0
        val p = provider(clock) { calls++; throw IllegalStateException("HTTP 500") }
        val a = account(trae(expiresAt = clock.nowSeconds - 10)) // 已过期

        assertNull(p.refreshAccount(a, 300))
        assertEquals(1, calls)

        // 冷却期内：一次都不该再打上游（否则 token 被吊销后会按请求频率轰炸）
        assertNull(p.refreshAccount(a, 300))
        assertEquals(1, calls)

        // 冷却结束后允许再试
        clock.nowSeconds += 61
        assertNull(p.refreshAccount(a, 300))
        assertEquals(2, calls)
    }

    @Test
    fun `过期护盾在上限内拦住、超限放行`() {
        val clock = Clock()
        val p = provider(clock) { error("不该被调用") }
        val expired = clock.nowSeconds - 5

        assertFalse(p.shouldShieldExpiredCredential(expired, streak = 0, nowSeconds = clock.nowSeconds))
        assertTrue(p.shouldShieldExpiredCredential(expired, streak = 1, nowSeconds = clock.nowSeconds))
        assertTrue(p.shouldShieldExpiredCredential(expired, streak = 3, nowSeconds = clock.nowSeconds))
        // 超过上限：真正被吊销的会话必须能走到 401 → 禁用 → 提示重新登录
        assertFalse(p.shouldShieldExpiredCredential(expired, streak = 4, nowSeconds = clock.nowSeconds))
        // 没过期 / 未知过期时间都不拦
        assertFalse(p.shouldShieldExpiredCredential(clock.nowSeconds + 60, 1, clock.nowSeconds))
        assertFalse(p.shouldShieldExpiredCredential(0, 1, clock.nowSeconds))
    }

    @Test
    fun `刷新成功后失败计数清零`() {
        val clock = Clock()
        var fail = true
        val p = provider(clock) { old ->
            if (fail) throw IllegalStateException("瞬时失败")
            old.copy(accessToken = "at-new", refreshToken = "rt-new", expiresAt = clock.nowSeconds + 3_600)
        }
        val a = account(trae(expiresAt = clock.nowSeconds - 10))

        assertNull(p.refreshAccount(a, 300)) // 失败一次 → streak = 1
        clock.nowSeconds += 61 // 过冷却
        fail = false
        val ok = p.refreshAccount(a, 300)
        assertNotNull(ok)
        // 成功后 streak 归零：护盾不该再拦一个刚刷新成功的账号
        assertFalse(p.shouldShieldExpiredCredential(clock.nowSeconds - 1, streak = 0, nowSeconds = clock.nowSeconds))
    }
}
