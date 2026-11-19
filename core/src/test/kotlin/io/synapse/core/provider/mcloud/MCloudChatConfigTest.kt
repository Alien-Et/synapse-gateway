package io.synapse.core.provider.mcloud

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 模型能力信息（上下文 / 联网 / 思考）的拉取与合并。
 *
 * 样本取自 **2026-10-31 抓包实测**（`移动云盘Ai接口抓包数据.har` 的
 * `apikey/get` 与 `outer/assistant/chat/config/get` 两个响应），不是编的。
 *
 * 这条链路的关键约束：`chat/config/get` 只是**补充信息**，任何失败都不能影响登录——
 * 少一个上下文窗口远好过登不进去。
 */
class MCloudChatConfigTest {

    private lateinit var server: HttpServer

    /** 实测样本：9 个模型，只有倍率没有上下文。 */
    private val apikeyJson = """
        {"success":true,"code":"0000","message":"请求成功","data":{
          "apikey":"a7a330229929e019efc014404067ec40829311116b2b2be4af6200aeb4d16786",
          "modelUrl":"https://ai.yun.139.com/api/llm/102001/v1/chat/completions",
          "modelList":[
            {"modelCode":"blian_qwen38_max","modelName":"qwen3.8-Max","ability":"自主规划能力强","tokenRatio":"1.5"},
            {"modelCode":"huoshan_glm_5_3","modelName":"GLM-5.3","ability":"深度推理","tokenRatio":"3.5"},
            {"modelCode":"blian_qwen38_flash","modelName":"qwen3.8-Flash","ability":"轻量高效","tokenRatio":"0.18"}
          ]}}
    """.trimIndent()

    /** 实测样本：maxInputLength / enableNetworkSearch / enableSetThinking。 */
    private val chatConfigJson = """
        {"success":true,"code":"0000","message":"请求成功","data":{"modelList":[
          {"modelType":"blian_qwen38_max","description":"qwen3.8-Max","maxInputLength":800000,
           "enableNetworkSearch":true,"enableSetThinking":true,"paid":false},
          {"modelType":"huoshan_glm_5_3","description":"GLM-5.3","maxInputLength":40000,
           "enableNetworkSearch":true,"enableSetThinking":false,"paid":false},
          {"modelType":"blian_qwen38_flash","description":"qwen3.8-Flash","maxInputLength":800000,
           "enableNetworkSearch":true,"enableSetThinking":true,"paid":false}
        ]}}
    """.trimIndent()

    private var configStatus = 200
    private var configBody = ""
    private var configCalls = 0

    @BeforeTest
    fun setUp() {
        configBody = chatConfigJson
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(MCloudConstants.PATH_APIKEY_GET) { ex -> respond(ex, 200, apikeyJson) }
        server.createContext(MCloudConstants.PATH_CHAT_CONFIG_GET) { ex ->
            configCalls++
            respond(ex, configStatus, configBody)
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.stop(0) }
    }

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun client() = MCloudKeyClient(aiBase = "http://127.0.0.1:${server.address.port}")

    private fun credential() = LoginCredential(
        basic = "bW9iaWxlOjEzODAwMTM4MDAwOnRva2Vu",
        userId = "1039762344857421899",
    )

    @Test
    fun `登录时把上下文与能力并进模型清单`() {
        val account = client().fetchKey(credential(), "13800138000", 1_000L)
        assertEquals(3, account.models.size)

        val qwenMax = account.models.first { it.code == "blian_qwen38_max" }
        assertEquals(800_000L, qwenMax.maxInputLength)
        assertTrue(qwenMax.enableNetworkSearch)
        assertTrue(qwenMax.enableSetThinking)
        // 倍率来自 apikey/get，不能被补充接口覆盖掉
        assertEquals("1.5", qwenMax.tokenRatio)

        val glm = account.models.first { it.code == "huoshan_glm_5_3" }
        assertEquals(40_000L, glm.maxInputLength)
        assertFalse(glm.enableSetThinking)
    }

    @Test
    fun `chat config 失败不影响登录 只是没有上下文`() {
        configStatus = 500
        configBody = """{"success":false,"code":"9999","message":"boom"}"""
        val account = client().fetchKey(credential(), "13800138000", 1_000L)
        assertEquals(3, account.models.size)
        assertTrue(account.models.all { it.maxInputLength == 0L })
        // 倍率仍在
        assertEquals("0.18", account.models.first { it.code == "blian_qwen38_flash" }.tokenRatio)
    }

    @Test
    fun `配置里没有的模型保持原样 不写 0 覆盖`() {
        configBody = """{"success":true,"code":"0000","data":{"modelList":[
            {"modelType":"blian_qwen38_max","maxInputLength":800000,"enableNetworkSearch":true}
        ]}}""".trimIndent()
        val account = client().fetchKey(credential(), "13800138000", 1_000L)
        val glm = account.models.first { it.code == "huoshan_glm_5_3" }
        assertEquals(0L, glm.maxInputLength)
        assertFalse(glm.enableNetworkSearch)
    }

