package io.synapse.core.pool

import io.synapse.core.provider.ProviderAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 账号导入导出的信封编解码：只测纯转换（不认识账号池），
 * 重点是「挡得住不相干的 JSON」与「不造出没有凭证的空账号」。
 */
class AccountTransferTest {

    private fun account(uid: String, providerId: String = "trae") = ProviderAccount(
        providerId = providerId,
        uid = uid,
        nickname = "昵称$uid",
        secret = """{"uid":"$uid","accessToken":"at-$uid"}""",
    )

    @Test
    fun `导出再导入能拿回同样的账号`() {
        val source = listOf(account("a"), account("b", providerId = "loomy"))
        val json = AccountTransfer.encode(source.map { AccountSnapshot(it) }, exportedAt = 123L)
        val envelope = AccountTransfer.decode(json)
        assertEquals(AccountTransfer.VERSION, envelope.version)
        assertEquals(123L, envelope.exportedAt)
        assertEquals(source, envelope.accounts.map { it.account })
    }

    @Test
    fun `账号的启用开关与优先级会一起往返`() {
        val source = listOf(
            AccountSnapshot(account("a"), enabled = false),
            AccountSnapshot(account("b"), priority = 7),
        )
        val envelope = AccountTransfer.decode(
            AccountTransfer.encode(source, exportedAt = 0L),
        )
        assertEquals(source, envelope.accounts)
    }

    @Test
    fun `老版本文件被明确拒绝，不做兼容读取`() {
        // 导入是「按快照原样恢复」：v1/v2 里没有启用开关与优先级，
        // 猜默认值补出来的结果跟你导出时并不是同一份。报错让用户重新导出才安全。
        for (version in listOf(1, 2)) {
            val json = """
                {"format":"aigw-accounts","version":$version,"exportedAt":0,"accounts":[
                  {"providerId":"trae","uid":"a","nickname":"甲","secret":"{x}"}
                ]}
            """.trimIndent()
            val e = assertFailsWith<IllegalArgumentException> { AccountTransfer.decode(json) }
            assertTrue(e.message!!.contains("v$version"), e.message!!)
            assertTrue(e.message!!.contains("重新导出"), e.message!!)
        }
    }

    @Test
    fun `不是本应用导出的文件要被拒绝`() {
        val e = assertFailsWith<IllegalArgumentException> {
            AccountTransfer.decode("""{"foo":"bar","accounts":[]}""")
        }
        assertTrue(e.message!!.contains("不是本应用导出的账号文件"), e.message!!)
    }

    @Test
    fun `高于当前版本的文件要被拒绝`() {
        val json = """{"format":"aigw-accounts","version":99,"exportedAt":0,"accounts":[]}"""
        val e = assertFailsWith<IllegalArgumentException> { AccountTransfer.decode(json) }
        assertTrue(e.message!!.contains("v99"), e.message!!)
    }

    @Test
    fun `缺字段或空凭证的条目被丢掉，而不是造出空账号`() {
        val json = """
            {"format":"aigw-accounts","version":3,"exportedAt":0,"accounts":[
              {"providerId":"trae","uid":"a","nickname":"甲","secret":"{x}"},
              {"providerId":"trae","uid":"b","nickname":"乙"},
              {"providerId":"","uid":"c","secret":"{x}"}
            ]}
        """.trimIndent()
        val envelope = AccountTransfer.decode(json)
        assertEquals(1, envelope.accounts.size)
        assertEquals("a", envelope.accounts[0].account.uid)
    }

    @Test
    fun `空内容与非法 JSON 给可读报错`() {
        assertTrue(
            assertFailsWith<IllegalArgumentException> { AccountTransfer.decode("  ") }
                .message!!.contains("内容为空"),
        )
        assertTrue(
            assertFailsWith<IllegalArgumentException> { AccountTransfer.decode("{oops") }
                .message!!.contains("不是合法的 JSON"),
        )
    }

    @Test
    fun `解析出全部条目都不可用时报错而不是静默成功`() {
        val json = """{"format":"aigw-accounts","version":3,"exportedAt":0,"accounts":[{"providerId":"trae","uid":"a"}]}"""
        val e = assertFailsWith<IllegalArgumentException> { AccountTransfer.decode(json) }
        assertTrue(e.message!!.contains("没有可用的账号"), e.message!!)
    }

    @Test
    fun `自定义供应商的新字段导入导出往返一致`() {
        // 覆盖新增的 protocol / headers / balanceQuery(含 method/body/preset/unit)
        val cfg = io.synapse.core.gateway.CustomProviderConfig(
            key = "mysite",
            name = "我的站点",
            baseUrl = "https://api.mysite.com/v1",
            apiKeys = emptyList(),
            protocol = "anthropic",
            models = listOf("claude-3-5", "claude-4"),
            enabled = false,
            headers = listOf(
                io.synapse.core.gateway.HttpHeader("X-Tenant", "acme"),
                io.synapse.core.gateway.HttpHeader("x-api-key", "override"),
            ),
            balanceQuery = io.synapse.core.gateway.BalanceQueryConfig(
                path = "/api/user/self",
                field = "data.quota,balance",
                scale = 500_000.0,
                divide = true,
                unit = "$",
                method = "POST",
                body = """{"scope":"all"}""",
                preset = "new_api",
            ),
        )
        val json = AccountTransfer.encode(
            accounts = listOf(AccountSnapshot(ProviderAccount("custom:mysite", "uid1", "k1", "{}"))),
            exportedAt = 999L,
            providers = listOf(cfg),
        )
        val decoded = AccountTransfer.decode(json)
        assertEquals(1, decoded.providers.size)
        val got = decoded.providers.first()
        assertEquals(cfg.key, got.key)
        assertEquals("anthropic", got.protocol)
        assertEquals(cfg.models, got.models)
        assertEquals(false, got.enabled)
        assertEquals(cfg.headers, got.headers, "自定义请求头不能丢")
        assertEquals(cfg.balanceQuery, got.balanceQuery, "余额配置（含 POST body / preset）不能丢")
    }

    @Test
    fun `老版本导出文件缺少新字段时回退到默认值`() {
        // v3 里没有 method/body/preset 的旧文件仍能导入
        val legacy = """{"format":"aigw-accounts","version":3,"exportedAt":1,
            "accounts":[{"providerId":"trae","uid":"u1","nickname":"n","secret":"{}","enabled":true,"priority":0}],
            "providers":[{"key":"old","name":"old","baseUrl":"https://x/v1",
            "protocol":"openai","enabled":true,"models":[],"balanceQuery":
            {"url":"","path":"/api/x","field":"balance","scale":1.0,"divide":false,"unit":""}}]}"""
        val decoded = AccountTransfer.decode(legacy)
        assertEquals(1, decoded.providers.size)
        val bq = decoded.providers.first().balanceQuery
        assertNotNull(bq)
        val q = bq
        assertEquals("GET", q.method)
        assertEquals("", q.body)
        assertEquals("", q.preset)
    }
}
