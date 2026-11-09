package io.synapse.core.provider.cline

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.synapse.core.provider.DeviceAuthPoll
import io.synapse.core.provider.ProviderHooks
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cline 设备授权（WorkOS RFC 8628）回归。
 *
 * 四步链路里**有 4 个状态分支**（pending / 过期 / 拒绝 / 成功），
 * 手工点真机覆盖不完，所以这里用本地假上游逐个锁住。
 *
 * 最要紧的两条：
 * - 两个 WorkOS 端点只认 **form 编码**（发 JSON 会 400 `invalid_request`，
 *   与「码不对」难以区分）；
 * - 第 4 步 `/auth/register` **不能省**（WorkOS 令牌不能直接打 LLM）。
 */
class ClineLoginTest {

    private var server: HttpServer? = null
    private val lastFormBody = AtomicReference("")
    private val lastPath = AtomicReference("")
    private val registerCalled = AtomicReference(false)

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    /** 造一个假的 JWT（只有 payload 有意义，签名是摆设）。 */
    private fun fakeJwt(vararg claims: Pair<String, Any>): String {
        val header = """{"alg":"RS256","typ":"JWT"}"""
        val payload = claims.joinToString(",", "{", "}") { (k, v) ->
            if (v is Number) "\"$k\":$v" else "\"$k\":\"$v\""
        }
        val enc = { text: String ->
            Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
        }
        return "${enc(header)}.${enc(payload)}.sig"
    }

    private fun startServer(
        authorizeResponse: Pair<Int, String>,
        authenticateResponse: Pair<Int, String>,
        registerResponse: Pair<Int, String> = 200 to """{"data":{"accessToken":"eyJreg"},"success":true}""",
    ): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange: HttpExchange ->
            val path = exchange.requestURI.path
            lastPath.set(path)
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            if (path.contains("authenticate") || path.contains("authorize")) {
                lastFormBody.set(body)
            }
            if (path.contains("register")) registerCalled.set(true)
            val (status, text) = when {
                path.contains("authorize/device") -> authorizeResponse
                path.contains("authenticate") -> authenticateResponse
                path.contains("register") -> registerResponse
                else -> 404 to "{}"
            }
            val bytes = text.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private fun login(base: String) = ClineLogin(ProviderHooks(), System::currentTimeMillis, base, base)

    // ---------------------------------------------------------------- 第一步

    @Test
    fun `发起授权用 form 编码，且 state 里放 device_code`() {
        val base = startServer(
            authorizeResponse = 200 to """
                {"device_code":"dev-123","user_code":"ABCD-EFGH",
                 "verification_uri":"https://authkit.cline.bot/device",
                 "verification_uri_complete":"https://authkit.cline.bot/device?user_code=ABCD-EFGH",
                 "expires_in":300,"interval":5}
            """.trimIndent(),
            authenticateResponse = 200 to "{}",
        )
        val ticket = login(base).start()
        // 优先用 verification_uri_complete（码已预填，用户少一步）
        assertTrue(ticket.loginUrl.contains("user_code=ABCD-EFGH"), ticket.loginUrl)
        // state 就是 device_code：轮询只需它，网关不需要保存任何额外状态
        assertEquals("dev-123", ticket.state)
        // WorkOS 只认 form 编码；发 JSON 会 400 invalid_request，与「码不对」难分辨
        assertTrue(lastFormBody.get().contains("client_id="), lastFormBody.get())
        assertTrue(!lastFormBody.get().trimStart().startsWith("{"), "不能发 JSON：${lastFormBody.get()}")
    }

