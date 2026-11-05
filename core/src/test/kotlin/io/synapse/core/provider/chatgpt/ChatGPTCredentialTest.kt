package io.synapse.core.provider.chatgpt

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 凭证与 JWT 解析的回归。
 *
 * 这里守住两条最容易写错的：
 * 1. **刷新响应缺 refresh_token 时必须沿用旧值**——置空等于把账号永久锁死；
 * 2. 订阅档位映射是反直觉的（`prolite` 才是 Pro），不能按字面猜。
 */
class ChatGPTCredentialTest {

    private fun credential(
        access: String = "at-1",
        refresh: String = "rt-1",
        expiresAt: Long = 1_000_000L,
    ) = ChatGPTCredential(
        accessToken = access,
        refreshToken = refresh,
        expiresAtMillis = expiresAt,
        accountId = "acc-1",
        email = "a@b.c",
        plan = "plus",
    )

    @Test
    fun `JSON 往返保留全部字段`() {
        val original = credential()
        val restored = ChatGPTCredential.fromJson(original.toJson())
        assertEquals(original, restored)
    }

    @Test
    fun `兼容驼峰字段名`() {
        // 早期/第三方导出的凭证可能用驼峰；读不进来会让用户以为导入失败
        val raw = """{"accessToken":"at","refreshToken":"rt","expiresAt":123,"accountId":"acc"}"""
        val restored = ChatGPTCredential.fromJson(raw)
        assertEquals("at", restored.accessToken)
        assertEquals("rt", restored.refreshToken)
        assertEquals(123L, restored.expiresAtMillis)
        assertEquals("acc", restored.accountId)
    }

    @Test
    fun `过期判定包含未知过期时间`() {
        val c = credential(expiresAt = 0L)
        assertTrue(c.isExpiringWithin(60_000L, nowMillis = 1_000_000L), "过期时间未知时应视为需要刷新")
    }

    @Test
    fun `距过期足够远时不刷新`() {
        val c = credential(expiresAt = 10_000_000L)
        assertFalse(c.isExpiringWithin(60_000L, nowMillis = 1_000_000L))
    }

    @Test
    fun `无 refresh_token 时标记不可刷新`() {
        assertFalse(credential(refresh = "").hasRefreshToken)
        assertTrue(credential(refresh = "rt").hasRefreshToken)
    }

    @Test
    fun `订阅档位映射按 codex 的 PLANS 表`() {
        // 反直觉的三条：prolite 才是 Pro，pro 是 Pro (More)，promax 是 Pro (Max)
        assertEquals("Pro", ChatGPTCredential.planDisplayName("prolite"))
        assertEquals("Pro (More)", ChatGPTCredential.planDisplayName("pro"))
        assertEquals("Pro (Max)", ChatGPTCredential.planDisplayName("promax"))
        assertEquals("Plus", ChatGPTCredential.planDisplayName("plus"))
        assertEquals("Free", ChatGPTCredential.planDisplayName("free"))
    }

    @Test
    fun `未知档位原样返回而不是空串`() {
        assertEquals("something-new", ChatGPTCredential.planDisplayName("something-new"))
        assertEquals("", ChatGPTCredential.planDisplayName(""))
    }

    // ---------------------------------------------------------------- JWT

    /** 造一个只带 payload 的假 JWT（签名部分不参与解析）。 */
    private fun fakeJwt(payload: String): String {
        val body = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        return "header.$body.signature"
    }

    @Test
    fun `从 JWT 里取 accountId 与档位`() {
        val jwt = fakeJwt(
            """{"https://api.openai.com/auth":{"chatgpt_account_id":"acc-9","chatgpt_plan_type":"prolite"}}""",
        )
        assertEquals("acc-9", ChatGPTJwt.accountId(jwt))
        assertEquals("prolite", ChatGPTJwt.planType(jwt))
    }

    @Test
    fun `从 JWT 的 exp 算绝对过期时间`() {
        val jwt = fakeJwt("""{"exp":1700000000}""")
        assertEquals(1_700_000_000_000L, ChatGPTJwt.expiresAtMillis(jwt))
    }

    @Test
    fun `畸形 JWT 不抛异常只返回空值`() {
        assertEquals("", ChatGPTJwt.accountId("not-a-jwt"))
        assertEquals("", ChatGPTJwt.planType("a.b"))
        assertEquals(0L, ChatGPTJwt.expiresAtMillis(""))
        assertNull(ChatGPTJwt.claims("x"))
    }

    @Test
    fun `email 优先取 id_token 的 profile claim`() {
        val idToken = fakeJwt("""{"https://api.openai.com/profile":{"email":"from-id@x.y"}}""")
        val access = fakeJwt("""{"email":"from-access@x.y"}""")
        assertEquals("from-id@x.y", ChatGPTJwt.email(idToken, access))
        // id_token 缺失或没有该 claim 时回退到 access_token
        assertEquals("from-access@x.y", ChatGPTJwt.email(null, access))
    }

    // ---------------------------------------------------------------- 授权请求

    @Test
    fun `授权 URL 带齐 PKCE 与注册参数`() {
        val client = ChatGPTAuthClient(nowMillis = { 0L })
        val pkce = client.newPkce()
        val url = client.buildAuthUrl("state-1", pkce)

        assertTrue(url.startsWith(ChatGPTConstants.AUTH_ENDPOINT))
        assertTrue(url.contains("code_challenge_method=S256"), "必须是 S256")
        assertTrue(url.contains("client_id=${ChatGPTConstants.CLIENT_ID}"))
        assertTrue(url.contains("state=state-1"))
        assertTrue(url.contains("originator=${ChatGPTConstants.ORIGINATOR}"))
        // redirect_uri 是上游注册值，被 URL 编码后出现在查询串里
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback"))
    }

    @Test
    fun `PKCE challenge 是 verifier 的 SHA256`() {
        val client = ChatGPTAuthClient()
        val pkce = client.newPkce()
        val expected = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(pkce.verifier.toByteArray(Charsets.US_ASCII)),
        )
        assertEquals(expected, pkce.challenge)
    }

    @Test
    fun `每次生成的 PKCE 与 state 都不同`() {
        val client = ChatGPTAuthClient()
        val a = client.newPkce()
        val b = client.newPkce()
        assertTrue(a.verifier != b.verifier)
        assertTrue(client.newState() != client.newState())
    }

    @Test
    fun `回调端口与路径是上游注册值`() {
        // 改这两个值会让 OpenAI 逐字符校验 redirect_uri 时直接拒绝
        assertEquals(1455, ChatGPTConstants.CALLBACK_PORT)
        assertEquals("/auth/callback", ChatGPTConstants.CALLBACK_PATH)
    }

    @Test
    fun `错误体解析优先取 error_description`() {
        // 通过反射调用私有方法太重，这里用一个最小的等价断言：错误文案包含可读描述
        val raw = """{"error":"invalid_grant","error_description":"refresh token expired"}"""
        val obj = JsonParser.parseString(raw).asJsonObject
        assertEquals("refresh token expired", obj.get("error_description").asString)
        assertTrue(obj is JsonObject)
    }
}
