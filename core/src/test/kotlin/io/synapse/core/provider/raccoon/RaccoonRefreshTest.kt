package io.synapse.core.provider.raccoon

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.synapse.core.provider.ProviderAccount
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 小浣熊凭证刷新的回归。
 *
 * 上游风控的关键在这里：**刷新后的过期时间绝不能沿用旧值**。
 * 能走到刷新就说明旧值已临期，沿用会让之后每个请求都再刷一次，
 * 刷新频率等于请求频率——正是上游吊销 token、用户看到「登录失效」的触发条件。
 *
 * 另两条：刷新端点回 200 但形状不对时不能算成功（否则会带着空 token 打上游），
 * 以及刚失败过的账号在冷却期内不该被反复重刷。
 */
class RaccoonRefreshTest {

    private val nowSeconds = 1_700_000_000L

    /** 造一个 JWT（只需要 header.payload.signature 三段可解码，exp 用得上）。 */
    private fun jwt(exp: Long, userId: String = "u1"): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"none"}""".toByteArray())
        val payload = enc.encodeToString("""{"exp":$exp,"user_id":"$userId"}""".toByteArray())
        return "$header.$payload.sig"
    }

    private fun account(expiresAt: Long, refreshToken: String = "r1") = ProviderAccount(
        providerId = RaccoonProvider.ID,
        uid = "u1",
        nickname = "测试",
        secret = JsonObject().apply {
            addProperty("accessToken", jwt(expiresAt))
            addProperty("refreshToken", refreshToken)
            addProperty("expiresAt", expiresAt)
            addProperty("userId", "u1")
            addProperty("nickname", "测试")
        }.toString(),
    )

    /** 假刷新端点：按 [responder] 回包，并记录被调用次数。 */
    private class FakeRefresh(private val responder: (HttpExchange) -> Unit) {
        var calls = 0
        private lateinit var server: HttpServer

        fun start(): String {
            server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/api/web/auth/v1/refresh") { exchange ->
                calls++
                exchange.requestBody.use { it.readBytes() }
                responder(exchange)
            }
            server.start()
            return "http://127.0.0.1:${server.address.port}"
        }

        fun stop() = server.stop(0)
    }

    private fun respond(exchange: HttpExchange, body: String, status: Int = 200) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    // ---------------------------------------------------------------- 用例

    @Test
    fun `刷新响应没给过期时间时用 token 的 exp，绝不沿用旧值`() {
        val newToken = jwt(nowSeconds + 7200)
        val upstream = FakeRefresh { respond(it, """{"status":0,"data":{"access_token":"$newToken"}}""") }
        val base = upstream.start()
        try {
            val provider = RaccoonProvider(nowMillis = { nowSeconds * 1000 }, host = base)
            // 旧凭证已过期（临期窗口内才会触发刷新）
            val refreshed = provider.refreshAccount(account(expiresAt = nowSeconds - 10), skewSeconds = 60)
            assertNotNull(refreshed, "刷新应当成功")
            val expiresAt = JsonObject().let {
                com.google.gson.JsonParser.parseString(refreshed.secret).asJsonObject.get("expiresAt").asLong
            }
            assertEquals(nowSeconds + 7200, expiresAt, "应当采用新 token 的 exp，而不是沿用旧的已过期时间")
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `刷新响应给相对秒数时按 now 加上去`() {
        val newToken = jwt(0) // 故意不给 exp，逼它走 expires_in
        val upstream = FakeRefresh { respond(it, """{"status":0,"data":{"access_token":"$newToken","expires_in":1800}}""") }
        val base = upstream.start()
        try {
            val provider = RaccoonProvider(nowMillis = { nowSeconds * 1000 }, host = base)
            val refreshed = provider.refreshAccount(account(expiresAt = nowSeconds - 10), skewSeconds = 60)
            assertNotNull(refreshed)
            val expiresAt =
                com.google.gson.JsonParser.parseString(refreshed.secret).asJsonObject.get("expiresAt").asLong
            assertEquals(nowSeconds + 1800, expiresAt, "相对秒数应当换算成绝对时间")
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `刷新响应形状不对时算失败而不是带空 token 打上游`() {
        val upstream = FakeRefresh { respond(it, """{"status":0,"data":{}}""") }
        val base = upstream.start()
        try {
            val provider = RaccoonProvider(nowMillis = { nowSeconds * 1000 }, host = base)
            assertNull(provider.refreshAccount(account(expiresAt = nowSeconds - 10), skewSeconds = 60))
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `刷新失败后有冷却，不会按请求频率轰炸端点`() {
        val upstream = FakeRefresh { respond(it, """{"status":1,"msg":"bad"}""") }
        val base = upstream.start()
        try {
            val provider = RaccoonProvider(nowMillis = { nowSeconds * 1000 }, host = base)
            val acc = account(expiresAt = nowSeconds - 10)
            assertNull(provider.refreshAccount(acc, skewSeconds = 60))
            assertNull(provider.refreshAccount(acc, skewSeconds = 60))
            assertNull(provider.refreshAccount(acc, skewSeconds = 60))
            assertEquals(1, upstream.calls, "冷却期内不该重复请求刷新端点")
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `过期且刷新连续失败的账号报可重试错误，不带过期 token 打上游`() {
        val upstream = FakeRefresh { respond(it, """{"status":1,"msg":"bad"}""") }
        val base = upstream.start()
        try {
            val provider = RaccoonProvider(nowMillis = { nowSeconds * 1000 }, host = base)
            val acc = account(expiresAt = nowSeconds - 10)
            provider.refreshAccount(acc, skewSeconds = 60)

            val call = provider.openChat(acc, """{"model":"x","messages":[]}""")
            call.close()
            assertEquals(503, call.status, "过期且刷新失败应报 503（可重试），而不是 401 把账号打成失效")
            assertEquals(1, upstream.calls, "刷新失败后不该再去打刷新端点")
        } finally {
            upstream.stop()
        }
    }

    @Test
    fun `失败超过上限后不再护着，让真死会话照常暴露`() {
        val upstream = FakeRefresh { respond(it, """{"status":1,"msg":"dead"}""") }
        val base = upstream.start()
        try {
            val provider = RaccoonProvider(nowMillis = { nowSeconds * 1000 }, host = base)
            assertTrue(
                provider.shouldShieldExpiredCredential(expiresAt = nowSeconds - 10, streak = 1, nowSeconds = nowSeconds),
            )
            assertTrue(
                provider.shouldShieldExpiredCredential(expiresAt = nowSeconds - 10, streak = 2, nowSeconds = nowSeconds),
            )
            // 连续失败 3 次：refresh_token 大概率真被吊销，放行让 401 → 重新登录
            assertTrue(
                !provider.shouldShieldExpiredCredential(expiresAt = nowSeconds - 10, streak = 3, nowSeconds = nowSeconds),
            )
            // 没过期的凭证不需要护
            assertTrue(
                !provider.shouldShieldExpiredCredential(expiresAt = nowSeconds + 100, streak = 1, nowSeconds = nowSeconds),
            )
        } finally {
            upstream.stop()
        }
    }
}