    @Test
    fun `老账号可补拉能力信息且不改 Key`() {
        val legacy = MCloudAccount(
            uid = "1039762344857421899",
            phone = "13800138000",
            apiKey = "old-key",
            basicCredential = "bW9iaWxlOjEzODAwMTM4MDAwOnRva2Vu",
            models = listOf(
                MCloudModelInfo(code = "blian_qwen38_max", name = "qwen3.8-Max", tokenRatio = "1.5"),
                MCloudModelInfo(code = "huoshan_glm_5_3", name = "GLM-5.3", tokenRatio = "3.5"),
            ),
        )
        val enriched = assertNotNull(client().enrichModelMeta(legacy))
        assertEquals("old-key", enriched.apiKey)
        assertEquals(800_000L, enriched.models.first { it.code == "blian_qwen38_max" }.maxInputLength)
        assertEquals(40_000L, enriched.models.first { it.code == "huoshan_glm_5_3" }.maxInputLength)
    }

    @Test
    fun `没有 Basic 凭证时不补拉 不打上游`() {
        val legacy = MCloudAccount(uid = "u", phone = "p", apiKey = "k", basicCredential = "")
        assertNull(client().enrichModelMeta(legacy))
        assertEquals(0, configCalls)
    }

    @Test
    fun `chat config 请求带 WebView 形态的设备指纹`() {
        // 抓包实测：x-DeviceInfo 第二段是 36（WebView 内核版本），x-yun-client-info 是 1；
        // 设备标识是 UUID 形态。发成 App 原生形态会被上游回「资源不存在」。
        val captured = HashMap<String, String>()
        val probe = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        probe.createContext(MCloudConstants.PATH_APIKEY_GET) { ex -> respond(ex, 200, apikeyJson) }
        probe.createContext(MCloudConstants.PATH_CHAT_CONFIG_GET) { ex ->
            ex.requestHeaders.forEach { (k, v) -> captured[k.lowercase()] = v.firstOrNull().orEmpty() }
            respond(ex, 200, chatConfigJson)
        }
        probe.start()
        try {
            val c = MCloudKeyClient(aiBase = "http://127.0.0.1:${probe.address.port}")
            c.fetchKey(credential(), "13800138000", 1_000L)
            val deviceInfo = captured["x-deviceinfo"].orEmpty()
            val clientInfo = captured["x-yun-client-info"].orEmpty()
            assertTrue(deviceInfo.startsWith("||36|13.2.5||"), "实际：$deviceInfo")
            assertTrue(clientInfo.startsWith("||1|13.2.5||"), "实际：$clientInfo")
            // 设备标识必须是 UUID 形态（8-4-4-4-12），不是 32 位裸 hex
            // 串形态：||1|13.2.5||机型|uuid||android 16|||||
            // split 后：[0]="" [1]="" [2]="1" [3]="13.2.5" [4]="" [5]=机型 [6]=uuid
            val idPart = clientInfo.split("|").getOrNull(6).orEmpty()
            assertTrue(
                Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(idPart),
                "设备标识不是 UUID 形态：$idPart",
            )
        } finally {
            probe.stop(0)
        }
    }

    @Test
    fun `续期时补充接口失败 不能清空已有上下文`() {
        // 场景：账号已有上下文 → Basic 过期 → 续期（apikey/get 成功但 chat/config 失败）
        // 期望：上下文与能力从旧账号继承，不能被一次抖动清空。
        configStatus = 500
        configBody = """{"success":false,"code":"9999","message":"boom"}"""
        val existing = MCloudAccount(
            uid = "1039762344857421899",
            phone = "13800138000",
            apiKey = "old-key",
            basicCredential = "bW9iaWxlOjEzODAwMTM4MDAwOnRva2Vu",
            models = listOf(
                MCloudModelInfo(
                    code = "blian_qwen38_max",
                    name = "qwen3.8-Max",
                    tokenRatio = "1.5",
                    maxInputLength = 800_000L,
                    enableNetworkSearch = true,
                    enableSetThinking = true,
                ),
                MCloudModelInfo(
                    code = "huoshan_glm_5_3",
                    name = "GLM-5.3",
                    tokenRatio = "3.5",
                    maxInputLength = 40_000L,
                ),
            ),
        )
        val refreshed = client().refreshKey(existing, 2_000L)
        // Key 已换新
        assertEquals("a7a330229929e019efc014404067ec40829311116b2b2be4af6200aeb4d16786", refreshed.apiKey)
        // 上下文与能力必须保留
        val qwen = refreshed.models.first { it.code == "blian_qwen38_max" }
        assertEquals(800_000L, qwen.maxInputLength)
        assertTrue(qwen.enableNetworkSearch)
        assertTrue(qwen.enableSetThinking)
        val glm = refreshed.models.first { it.code == "huoshan_glm_5_3" }
        assertEquals(40_000L, glm.maxInputLength)
    }

    @Test
    fun `续期成功时用新的上下文覆盖旧值`() {
        val existing = MCloudAccount(
            uid = "1039762344857421899",
            phone = "13800138000",
            apiKey = "old-key",
            basicCredential = "bW9iaWxlOjEzODAwMTM4MDAwOnRva2Vu",
            models = listOf(
                MCloudModelInfo(code = "blian_qwen38_max", name = "qwen3.8-Max", maxInputLength = 100_000L),
            ),
        )
        val refreshed = client().refreshKey(existing, 2_000L)
        // 上游这次给的是 800000，应当覆盖旧的 100000
        assertEquals(800_000L, refreshed.models.first { it.code == "blian_qwen38_max" }.maxInputLength)
    }
}
