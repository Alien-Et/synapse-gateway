package io.synapse.core.provider.custom

import com.sun.net.httpserver.HttpServer
import io.synapse.core.gateway.BalanceQueryConfig
import io.synapse.core.gateway.CustomProviderConfig
import io.synapse.core.gateway.HttpHeader
import io.synapse.core.provider.ProviderAccount
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 自定义请求头 + 余额查询的回归测试。
 *
 * 这两项都是「用户自己填的东西」，一旦静默失效最难查：配了头却发不出去、
 * 字段名写错却显示成 0 —— 都属于看着没报错、实际全错。这里锁住三条底线：
 *
 * 1. 自定义头真的会发到上游，且同名时覆盖内置的 `Authorization`；
 * 2. 余额按点分路径取值，取不到必须 `known=false`（不能显示成 0）；
 * 3. 倍数换算（乘/除）按配置生效。
 */
class CustomProviderHeadersAndBalanceTest {

    /** 起一个本地上游，记录收到的请求头，并按 [body] 回应。 */
    private fun withUpstream(
        status: Int = 200,
        body: String = "{}",
        capturedHeaders: AtomicReference<Map<String, List<String>>>? = null,
        capturedPath: AtomicReference<String>? = null,
        block: (baseUrl: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // 用根 context 兜住任意路径：余额接口路径是用户填的，测试里不想逐个建
        server.createContext("/") { exchange ->
            capturedHeaders?.set(exchange.requestHeaders.mapValues { it.value.toList() })
            capturedPath?.set(exchange.requestURI.path)
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/v1")
        } finally {
            server.stop(0)
        }
    }

    private fun provider(
        baseUrl: String,
        headers: List<HttpHeader> = emptyList(),
        balanceQuery: BalanceQueryConfig? = null,
    ) = CustomProvider(
        CustomProviderConfig(
            key = "t",
            name = "测试",
            baseUrl = baseUrl,
            apiKeys = listOf("sk-1"),
            headers = headers,
            balanceQuery = balanceQuery,
        ),
    )

    private fun account() = ProviderAccount("custom:t", "u1", "测试", CustomProvider.secretOf("sk-1"))

    // ------------------------------------------------------------------ 请求头

    @Test
    fun `自定义头会随对话请求发到上游`() {
        val captured = AtomicReference<Map<String, List<String>>>()
        withUpstream(capturedHeaders = captured) { baseUrl ->
            provider(
                baseUrl,
                headers = listOf(
                    HttpHeader("X-Tenant", "abc123"),
                    HttpHeader("User-Agent", "MyClient/1.0"),
                ),
            ).openChat(
                account(),
                """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
            )
        }
        val headers = captured.get().orEmpty()
        assertEquals(listOf("abc123"), headers["X-tenant"] ?: headers["X-Tenant"])
        // JDK 的 Headers 会改写大小写，取任一形态即可
        val ua = headers.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
        assertEquals(listOf("MyClient/1.0"), ua)
        // 内置的 Bearer 认证不能被挤掉
        val auth = headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
        assertEquals(listOf("Bearer sk-1"), auth)
    }

    @Test
    fun `自定义 Authorization 覆盖内置 Bearer`() {
        val captured = AtomicReference<Map<String, List<String>>>()
        withUpstream(capturedHeaders = captured) { baseUrl ->
            // 有些中转站要求裸 Key 或 x-api-key，硬塞 Bearer 反而验不过
            provider(baseUrl, headers = listOf(HttpHeader("Authorization", "sk-raw")))
                .openChat(
                    account(),
                    """{"model":"m","messages":[{"role":"user","content":"hi"}]}""",
                )
        }
        val headers = captured.get().orEmpty()
        val auths = headers.entries.filter { it.key.equals("Authorization", ignoreCase = true) }
        // 只能有一条：两条 Authorization 同时发出去，服务端多半直接 400
        assertEquals(1, auths.size)
        assertEquals(listOf("sk-raw"), auths.first().value)
    }

    @Test
    fun `非法头名与非法的值会被丢弃`() {
        assertFalse(HttpHeader.isValidName("X Bad"))
        assertFalse(HttpHeader.isValidName(""))
        assertFalse(HttpHeader.isValidValue("a\r\nInjected: 1"))
        assertTrue(HttpHeader.isValidName("X-Tenant"))
        assertTrue(HttpHeader.isValidValue("Bearer abc"))
        // fromJson 对非法项返回 null，调用方（配置解析）据此跳过
        assertNull(HttpHeader.fromJson(com.google.gson.JsonObject().apply {
            addProperty("name", "X Bad")
            addProperty("value", "1")
        }))
    }

    // ------------------------------------------------------------------ 余额

    @Test
    fun `按点分路径取余额`() {
        withUpstream(body = """{"data":{"quota":12345,"nested":{"remain":77}}}""") { baseUrl ->
            val info = provider(baseUrl, balanceQuery = BalanceQueryConfig(field = "data.nested.remain"))
                .creditInfo(account())
            assertTrue(info!!.known, "detail=${info.detail}")
            assertEquals(77L, info.balance)
        }
    }

    @Test
    fun `支持数组下标路径`() {
        withUpstream(body = """{"data":{"accounts":[{"remain":-1},{"remain":999}]}}""") { baseUrl ->
            val info = provider(baseUrl, balanceQuery = BalanceQueryConfig(field = "data.accounts.1.remain"))
                .creditInfo(account())
            assertEquals(999L, info!!.balance)
        }
    }

    @Test
    fun `字段不存在时报 known 为 false 而不是 0`() {
        withUpstream(body = """{"data":{}}""") { baseUrl ->
            val info = provider(baseUrl, balanceQuery = BalanceQueryConfig(field = "data.quota"))
                .creditInfo(account())
            assertFalse(info!!.known)
            // 关键：不能返回 0 —— 那等于告诉用户「你没钱了」
            assertEquals(0L, info.balance)
            assertTrue(info.detail.contains("找不到"))
        }
    }

    @Test
    fun `余额真的是 0 时报 known 为 true`() {
        withUpstream(body = """{"balance":0}""") { baseUrl ->
            val info = provider(baseUrl, balanceQuery = BalanceQueryConfig(field = "balance"))
                .creditInfo(account())
            assertTrue(info!!.known)
            assertEquals(0L, info.balance)
        }
    }

    @Test
    fun `倍数为乘且带单位`() {
        withUpstream(body = """{"balance":5}""") { baseUrl ->
            val info = provider(
                baseUrl,
                balanceQuery = BalanceQueryConfig(field = "balance", scale = 100.0, unit = "点"),
            ).creditInfo(account())
            assertEquals(500L, info!!.balance)
            assertTrue(info.detail.contains("点"))
        }
    }

    @Test
    fun `倍数为除`() {
        withUpstream(body = """{"quota":500000}""") { baseUrl ->
            // 某些站的 quota 是「最小单位」，500000 = 1 元
            val info = provider(
                baseUrl,
                balanceQuery = BalanceQueryConfig(field = "quota", scale = 500000.0, divide = true),
            ).creditInfo(account())
            assertEquals(1L, info!!.balance)
        }
    }

    @Test
    fun `数值是字符串也能解析`() {
        withUpstream(body = """{"balance":"12.9"}""") { baseUrl ->
            val info = provider(baseUrl, balanceQuery = BalanceQueryConfig(field = "balance"))
                .creditInfo(account())
            assertTrue(info!!.known)
            assertEquals(12L, info.balance)
        }
    }

    @Test
    fun `上游非 2xx 时报失败`() {
        withUpstream(status = 401, body = """{"error":"bad key"}""") { baseUrl ->
            val info = provider(baseUrl, balanceQuery = BalanceQueryConfig(field = "balance"))
                .creditInfo(account())
            assertFalse(info!!.known)
            assertTrue(info.detail.contains("401"))
        }
    }

    @Test
    fun `没配余额查询就返回 null`() {
        assertNull(provider("http://127.0.0.1:1/v1").creditInfo(account()))
    }

    @Test
    fun `余额请求会带上自定义头`() {
        val captured = AtomicReference<Map<String, List<String>>>()
        withUpstream(body = """{"balance":1}""", capturedHeaders = captured) { baseUrl ->
            provider(
                baseUrl,
                headers = listOf(HttpHeader("X-Tenant", "t1")),
                balanceQuery = BalanceQueryConfig(field = "balance"),
            ).creditInfo(account())
        }
        val headers = captured.get().orEmpty()
        assertEquals(listOf("t1"), headers.entries.firstOrNull { it.key.equals("X-Tenant", ignoreCase = true) }?.value)
    }

    @Test
    fun `相对路径会拼到 baseUrl 上`() {
        val capturedPath = AtomicReference<String>()
        withUpstream(body = """{"balance":1}""", capturedPath = capturedPath) { baseUrl ->
            provider(baseUrl, balanceQuery = BalanceQueryConfig(path = "/api/user/self", field = "balance"))
                .creditInfo(account())
        }
        assertEquals("/v1/api/user/self", capturedPath.get())
    }
}
