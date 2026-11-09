package io.synapse.core.provider.cline

import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderHooks
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cline 协议回归：模型目录归池、请求体收敛、伪装头、错误分类。
 *
 * 这里锁的都是**实测踩出来的**规矩，改动前先读 `ClineProvider` 的类注释：
 *
 * - 归池看**分组**不看前缀（`free` 组里有别家的裸 id）；
 * - `X-CLIENT-TYPE: cline-sdk` 是免费池的硬门槛；
 * - 请求体是白名单重建，且注入 `session_id` / `reasoning_effort`；
 * - 403 `ENTITLEMENT_ERROR` 归 `MODEL_LIMIT` 而不是 `SESSION_DEAD`。
 */
class ClineProtocolTest {

    private var server: HttpServer? = null

    /** 最近一次请求：路径、请求头、请求体（用来断言「发出去的是什么」）。 */
    private val lastPath = AtomicReference("")
    private val lastHeaders = AtomicReference<Map<String, String>>(emptyMap())
    private val lastBody = AtomicReference("")

    @AfterTest
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    /** 起一个本地假上游，[responder] 决定返回什么。 */
    private fun startServer(responder: (String) -> Pair<Int, String>): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange: HttpExchange ->
            lastPath.set(exchange.requestURI.path)
            lastHeaders.set(exchange.requestHeaders.entries.associate { (k, v) -> k to v.firstOrNull().orEmpty() })
            lastBody.set(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val (status, body) = responder(exchange.requestURI.path)
            val bytes = body.toByteArray(Charsets.UTF_8)
            // 响应体是 SSE 时必须回 text/event-stream：上游的流式与非流式
            // 走的是两条不同的解析分支（前者裸 chunk 直传，后者带信封）
            val contentType = if (body.startsWith("data:")) "text/event-stream" else "application/json"
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private fun account(expiresAt: Long = System.currentTimeMillis() + 3_600_000) = ProviderAccount(
        providerId = ClineProvider.ID,
        uid = "usr-1",
        nickname = "测试",
        secret = """{"accessToken":"workos:eyJhbGci","refreshToken":"rv","expiresAt":$expiresAt,"accountId":"usr-1"}""",
    )

    // ---------------------------------------------------------------- 模型目录

    @Test
    fun `归池看分组不看前缀 —— free 组里的裸 id 也属于免费池`() {
        // 实测：free 组 5 条里有 2 条是别家的裸 id（走 OpenRouter 那类通道转售），
        // 它们同样是免费池成员。按前缀猜池会把这两条错判进订阅池，
        // 而订阅池没账号时整家不广告 —— 结果是它们在界面上彻底消失。
        val base = startServer {
            200 to """
                {"recommended":[{"id":"openai/gpt-6-astra","name":"gpt-6-astra"}],
                 "free":[{"id":"cline-free/deepseek-v4.1-flash","name":"Deepseek-v4.1-Flash"},
                         {"id":"z-ai/glm-5.3-flash","name":"glm-5.3-flash"},
                         {"id":"poolside/laguna-s-2.1:free","name":"laguna"}],
                 "clinePass":[{"id":"cline-pass/glm-5.3","name":"cline-pass/glm-5.3"}],
                 "clineCloud":[{"id":"cline-cloud/glm-5.3","name":"cloud"}]}
            """.trimIndent()
        }
        val provider = ClineProvider(apiBaseUrl = base)
        val view = provider.listModels(account())
        assertTrue(view.error.isEmpty(), view.error)

        val byPool = view.models.groupBy { it.extra[ClineProvider.EXTRA_POOL].orEmpty() }
        // free 组三条全部归免费池 —— 包括那两条没带 cline-free/ 前缀的
        assertEquals(3, byPool[ClineProvider.POOL_FREE]?.size, "free 组应为 3 条：${byPool[ClineProvider.POOL_FREE]}")
        assertEquals(1, byPool[ClineProvider.POOL_PASS]?.size)
        // clineCloud 不收录（实测 403）
        assertTrue(view.models.none { it.id.startsWith(ClineProvider.CLOUD_PREFIX) }, "云端池不该收录")
        // recommended 组没有池信息，按前缀兜底判不出池
        assertTrue(view.models.any { it.id == "openai/gpt-6-astra" })
    }

    @Test
    fun `云端池模型算内部条目`() {
        val provider = ClineProvider()
        assertTrue(provider.isInternalModel("cline-cloud/glm-5.3"))
        assertTrue(!provider.isInternalModel("cline-free/glm-5.3"))
        assertTrue(!provider.isInternalModel("cline-pass/glm-5.3"))
    }

    @Test
    fun `模型名不自动补池前缀`() {
        // 两池都有 deepseek-v4.1-flash，补哪个都是替用户做决定
        val provider = ClineProvider()
        assertEquals("deepseek-v4.1-flash", provider.resolveModel("deepseek-v4.1-flash"))
        assertEquals("cline-pass/glm-5.3", provider.resolveModel("cline-pass/glm-5.3"))
        assertEquals("", provider.resolveModel("  "))
    }

    // ---------------------------------------------------------------- 请求体

    @Test
    fun `请求体按白名单重建并注入 session_id 与默认档位`() {
        val base = startServer {
            200 to "data: {\"id\":\"c1\",\"model\":\"deepseek/deepseek-v4.1-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        }
        val provider = ClineProvider(apiBaseUrl = base)
        provider.openChat(
            account(),
            """{"model":"cline-free/deepseek-v4.1-flash","messages":[{"role":"user","content":"hi"}],
                "temperature":0.7,"top_p":0.9,"nonsense_key":"should-be-dropped","stream":false}""",
        ).use { call ->
            assertEquals(200, call.status)
        }
        val sent = JsonParser.parseString(lastBody.get()).asJsonObject
        // model 原样保留（含池前缀 —— 那是计费通道选择器）
        assertEquals("cline-free/deepseek-v4.1-flash", sent.get("model").asString)
        // 白名单内的键保留
        assertEquals(0.7, sent.get("temperature").asDouble)
        assertEquals(0.9, sent.get("top_p").asDouble)
        // 白名单外的键丢弃
        assertNull(sent.get("nonsense_key"), "白名单外的键不该发给上游：${lastBody.get()}")
        // 上游恒为流式（非流式响应带信封，主链路统一走流式）
        assertTrue(sent.get("stream").asBoolean)
        // 注入的三样：max_tokens 默认 128000、session_id、reasoning_effort 默认 high
        assertEquals(ClineProvider.DEFAULT_MAX_TOKENS, sent.get("max_tokens").asLong)
        assertTrue(sent.get("session_id").asString.startsWith("sess_"), sent.get("session_id").asString)
        assertEquals(ClineProvider.DEFAULT_REASONING_EFFORT, sent.get("reasoning_effort").asString)
    }

    @Test
    fun `客户端显式给的小 max_tokens 不被抬高`() {
        // 上游在 max_tokens 太小而模型又要先输出一大段 reasoning 时会回 500，
        // 但那是客户端自己的取舍 —— 强制抬高会让「我要短回复」失效。
        val base = startServer { 200 to "data: [DONE]\n\n" }
        val provider = ClineProvider(apiBaseUrl = base)
        provider.openChat(
            account(),
            """{"model":"cline-free/x","messages":[],"max_tokens":256}""",
        ).use { }
        val sent = JsonParser.parseString(lastBody.get()).asJsonObject
        assertEquals(256L, sent.get("max_tokens").asLong)
    }

    @Test
    fun `max_tokens 为零或负数按缺失处理`() {
        val base = startServer { 200 to "data: [DONE]\n\n" }
        val provider = ClineProvider(apiBaseUrl = base)
        provider.openChat(
            account(),
            """{"model":"cline-free/x","messages":[],"max_tokens":0}""",
        ).use { }
        val sent = JsonParser.parseString(lastBody.get()).asJsonObject
        assertEquals(ClineProvider.DEFAULT_MAX_TOKENS, sent.get("max_tokens").asLong)
    }

    @Test
    fun `显式思考档位优先于默认值`() {
        val base = startServer { 200 to "data: [DONE]\n\n" }
        val provider = ClineProvider(apiBaseUrl = base)
        provider.openChat(
            account(),
            """{"model":"cline-free/x","messages":[],"reasoningEffort":"low"}""",
        ).use { }
        val sent = JsonParser.parseString(lastBody.get()).asJsonObject
        // 驼峰写法也要认（不同客户端的字段名不一致）
        assertEquals("low", sent.get("reasoning_effort").asString)
    }

    // ---------------------------------------------------------------- 请求头

    @Test
    fun `伪装头齐全且 Authorization 带 workos 前缀`() {
        val base = startServer { 200 to "data: [DONE]\n\n" }
        val provider = ClineProvider(apiBaseUrl = base)
        provider.openChat(account(), """{"model":"cline-free/x","messages":[]}""").use { }
        val headers = lastHeaders.get().mapKeys { it.key.lowercase() }
        // X-CLIENT-TYPE 是免费池的硬门槛：缺了一律 403
        assertEquals(ClineProvider.CLIENT_TYPE, headers["x-client-type"], "缺 X-CLIENT-TYPE 会 403：$headers")
        // 取值必须是 cline-sdk，不是 cline-cli（后者会 500 empty response content）
        assertEquals("cline-sdk", headers["x-client-type"])
        assertEquals("Cline/3.0.62", headers["user-agent"])
        assertEquals("https://cline.bot", headers["http-referer"])
        assertEquals("terminal", headers["x-platform"])
        // workos: 前缀必须原样带上，去掉就 401
        assertEquals("Bearer workos:eyJhbGci", headers["authorization"])
    }

    @Test
    fun `请求路径只有一个 v1`() {
        // 上游用 AI SDK 的 createOpenAICompatible，把 baseURL 当根拼；
        // 发成 /api/v1/v1/chat/completions 会 404
        val base = startServer { 200 to "data: [DONE]\n\n" }
        val provider = ClineProvider(apiBaseUrl = base)
        provider.openChat(account(), """{"model":"cline-free/x","messages":[]}""").use { }
        assertEquals("/chat/completions", lastPath.get())
    }

    // ---------------------------------------------------------------- SSE

    @Test
    fun `流式响应里的 model 被改回客户端请求的名字`() {
        val base = startServer {
            200 to "data: {\"id\":\"c1\",\"model\":\"deepseek/deepseek-v4.1-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n"
        }
        val provider = ClineProvider(apiBaseUrl = base)
        val text = provider.openChat(
            account(),
            """{"model":"cline-free/deepseek-v4.1-flash","messages":[],"stream":true}""",
        ).use { call ->
            assertNotNull(call.stream, "流式请求应返回 stream")
            call.stream!!.readBytes().toString(Charsets.UTF_8)
        }
        // 上游给的是内部名（deepseek/deepseek-v4.1-flash），客户端不该看到它
        assertTrue(text.contains("cline-free/deepseek-v4.1-flash"), text)
        assertTrue(!text.contains("deepseek/deepseek-v4.1-flash"), "上游内部名不该泄漏：$text")
        assertTrue(text.contains("[DONE]"), text)
    }

    @Test
    fun `非流式请求由本地聚合，且不带信封`() {
        val base = startServer {
            200 to "data: {\"id\":\"c1\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"}}]}\n\n" +
                "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"世界\"},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n"
        }
        val provider = ClineProvider(apiBaseUrl = base)
        val aggregated = provider.openChat(
            account(),
            """{"model":"cline-free/x","messages":[],"stream":false}""",
        ).use { call ->
            assertNull(call.stream, "非流式不该返回 stream")
            call.aggregated
        }
        assertNotNull(aggregated)
        val obj = JsonParser.parseString(aggregated).asJsonObject
        assertEquals("chat.completion", obj.get("object").asString)
        assertEquals("cline-free/x", obj.get("model").asString)
        val content = obj.getAsJsonArray("choices")[0].asJsonObject
            .getAsJsonObject("message").get("content").asString
        assertEquals("你好世界", content)
    }

    // ---------------------------------------------------------------- 错误分类

    @Test
    fun `403 权限错误归 MODEL_LIMIT 而不是 SESSION_DEAD`() {
        // 归 SESSION_DEAD 会 pooldisable() 硬禁用账号，用户得重新登录才能解封，
        // 而权限问题重新登录根本解决不了。
        val provider = ClineProvider()
        val entitlement = provider.classify(
            403,
            """{"error":{"code":"ENTITLEMENT_ERROR","message":"Model only available via Cline product surfaces"}}""",
        )
        assertEquals(ErrorKind.MODEL_LIMIT, entitlement.kind)
        // 文案要带中文结论，同时**保留上游原文**（排查时它是唯一能搜的线索）
        assertTrue(entitlement.message.contains("没有该模型的权限"), entitlement.message)
        assertTrue(
            entitlement.message.contains("Model only available via Cline product surfaces"),
            "必须保留上游原文：${entitlement.message}",
        )
    }

    @Test
    fun `402 余额不足给出中文语义与上游给的余额和充值地址`() {
        val provider = ClineProvider()
        val quota = provider.classify(
            402,
            """{"error":{"code":"insufficient_credits","message":"Insufficient balance. Your Cline Credits balance is $-0.00","current_balance":-0.002877,"buy_credits_url":"https://app.cline.bot/credits"}}""",
        )
        assertEquals(ErrorKind.QUOTA, quota.kind)
        assertTrue(quota.message.contains("余额不足"), quota.message)
        // current_balance 上游已经是 credit，不能再除 1e6
        assertTrue(quota.message.contains("-0.002877 credit"), quota.message)
        assertTrue(quota.message.contains("https://app.cline.bot/credits"), quota.message)
        assertTrue(quota.message.contains("Insufficient balance"), "要保留原文：${quota.message}")
    }

    @Test
    fun `401 给出中文语义`() {
        val provider = ClineProvider()
        val dead = provider.classify(401, """{"error":"Unauthorized: Please make sure you're using the latest version of Cline and re-authenticate your Cline account."}""")
        assertEquals(ErrorKind.SESSION_DEAD, dead.kind)
        assertTrue(dead.message.contains("登录凭证已失效"), dead.message)
        assertTrue(dead.message.contains("Unauthorized"), "要保留原文：${dead.message}")
    }

    @Test
    fun `上游文案本身已是中文时不再套壳`() {
        val provider = ClineProvider()
        val client = provider.classify(400, """{"error":{"message":"模型不存在，请检查模型名"}}""")
        assertEquals("模型不存在，请检查模型名", client.message)
    }

    @Test
    fun `401 与其它 403 归 SESSION_DEAD`() {
        val provider = ClineProvider()
        assertEquals(ErrorKind.SESSION_DEAD, provider.classify(401, """{"error":"unauthorized"}""").kind)
        assertEquals(ErrorKind.SESSION_DEAD, provider.classify(403, """{"error":"forbidden"}""").kind)
    }

    @Test
    fun `404 分两支 —— 缺模型与路径错`() {
        val provider = ClineProvider()
        assertEquals(
            ErrorKind.NOT_FOUND,
            provider.classify(404, """{"error":"model not found"}""").kind,
        )
        // 路径写错（如多一个 v1）不该报成「没有这个模型」
        assertEquals(
            ErrorKind.CLIENT,
            provider.classify(404, """{"error":"Not Found"}""").kind,
        )
    }

    @Test
    fun `429 与 5xx 的分类`() {
        val provider = ClineProvider()
        assertEquals(ErrorKind.SOFT_RATE, provider.classify(429, """{"error":"rate limited"}""").kind)
        assertEquals(ErrorKind.SERVER, provider.classify(503, "upstream down").kind)
        assertEquals(ErrorKind.QUOTA, provider.classify(402, """{"error":"insufficient credit"}""").kind)
    }

    // ---------------------------------------------------------------- 余额

    @Test
    fun `余额按微 credit 换算，负值如实保留`() {
        val base = startServer { path ->
            when {
                path.endsWith("/balance") -> 200 to """{"data":{"userId":"usr-1","balance":-17704},"success":true}"""
                // 免费用户没有订阅：404 是正常状态，不是错误
                path.endsWith("/plan") -> 404 to """{"data":null,"error":"no plan history found for user","success":false}"""
                else -> 404 to "{}"
            }
        }
        val provider = ClineProvider(apiBaseUrl = base)
        val info = provider.creditInfo(account())
        assertNotNull(info)
        // -17704 微 credit = -0.0177 credit，上游官方界面显示的就是这个量级
        assertEquals(-17704L / ClineProvider.MICRO_CREDITS_PER_CREDIT, info.balance)
        assertTrue(info.known)
        // 欠费状态必须能在文案里看出来
        assertTrue(info.detail.contains("欠费"), info.detail)
        // plan 的 404 不该把「查询失败」写进 detail
        assertTrue(!info.detail.contains("失败"), info.detail)
    }

    @Test
    fun `账号 id 缺失时打 users me 问出真 id，而不是拼空路径`() {
        // 实测踩到的 bug：凭证里没有 accountId 时直接拿 uid 拼路径，
        // 会得到 `/users/<哈希>/balance`，上游回 `Invalid request format` ——
        // 看起来像「协议写错了」，实际是 id 取错了。
        val balancePath = AtomicReference("")
        val base = startServer { path ->
            when {
                path.endsWith("/users/me") -> 200 to """{"data":{"id":"usr-real"},"success":true}"""
                path.endsWith("/users/usr-real/balance") -> {
                    balancePath.set(path)
                    200 to """{"data":{"userId":"usr-real","balance":5000000},"success":true}"""
                }
                path.endsWith("/plan") -> 404 to """{"data":null,"success":false}"""
                else -> 400 to """{"error":"Invalid request format"}"""
            }
        }
        val provider = ClineProvider(apiBaseUrl = base)
        // 凭证里没有 accountId，池 uid 也不是 usr- 形态
        val noId = ProviderAccount(
            providerId = ClineProvider.ID,
            uid = "cline-abc123",
            nickname = "n",
            secret = """{"accessToken":"workos:eyJ","refreshToken":"rv","expiresAt":${System.currentTimeMillis() + 3_600_000}}""",
        )
        val info = provider.creditInfo(noId)
        assertNotNull(info)
        assertTrue(info.known, info.detail)
        assertEquals(5L, info.balance)
        // 关键断言：打的是 /users/usr-real/balance，不是 /users/cline-abc123/balance
        assertEquals("/users/usr-real/balance", balancePath.get())
    }

    @Test
    fun `凭证里的 accountId 直接可用，不必打 users me`() {
        val meCalled = AtomicReference(false)
        val base = startServer { path ->
            when {
                path.endsWith("/users/me") -> {
                    meCalled.set(true)
                    200 to """{"data":{"id":"usr-real"},"success":true}"""
                }
                path.endsWith("/balance") -> 200 to """{"data":{"balance":1000000},"success":true}"""
                path.endsWith("/plan") -> 404 to "{}"
                else -> 400 to "{}"
            }
        }
        val provider = ClineProvider(apiBaseUrl = base)
        val withId = ProviderAccount(
            providerId = ClineProvider.ID,
            uid = "usr-1",
            nickname = "n",
            secret = """{"accessToken":"workos:eyJ","refreshToken":"rv","expiresAt":${System.currentTimeMillis() + 3_600_000},"accountId":"usr-1"}""",
        )
        val info = provider.creditInfo(withId)
        assertNotNull(info)
        assertEquals(1L, info.balance)
        // 已有 id 时不该多打一次 /users/me
        assertTrue(!meCalled.get(), "已有 accountId 时不该再问 /users/me")
    }

    @Test
    fun `余额缺失时 known 为 false 而不是 0`() {
        val base = startServer { 200 to """{"data":{"userId":"usr-1"},"success":true}""" }
        val provider = ClineProvider(apiBaseUrl = base)
        val info = provider.creditInfo(account())
        assertNotNull(info)
        // 显示成 0 会让人以为额度真的用完了
        assertTrue(!info.known, "缺 balance 字段时应为未知：${info.detail}")
    }

    // ---------------------------------------------------------------- 续期

    @Test
    fun `续期请求用 camelCase 的 grantType`() {
        val base = startServer { path ->
            when {
                path.endsWith("/auth/refresh") ->
                    200 to """{"data":{"accessToken":"eyJnew","refreshToken":"rv","expiresAt":"2026-09-19T19:30:04Z"},"success":true}"""
                else -> 404 to "{}"
            }
        }
        val provider = ClineProvider(apiBaseUrl = base)
        val refreshed = provider.refreshAccount(account(expiresAt = 1000L), 60)
        assertNotNull(refreshed, "临期凭证应触发续期")
        val sent = JsonParser.parseString(lastBody.get()).asJsonObject
        // OAuth 标准是 grant_type，Cline 用的是 grantType —— 发错会被拒
        assertTrue(sent.has("grantType"), "必须用 camelCase 的 grantType：${lastBody.get()}")
        assertTrue(!sent.has("grant_type"), "不该发 grant_type：${lastBody.get()}")
        // 续期返回的 token 不带 workos: 前缀，写入时必须补上
        val credential = parseClineCredential(refreshed.secret)
        assertNotNull(credential)
        assertEquals("workos:eyJnew", credential.accessToken)
        // expiresAt 是 ISO 字符串，要转成毫秒
        assertEquals(1789846204000L, credential.expiresAt)
    }

    @Test
    fun `凭证未临期时不续期`() {
        val provider = ClineProvider(apiBaseUrl = "http://127.0.0.1:1")
        assertNull(provider.refreshAccount(account(expiresAt = System.currentTimeMillis() + 3_600_000), 60))
    }

    @Test
    fun `没有 refreshToken 时不尝试续期`() {
        val provider = ClineProvider(apiBaseUrl = "http://127.0.0.1:1")
        val noRefresh = ProviderAccount(
            providerId = ClineProvider.ID,
            uid = "u",
            nickname = "n",
            secret = """{"accessToken":"workos:a","expiresAt":1000}""",
        )
        assertNull(provider.refreshAccount(noRefresh, 60))
    }

    @Test
    fun `过期凭证不带着打上游`() {
        // 带着过期 token 打上游会拿到 401 → 被判 SESSION_DEAD → 账号被硬禁用，
        // 一次瞬时问题就变成用户看到的「登录失效」。
        val provider = ClineProvider(apiBaseUrl = "http://127.0.0.1:1")
        provider.openChat(account(expiresAt = 1000L), """{"model":"cline-free/x","messages":[]}""").use { call ->
            assertEquals(401, call.status)
            assertTrue(call.errorBody.contains("过期"), call.errorBody)
        }
    }

    // ---------------------------------------------------------------- 其它

    @Test
    fun `hosts 供代理开关匹配`() {
        assertEquals(listOf("cline.bot"), ClineProvider().hosts())
    }

    @Test
    fun `账号 id 与显示名`() {
        val provider = ClineProvider()
        assertEquals("cline", provider.id)
        assertEquals("Cline", provider.displayName)
    }

    @Test
    fun `凭证无法解析时给出明确错误`() {
        val provider = ClineProvider(apiBaseUrl = "http://127.0.0.1:1")
        val broken = ProviderAccount(ClineProvider.ID, "u", "n", "not json")
        provider.openChat(broken, """{"model":"x","messages":[]}""").use { call ->
            assertEquals(401, call.status)
            assertTrue(call.errorBody.contains("凭证"), call.errorBody)
        }
    }
}
