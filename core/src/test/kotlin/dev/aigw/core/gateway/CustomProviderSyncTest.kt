package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.pool.AccountSnapshot
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
    fun `导出会带上自定义供应商定义，换机器导入能自动建出来`() {
        // 旧机器：有一个自定义供应商 + 一个它的账号
        val src = engine()
        src.settingsRepository.saveCustomProviders(
            listOf(
                dev.aigw.core.gateway.CustomProviderConfig(
                    key = "小红书",
                    name = "小红书中转",
                    baseUrl = "https://api.xhs.example/v1",
                    apiKeys = emptyList(),
                    models = listOf("gpt-4o", "claude-3"),
                ),
            ),
        )
        src.reloadCustomProviders()
        src.pool.upsert(
            dev.aigw.core.provider.ProviderAccount(
                providerId = "custom:小红书",
                uid = "key-1",
                nickname = "我的号",
                secret = "sk-x",
            ),
        )

        val payload = src.exportAccounts()

        // 导出内容里必须带 providers（供应商定义）
        val envelope = dev.aigw.core.pool.AccountTransfer.decode(payload)
        assertEquals(1, envelope.providers.size, "导出必须带上自定义供应商定义")
        assertEquals("小红书", envelope.providers.first().key)
        assertEquals("https://api.xhs.example/v1", envelope.providers.first().baseUrl)

        // 新机器：什么都没有
        val dst = GatewayEngine(InMemoryKeyValueStore())
        assertTrue(dst.settingsRepository.loadCustomProviders().isEmpty())

        val result = dst.importAccounts(payload, overwrite = false)

        // 供应商被自动建出来，账号才落得下去
        assertEquals(1, result.createdProviders.size, "应随文件新建供应商")
        assertEquals(1, result.added.size, "账号应当导入成功")
        assertTrue(result.unknownProviders.isEmpty(), "不应有未知供应商")
        assertEquals(
            "https://api.xhs.example/v1",
            dst.settingsRepository.loadCustomProviders().single().baseUrl,
        )
    }

    @Test
    fun `导入自定义供应商账号时不会误判为未知供应商`() {
        val engine = engine()
        // 先存一条自定义供应商配置（模拟本机已创建过该供应商）
        engine.settingsRepository.saveCustomProviders(
            listOf(
                dev.aigw.core.gateway.CustomProviderConfig(
                    key = "小红书",
                    name = "小红书中转",
                    baseUrl = "https://api.example.com/v1",
                    apiKeys = emptyList(),
                    models = emptyList(),
                ),
            ),
        )

        // 造一份含该自定义供应商的导出内容
        val payload = dev.aigw.core.pool.AccountTransfer.encode(
            listOf(
                AccountSnapshot(
                    dev.aigw.core.provider.ProviderAccount(
                        providerId = "custom:小红书",
                        uid = "key-abc123",
                        nickname = "我和号",
                        secret = "sk-test",
                    ),
                ),
            ),
            exportedAt = 0L,
        )

        val result = engine.importAccounts(payload, overwrite = false)

        // 关键：不得落到 unknownProviders（之前就是这里整批被当成未知供应商）
        assertTrue(
            result.unknownProviders.isEmpty(),
            "自定义供应商不应被误判为未知：${result.unknownProviders}",
        )
        assertEquals(1, result.added.size, "应当新增 1 个账号")
        assertEquals("key-abc123", engine.accounts("custom:小红书").single().uid)
    }

    @Test
    fun `文件没带供应商定义时，本机也会按标签补建（v1 文件）`() {
        // 老版本导出的文件：只有账号，没有 providers 段。
        // 用户的真实场景就是「本机还没创建这个供应商，所以要靠导入把它带出来」。
        val payload = dev.aigw.core.pool.AccountTransfer.encode(
            listOf(
                AccountSnapshot(
                    dev.aigw.core.provider.ProviderAccount(
                        providerId = "custom:小红书",
                        uid = "key-ea48c492",
                        nickname = "我的号",
                        secret = "sk-test",
                    ),
                ),
            ),
            exportedAt = 0L,
        )

        val dst = GatewayEngine(InMemoryKeyValueStore())

        val result = dst.importAccounts(payload, overwrite = false)

        assertEquals(1, result.createdProviders.size, "应补建占位供应商")
        assertEquals(1, result.added.size, "账号应当导入成功")
        assertTrue(result.unknownProviders.isEmpty(), "不应有未知供应商")

        // 关键回归：占位配置的 baseUrl 是空的，落盘后必须还能读回来。
        // 曾经 fromJson 要求 baseUrl 非空，占位配置一写下去就被丢 → 账号永远进不来。
        val saved = dst.settingsRepository.loadCustomProviders().single()
        assertEquals("小红书", saved.key)
        assertEquals("", saved.baseUrl, "地址留空，等用户自己补")
        assertTrue(dst.registry.get("custom:小红书") != null, "补建后应注册成可用供应商")
    }

    @Test
    fun `占位配置能落盘读回，空 key 才被拒`() {
        val placeholder = CustomProviderConfig(
            key = "待补地址",
            name = "待补地址",
            baseUrl = "",
            apiKeys = emptyList(),
            models = emptyList(),
        )
        val restored = CustomProviderConfig.fromJson(placeholder.toJson())
        assertTrue(restored != null, "地址为空的占位配置不能在这里被丢掉")
        assertEquals("", restored.baseUrl)

        val noKey = com.google.gson.JsonObject().apply { addProperty("baseUrl", "https://a/v1") }
        assertEquals(null, CustomProviderConfig.fromJson(noKey), "没有 key 的配置仍然无效")
    }

    @Test
    fun `地址未填的供应商不会盲目发请求`() {
        val provider = CustomProvider(
            config = CustomProviderConfig(
                key = "待补地址",
                name = "待补地址",
                baseUrl = "",
                apiKeys = emptyList(),
                models = emptyList(),
            ),
        )
        val call = provider.openChat(
            dev.aigw.core.provider.ProviderAccount(
                providerId = "custom:待补地址",
                uid = "key-1",
                nickname = "n",
                secret = CustomProvider.secretOf("sk-x"),
            ),
            """{"model":"gpt-4o"}""",
        )
        assertTrue(
            call is dev.aigw.core.provider.FailedChatCall,
            "地址为空应直接失败，而不是拼一个相对地址出去",
        )
        assertTrue(call.status == 0)
    }

    @Test
    fun `只给本次勾选的账号建供应商，没勾的不会凭空出现`() {
        val payload = dev.aigw.core.pool.AccountTransfer.encode(
            accounts = listOf(
                AccountSnapshot(dev.aigw.core.provider.ProviderAccount("custom:甲", "key-1", "a", "sk-a")),
                AccountSnapshot(dev.aigw.core.provider.ProviderAccount("custom:乙", "key-2", "b", "sk-b")),
            ),
            exportedAt = 0L,
            providers = listOf(
                CustomProviderConfig("甲", "甲", "https://a.example/v1", emptyList(), models = listOf("m1")),
                CustomProviderConfig("乙", "乙", "https://b.example/v1", emptyList(), models = listOf("m2")),
            ),
        )

        val dst = GatewayEngine(InMemoryKeyValueStore())
        val result = dst.importAccounts(payload, overwrite = false, selection = setOf("custom:甲/key-1"))

        assertEquals(listOf("甲"), result.createdProviders, "只应创建被勾选账号用到的供应商")
        assertEquals(1, result.added.size)
        val saved = dst.settingsRepository.loadCustomProviders()
        assertEquals(listOf("甲"), saved.map { it.key })
        assertEquals("https://a.example/v1", saved.single().baseUrl, "v2 文件要连地址一起恢复")
        assertTrue(result.placeholderProviders.isEmpty(), "带地址的不算占位")
    }

    @Test
    fun `v1 补建的供应商会被标成待补地址`() {
        val payload = dev.aigw.core.pool.AccountTransfer.encode(
            accounts = listOf(
                AccountSnapshot(dev.aigw.core.provider.ProviderAccount("custom:小红书", "key-1", "a", "sk-a")),
            ),
            exportedAt = 0L,
        )

        val result = GatewayEngine(InMemoryKeyValueStore()).importAccounts(payload, overwrite = false)

        assertEquals(listOf("小红书"), result.createdProviders)
        assertEquals(listOf("小红书"), result.placeholderProviders, "旧文件没带地址，要提示用户补")
    }

    @Test
    fun `地址为空的供应商定义能在导出文件里往返`() {
        val placeholder = CustomProviderConfig(
            key = "待补地址",
            name = "待补地址",
            baseUrl = "",
            apiKeys = emptyList(),
            models = emptyList(),
        )
        val payload = dev.aigw.core.pool.AccountTransfer.encode(
            accounts = listOf(
                AccountSnapshot(dev.aigw.core.provider.ProviderAccount("custom:待补地址", "key-1", "a", "sk-a")),
            ),
            exportedAt = 0L,
            providers = listOf(placeholder),
        )

        // 解码阶段不能把它丢掉（丢了就又变成「供应商不存在」）
        val decoded = dev.aigw.core.pool.AccountTransfer.decode(payload)
        assertEquals(1, decoded.providers.size)
        assertEquals("", decoded.providers.single().baseUrl)

        val dst = GatewayEngine(InMemoryKeyValueStore())
        val result = dst.importAccounts(payload, overwrite = false)
        assertEquals(1, result.added.size)
        assertEquals(listOf("待补地址"), result.placeholderProviders)
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

    @Test
    fun `整份快照导出再导入，供应商与账号状态一模一样`() {
        val src = engine()
        src.settingsRepository.saveCustomProviders(
            listOf(
                CustomProviderConfig(
                    key = "站",
                    name = "站点",
                    baseUrl = "https://a.example/v1",
                    apiKeys = emptyList(),
                    protocol = "openai",
                    models = listOf("m1", "m2"),
                    enabled = false,
                ),
            ),
        )
        src.reloadCustomProviders()
        src.pool.upsert(dev.aigw.core.provider.ProviderAccount("custom:站", "key-1", "我的号", "sk-a"))
        src.pool.setEnabled("custom:站", "key-1", false, reason = "手动停用")
        src.pool.setPriority("custom:站", "key-1", 3)

        // 导出就是那一刻的快照
        val payload = src.exportAccounts()

        // 全新一台设备
        val dst = engine()
        dst.importAccounts(payload, overwrite = false)

        val saved = dst.settingsRepository.loadCustomProviders().single()
        assertEquals("站点", saved.name)
        assertEquals("https://a.example/v1", saved.baseUrl)
        assertEquals(listOf("m1", "m2"), saved.models, "模型列表要跟着回来")
        assertEquals(false, saved.enabled, "停用的供应商导入后不能自己变成启用")

        val status = dst.pool.status("custom:站", "key-1")
        assertTrue(status != null)
        assertEquals(false, status.enabled, "停用的账号导入后不能自己变成启用")
        assertEquals(3, status.priority, "优先级要跟着回来")
    }

    @Test
    fun `本机已有的供应商会被文件里的快照盖回去`() {
        // 本机：地址被改过、模型是旧的、还开着
        val dst = engine()
        dst.settingsRepository.saveCustomProviders(
            listOf(
                CustomProviderConfig(
                    key = "小红书",
                    name = "小红书",
                    baseUrl = "https://本机改过的/v1",
                    apiKeys = emptyList(),
                    models = listOf("旧模型"),
                    enabled = true,
                ),
            ),
        )
        dst.reloadCustomProviders()

        // 文件里是导出那一刻的状态
        val payload = dev.aigw.core.pool.AccountTransfer.encode(
            accounts = listOf(
                AccountSnapshot(dev.aigw.core.provider.ProviderAccount("custom:小红书", "key-1", "a", "sk-a")),
            ),
            exportedAt = 0L,
            providers = listOf(
                CustomProviderConfig(
                    key = "小红书",
                    name = "小红书中转",
                    baseUrl = "https://导出时的/v1",
                    apiKeys = emptyList(),
                    models = listOf("gpt-4o", "claude-3"),
                    enabled = false,
                ),
            ),
        )

        val result = dst.importAccounts(payload, overwrite = false)

        // 供应商配置无条件按快照恢复；账号那边 overwrite=false 所以跳过
        assertEquals(listOf("小红书"), result.restoredProviders)
        assertTrue(result.createdProviders.isEmpty(), "本机已经有了，不算新建")
        assertTrue(result.placeholderProviders.isEmpty(), "文件带了地址，不是占位")

        val saved = dst.settingsRepository.loadCustomProviders().single()
        assertEquals("小红书中转", saved.name)
        assertEquals("https://导出时的/v1", saved.baseUrl)
        assertEquals(listOf("gpt-4o", "claude-3"), saved.models)
        assertEquals(false, saved.enabled)
    }
}
