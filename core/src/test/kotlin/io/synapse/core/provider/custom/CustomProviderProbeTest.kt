package io.synapse.core.provider.custom

import com.sun.net.httpserver.HttpServer
import io.synapse.core.gateway.CustomProviderConfig
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 保存前连通性探针的判定规则。
 *
 * 这里锁住的核心结论：**`/v1/models` 返回 404 不能判成失败**。
 * 很多中转站只实现 `chat/completions`，没有 `models`，它们完全可用。
 * 早先的版本拿「拉模型失败」去弹窗报错，用户明明能用却被提示配置错了。
 *
 * 反过来，401/403 是真的 Key / 请求头不对，必须拦下来当场告诉用户。
 */
class CustomProviderProbeTest {

    private fun withModelsEndpoint(
        status: Int,
        body: String = "{}",
        block: (baseUrl: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
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

    private fun provider(baseUrl: String) = CustomProvider(
        CustomProviderConfig(key = "t", name = "测试", baseUrl = baseUrl, apiKeys = listOf("sk-1")),
    )

    @Test
    fun `200 带模型列表通过`() {
        withModelsEndpoint(200, """{"data":[{"id":"gpt-4o"}]}""") { baseUrl ->
            assertNull(provider(baseUrl).probeModels("sk-1").reason)
        }
    }

    @Test
    fun `200 但模型为空也通过`() {
        withModelsEndpoint(200, """{"data":[]}""") { baseUrl ->
            assertNull(provider(baseUrl).probeModels("sk-1").reason)
        }
    }

    @Test
    fun `404 没实现 models 接口仍然通过`() {
        withModelsEndpoint(404, """{"error":"not found"}""") { baseUrl ->
            assertNull(provider(baseUrl).probeModels("sk-1").reason)
        }
    }

    @Test
    fun `405 通过`() {
        withModelsEndpoint(405, "") { baseUrl ->
            assertNull(provider(baseUrl).probeModels("sk-1").reason)
        }
    }

    @Test
    fun `500 上游故障不判失败`() {
        withModelsEndpoint(500, """{"error":"boom"}""") { baseUrl ->
            assertNull(provider(baseUrl).probeModels("sk-1").reason)
        }
    }

    @Test
    fun `401 鉴权失败必须拦下`() {
        withModelsEndpoint(401, """{"error":{"message":"invalid api key"}}""") { baseUrl ->
            val reason = provider(baseUrl).probeModels("sk-1").reason
            assertNotNull(reason)
            assertTrue(reason.contains("401"))
            assertTrue(reason.contains("invalid api key"))
        }
    }

    @Test
    fun `403 鉴权失败必须拦下`() {
        withModelsEndpoint(403, """{"message":"forbidden"}""") { baseUrl ->
            assertNotNull(provider(baseUrl).probeModels("sk-1").reason)
        }
    }

    @Test
    fun `地址为空直接报错`() {
        val reason = provider("").probeModels("sk-1").reason
        assertNotNull(reason)
        assertTrue(reason.contains("地址"))
    }

    @Test
    fun `连不上时报连接失败`() {
        // 127.0.0.1:1 基本不会有服务在听
        val reason = provider("http://127.0.0.1:1/v1").probeModels("sk-1").reason
        assertNotNull(reason)
    }

    @Test
    fun `secret 里的 JSON 能被还原成裸 Key`() {
        assertEquals("sk-abc", CustomProvider.parseKeyOf(CustomProvider.secretOf("sk-abc")))
        // 老数据直接存裸 Key 时原样返回
        assertEquals("sk-raw", CustomProvider.parseKeyOf("sk-raw"))
        assertNull(CustomProvider.parseKeyOf(""))
    }

    private fun assertEquals(expected: Any?, actual: Any?) = kotlin.test.assertEquals(expected, actual)
}
