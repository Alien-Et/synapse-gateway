package io.synapse.core.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 自定义供应商新增的 `headers` / `balanceQuery` 两个字段的读写回归。
 *
 * 重点是**向后兼容**：这两个字段是新加的，老配置文件里没有，
 * 读的时候必须给出空值而不是把整条配置读丢。
 */
class CustomProviderHeadersConfigTest {

    private fun obj(raw: String) = com.google.gson.JsonParser.parseString(raw).asJsonObject

    @Test
    fun `老配置没有新字段时仍然能读出来`() {
        val config = CustomProviderConfig.fromJson(
            obj("""{"key":"a","name":"A","baseUrl":"https://a.example/v1","apiKeys":["sk-1"]}"""),
        )
        assertNotNull(config)
        assertTrue(config.headers.isEmpty())
        assertNull(config.balanceQuery)
    }

    @Test
    fun `头与余额配置能往返`() {
        val original = CustomProviderConfig(
            key = "a",
            name = "A",
            baseUrl = "https://a.example/v1",
            apiKeys = listOf("sk-1"),
            headers = listOf(
                HttpHeader("X-Tenant", "abc"),
                HttpHeader("User-Agent", "MyClient/1.0"),
            ),
            balanceQuery = BalanceQueryConfig(
                path = "/api/user/self",
                field = "data.quota",
                scale = 500000.0,
                divide = true,
                unit = "元",
            ),
        )
        val restored = CustomProviderConfig.fromJson(original.toJson())
        assertNotNull(restored)
        assertEquals(original.headers, restored.headers)
        assertEquals(original.balanceQuery, restored.balanceQuery)
    }

    @Test
    fun `非法的头在解析时被丢弃而不是整条配置失败`() {
        val config = CustomProviderConfig.fromJson(
            obj(
                """{"key":"a","baseUrl":"https://a.example/v1",
                   "headers":[{"name":"X Bad","value":"1"},{"name":"X-Ok","value":"2"}]}""",
            ),
        )
        // 一条坏头不能连累整家供应商——那会让人以为「保存没生效」
        assertNotNull(config)
        assertEquals(listOf(HttpHeader("X-Ok", "2")), config.headers)
    }

    @Test
    fun `头里带换行会被拒`() {
        val config = CustomProviderConfig.fromJson(
            obj(
                """{"key":"a","baseUrl":"https://a.example/v1",
                   "headers":[{"name":"X-Evil","value":"1\r\nInjected: x"}]}""",
            ),
        )
        assertNotNull(config)
        assertTrue(config.headers.isEmpty())
    }

    @Test
    fun `倍数为 0 或负数时回退 1`() {
        val zero = BalanceQueryConfig.fromJson(obj("""{"field":"b","scale":0}"""))
        assertEquals(1.0, zero!!.scale)
        val negative = BalanceQueryConfig.fromJson(obj("""{"field":"b","scale":-5}"""))
        assertEquals(1.0, negative!!.scale)
    }

    @Test
    fun `全空的余额配置不落盘`() {
        assertNull(BalanceQueryConfig.fromJson(obj("""{"url":"","path":"","field":""}""")))
    }
}