    @Test
    fun `授权响应缺字段时抛可读错误`() {
        val base = startServer(
            authorizeResponse = 200 to """{"user_code":"ABCD"}""",
            authenticateResponse = 200 to "{}",
        )
        val error = runCatching { login(base).start() }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error.message!!.contains("device_code"), error.message)
    }

    // ---------------------------------------------------------------- 第三步

    @Test
    fun `pending 与 slow_down 都只是继续轮询，不是错误`() {
        // 当错误处理会让用户刚在浏览器里授权完就看到红字
        for (code in listOf("authorization_pending", "slow_down")) {
            val base = startServer(
                authorizeResponse = 200 to "{}",
                authenticateResponse = 400 to """{"error":"$code"}""",
            )
            val poll = login(base).poll("dev-123")
            assertEquals(DeviceAuthPoll.Pending, poll, "$code 应继续轮询")
            server?.stop(0)
            server = null
        }
    }

    @Test
    fun `expired_token 与 access_denied 给出可读原因`() {
        val expired = startServer(
            authorizeResponse = 200 to "{}",
            authenticateResponse = 400 to """{"error":"expired_token"}""",
        )
        val expiredPoll = login(expired).poll("dev-1") as DeviceAuthPoll.Failed
        assertTrue(expiredPoll.error.contains("过期"), expiredPoll.error)

        server?.stop(0)
        val denied = startServer(
            authorizeResponse = 200 to "{}",
            authenticateResponse = 400 to """{"error":"access_denied"}""",
        )
        val deniedPoll = login(denied).poll("dev-1") as DeviceAuthPoll.Failed
        assertTrue(deniedPoll.error.contains("拒绝"), deniedPoll.error)
    }

    @Test
    fun `空 state 直接失败，不打上游`() {
        val poll = login("http://127.0.0.1:1").poll("")
        assertTrue(poll is DeviceAuthPoll.Failed)
        assertTrue((poll as DeviceAuthPoll.Failed).error.contains("重新发起"), poll.error)
    }

    // ---------------------------------------------------------------- 第四步

    @Test
    fun `成功后自动完成第四步换会话令牌`() {
        val access = fakeJwt(
            "external_id" to "usr-abc",
            "email" to "me@example.com",
            "exp" to 1789846204L,
        )
        val base = startServer(
            authorizeResponse = 200 to "{}",
            authenticateResponse = 200 to """{"access_token":"$access","refresh_token":"workos-rv"}""",
            registerResponse = 200 to """{"data":{"accessToken":"session-token","refreshToken":"session-rv"},"success":true}""",
        )
        val poll = login(base).poll("dev-123")
        assertTrue(poll is DeviceAuthPoll.Success, "应成功：$poll")
        val account = (poll as DeviceAuthPoll.Success).account
        // 第 4 步不能省：WorkOS 令牌不能直接打 LLM
        assertTrue(registerCalled.get(), "必须调用 /auth/register")
        // 拿到的是会话令牌，不是 WorkOS 令牌
        val credential = parseClineCredential(account.secret)
        assertNotNull(credential)
        assertEquals("workos:session-token", credential.accessToken)
        assertEquals("session-rv", credential.refreshToken)
        // uid 用 JWT 里的 external_id（余额路径要的那个值）
        assertEquals("usr-abc", account.uid)
        // 展示名优先邮箱
        assertEquals("me@example.com", account.nickname)
        // exp 转成毫秒
        assertEquals(1789846204000L, credential.expiresAt)
    }

    @Test
    fun `register 不给 refreshToken 时沿用 WorkOS 的`() {
        val access = fakeJwt("external_id" to "usr-1")
        val base = startServer(
            authorizeResponse = 200 to "{}",
            authenticateResponse = 200 to """{"access_token":"$access","refresh_token":"workos-rv"}""",
            // 成功响应里没有 refreshToken
            registerResponse = 200 to """{"data":{"accessToken":"session-token"},"success":true}""",
        )
        val poll = login(base).poll("dev-123") as DeviceAuthPoll.Success
        val credential = parseClineCredential(poll.account.secret)
        assertNotNull(credential)
        // 沿用 WorkOS 的 refresh token，否则账号失去续期能力
        assertEquals("workos-rv", credential.refreshToken)
    }

    @Test
    fun `register 失败时如实报错`() {
        val base = startServer(
            authorizeResponse = 200 to "{}",
            authenticateResponse = 200 to """{"access_token":"a.b.c","refresh_token":"rv"}""",
            registerResponse = 502 to """{"error":"upstream down"}""",
        )
        val poll = login(base).poll("dev-123")
        assertTrue(poll is DeviceAuthPoll.Failed, "应失败：$poll")
        assertTrue((poll as DeviceAuthPoll.Failed).error.contains("登记失败"), poll.error)
    }

    @Test
    fun `authenticate 返回缺 access_token 时报错`() {
        val base = startServer(
            authorizeResponse = 200 to "{}",
            authenticateResponse = 200 to """{"refresh_token":"rv"}""",
        )
        val poll = login(base).poll("dev-123")
        assertTrue(poll is DeviceAuthPoll.Failed)
        assertTrue((poll as DeviceAuthPoll.Failed).error.contains("access_token"), poll.error)
    }
}
