package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.custom.CustomProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 自定义供应商配置 → 账号池的同步。
 *
 * 回归背景：新建供应商时填的 API Key 只落在供应商配置里，详情页读的是账号池，
 * 不同步就会「保存完进详情页看不到刚填的 key」。
 */
class CustomProviderSyncTest {

    private fun engine() = GatewayEngine(InMemoryKeyValueStore())

    private fun config(keys: List<String>) = CustomProviderConfig(
        key = "site",
        name = "站点",
        baseUrl = "https://api.example.com/v1",
        apiKeys = keys,
        models = listOf("gpt-4o"),
    )

    @Test
    fun `保存配置时把 key 同步成账号`() {
        val engine = engine()
        engine.syncCustomAccounts(config(listOf("sk-1", "sk-2")))

        val accounts = engine.accounts("custom:site")
        assertEquals(2, accounts.size)
        assertEquals(
            setOf(CustomProvider.uidOf("sk-1"), CustomProvider.uidOf("sk-2")),
            accounts.map { it.uid }.toSet(),
        )
        assertTrue(accounts.all { it.providerId == "custom:site" })
        assertTrue(accounts.all { it.nickname == "站点" })
    }

    @Test
    fun `同步只增不删，不会误删既有账号`() {
        val engine = engine()
        engine.syncCustomAccounts(config(listOf("sk-1", "sk-2")))
        // 再同步一份只含 sk-2 的配置：sk-1 不得被删。
        // 新表单编辑时本来就只带得上「原有摘要 + 新增 Key」，
        // 若在这里对账，用户没动过的账号会被误删。
        engine.syncCustomAccounts(config(listOf("sk-2")))

        val uids = engine.accounts("custom:site").map { it.uid }.toSet()
        assertEquals(setOf(CustomProvider.uidOf("sk-1"), CustomProvider.uidOf("sk-2")), uids)

        // 追加一个新 key
        engine.syncCustomAccounts(config(listOf("sk-2", "sk-3")))
        assertEquals(3, engine.accounts("custom:site").size)
    }

    @Test
    fun `空 apiKeys 配置不会删掉已有账号`() {
        val engine = engine()
        engine.syncCustomAccounts(config(listOf("sk-1", "sk-2")))
        assertEquals(2, engine.accounts("custom:site").size)

        // 编辑供应商基本信息时 apiKeys 恒为空（key 由「添加账号」单独维护）：
        // 必须原样保留账号，否则保存一次就丢光全部 key。
        engine.syncCustomAccounts(config(emptyList()))
        assertEquals(2, engine.accounts("custom:site").size, "空表同步不得删除任何账号")
    }

    @Test
    fun `重复同步不会产生重复账号`() {
        val engine = engine()
        repeat(3) { engine.syncCustomAccounts(config(listOf("sk-1"))) }
        assertEquals(1, engine.accounts("custom:site").size)
    }

    @Test
    fun `账号 secret 里存的是完整 key`() {
        val engine = engine()
        engine.syncCustomAccounts(config(listOf("sk-abc")))
        val account = engine.account("custom:site", CustomProvider.uidOf("sk-abc"))
        assertTrue(account != null)
        assertTrue(account.secret.contains("sk-abc"))
    }
}
