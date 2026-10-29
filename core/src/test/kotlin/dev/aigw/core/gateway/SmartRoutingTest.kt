package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmartRoutingTest {

    private inner class DummyProvider(override val id: String) : Provider {
        override val displayName = id
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = throw UnsupportedOperationException()
    }

    /**
     * 区域型供应商替身：内置 CodeBuddyProvider 已不再回退内置快照，拉不到真实目录就为空，
     * 无法在无网的单测里产生模型；这里用固定目录锁定「按区域拆成多组」的行为。
     */
    private inner class RegionStubProvider(
        override val id: String,
        private val regions: List<String>,
    ) : Provider, dev.aigw.core.provider.RegionAwareSupport {
        override val displayName = id
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) =
            ProviderModelCatalogView(listOf(dev.aigw.core.provider.ProviderModel("glm-5.2", "GLM-5.2")), false, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = throw UnsupportedOperationException()
        override fun regionOf(account: ProviderAccount) =
            regions.firstOrNull { account.uid.endsWith("-$it") } ?: regions.first()

        override fun regions() = regions
    }

    /**
     * 带固定模型目录的桩：启发式跨供应商回退靠目录里的同名模型匹配，
     * 用空目录的桩永远匹配不到，等于测不到候选产生。
     */
    private inner class CatalogProvider(
        override val id: String,
        private val modelIds: List<String>,
    ) : Provider {
        override val displayName = id
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) =
            ProviderModelCatalogView(modelIds.map { dev.aigw.core.provider.ProviderModel(it, it) }, false, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = throw UnsupportedOperationException()
    }

    @Test
    fun `带别名前缀能正确路由且不区分大小写`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("antigravity"))

        assertEquals("antigravity", engine.resolveRoute("谷歌/gemini-2.5-flash")?.providerId)
        assertEquals("gemini-2.5-flash", engine.resolveRoute("谷歌/gemini-2.5-flash")?.model)
        assertEquals("antigravity", engine.resolveRoute("谷歌/claude-sonnet-4-6")?.providerId)

        // 英文前缀不再被识别为供应商前缀（只认中文）
        val english = engine.resolveRoute("google/gemini-2.5-flash")
        assertTrue(
            english == null || english.model != "gemini-2.5-flash" || english.providerId != "antigravity",
            "英文前缀不应再被解析：$english",
        )
    }

    @Test
    fun `中文区域前缀能正确识别区域约束并在模型列表拆为两组`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // 内置 CodeBuddyProvider 已实现 RegionAwareSupport；这里用替身覆盖同名 id，
        // 因为模型目录只在登录后拉取，无内置快照后单测里拿不到真实目录。
        engine.registry.register(RegionStubProvider("codebuddy", listOf("cn", "global")))

        val routeCn = engine.resolveRoute("腾讯国内/deepseek-v3")
        assertEquals("codebuddy", routeCn?.providerId)
        assertEquals("deepseek-v3", routeCn?.model)
        assertEquals("cn", routeCn?.region)

        val routeGlobal = engine.resolveRoute("腾讯国外/claude-3-5-sonnet")
        assertEquals("codebuddy", routeGlobal?.providerId)
        assertEquals("claude-3-5-sonnet", routeGlobal?.model)
        assertEquals("global", routeGlobal?.region)

        // 裸中文品牌名不带区域约束（不限制区域，所有账号都能用）
        assertEquals(null, engine.resolveRoute("腾讯/deepseek-v3")?.region)

        // 模型列表里针对区域型供应商拆出两个独立前缀组（每个区域各用本区域账号拉一次）
        engine.pool.upsert(ProviderAccount("codebuddy", "u-cn", "国内号", "{}"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u-global", "国外号", "{}"))
        val models = engine.models().filter { it.providerId == "codebuddy" }
        val prefixes = models.map { it.routePrefix }.distinct()
        assertTrue(prefixes.contains("腾讯国内"), "应包含国内组前缀：$prefixes")
        assertTrue(prefixes.contains("腾讯国外"), "应包含国外组前缀：$prefixes")



        // 组标题明确区分国内和国外（供应商用中文品牌名，区域标签统一为 国内 / 国外）
        val titles = models.map { it.providerName }.distinct()
        assertTrue(titles.any { it.contains("国内") }, "应包含国内标题：$titles")
        assertTrue(titles.any { it.contains("国外") }, "应包含国外标题：$titles")
    }

    /**
     * 区域型桩：国内站只有 cn-model，国外站只有 global-model，用于验证
     * 「模型在哪边就路由到哪边」——不能把只在国外的模型发给国内账号。
     */
    private inner class SplitRegionProvider(
        override val id: String,
    ) : Provider, dev.aigw.core.provider.RegionAwareSupport {
        override val displayName = id
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
            val region = account?.let { regionOf(it) } ?: "cn"
            val ids = if (region == "cn") listOf("cn-only-model") else listOf("global-only-model")
            return ProviderModelCatalogView(
                ids.map { dev.aigw.core.provider.ProviderModel(it, it) }, false, "",
            )
        }
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            throw UnsupportedOperationException()
        override fun regionOf(account: ProviderAccount) =
            regions().firstOrNull { account.uid.endsWith("-$it") } ?: regions().first()
        override fun regions() = listOf("cn", "global")
    }

    @Test
    fun `无前缀时按模型所在区域自动选站`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(SplitRegionProvider("codebuddy"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u-cn", "国内号", "{}"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u-global", "国外号", "{}"))

        // 只在国外站的模型 → 必须带上 global 区域约束，不能发给国内账号
        val globalRoute = engine.resolveRoute("global-only-model")
        assertNotNull(globalRoute)
        assertEquals("codebuddy", globalRoute.providerId)
        assertEquals("global", globalRoute.region, "只在国外的模型应该路由到国外账号")

        // 只在国内站的模型 → cn
        val cnRoute = engine.resolveRoute("cn-only-model")
        assertNotNull(cnRoute)
        assertEquals("codebuddy", cnRoute.providerId)
        assertEquals("cn", cnRoute.region, "只在国内的模型应该路由到国内账号")
    }

    @Test
    fun `显式区域前缀仍然可用来强制指定`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(SplitRegionProvider("codebuddy"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u-cn", "国内号", "{}"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u-global", "国外号", "{}"))

        // 高级用法：显式写区域前缀时以用户为准（强制走国内）
        val forced = engine.resolveRoute("腾讯国内/global-only-model")
        assertEquals("codebuddy", forced?.providerId)
        assertEquals("cn", forced?.region, "显式区域前缀应覆盖自动判定")
    }

    @Test
    fun `只认中文前缀`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("codebuddy"))
        engine.registry.register(DummyProvider("trae"))
        engine.registry.register(DummyProvider("antigravity"))
        engine.registry.register(DummyProvider("loomy"))
        engine.registry.register(DummyProvider("raccoon"))

        // 中文前缀
        assertEquals("codebuddy", engine.resolveRoute("腾讯/glm-5.2")?.providerId)
        assertEquals("trae", engine.resolveRoute("字节/qwen-max")?.providerId)
        assertEquals("antigravity", engine.resolveRoute("谷歌/gemini-3.8")?.providerId)
        assertEquals("loomy", engine.resolveRoute("讯飞/spark-4")?.providerId)
        assertEquals("raccoon", engine.resolveRoute("商汤/raccoon-chat")?.providerId)
        assertEquals("glm-5.2", engine.resolveRoute("腾讯/glm-5.2")?.model)

        // 英文前缀不再被当作供应商前缀：整串会按"无前缀"处理，
        // 前缀部分不会被剥掉（避免出现「英文也偷偷能用」的两套写法）。
        // 这里断言的是「没有被识别成 codebuddy 前缀」，即 model 保留完整串。
        val english = engine.resolveRoute("codebuddy/glm-5.2")
        assertTrue(
            english == null || english.model != "glm-5.2" || english.providerId != "codebuddy",
            "英文前缀不应再被解析为供应商前缀：$english",
        )
    }


    @Test
    fun `无前缀时按模型真实归属路由而非谁先注册`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // 腾讯（codebuddy）只有 deepseek / glm；字节（trae）才有 qwen 与 seed。
        // 两家都有账号 —— 正是原先「谁先注册用谁」会出错的前提。
        engine.registry.register(CatalogProvider("codebuddy", listOf("deepseek-v3", "glm-5.2")))
        engine.registry.register(CatalogProvider("trae", listOf("qwen-max", "seed-1.6")))
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "腾讯号", "{}"))
        engine.pool.upsert(ProviderAccount("trae", "t1", "字节号", "{}"))

        // 用腾讯的模型就路由到腾讯
        assertEquals("codebuddy", engine.resolveRoute("deepseek-v3")?.providerId)
        assertEquals("codebuddy", engine.resolveRoute("glm-5.2")?.providerId)
        // 用字节的模型就路由到字节（原先 glm/qwen 会一律先落到 codebuddy）
        assertEquals("trae", engine.resolveRoute("qwen-max")?.providerId)
        assertEquals("trae", engine.resolveRoute("seed-1.6")?.providerId)

        // 大小写与下划线/空格写法归一后仍能命中真实归属
        assertEquals("trae", engine.resolveRoute("QWEN-MAX")?.providerId)
        assertEquals("codebuddy", engine.resolveRoute("deepseek_v3")?.providerId)
    }

    @Test
    fun `目录里查不到时不瞎猜品牌归属`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // 两家都启用且有账号，但目录里都没有 kimi-k2
        engine.registry.register(CatalogProvider("codebuddy", listOf("deepseek-v3")))
        engine.registry.register(CatalogProvider("trae", listOf("qwen-max")))
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "腾讯号", "{}"))
        engine.pool.upsert(ProviderAccount("trae", "t1", "字节号", "{}"))

        // 不能因为品牌列表里先写了 codebuddy 就硬塞给腾讯：交给兜底（有账号的一家）
        val route = engine.resolveRoute("kimi-k2")
        assertNotNull(route)
        assertTrue(
            route.providerId == "codebuddy" || route.providerId == "trae",
            "应落到有账号的供应商：${route.providerId}",
        )
    }

    @Test
    fun `显式前缀优先于目录归属`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(CatalogProvider("codebuddy", listOf("deepseek-v3")))
        engine.registry.register(CatalogProvider("trae", listOf("deepseek-v3")))
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "腾讯号", "{}"))
        engine.pool.upsert(ProviderAccount("trae", "t1", "字节号", "{}"))

        // 两家目录都有 deepseek-v3，写了前缀就必须按前缀走
        assertEquals("trae", engine.resolveRoute("字节/deepseek-v3")?.providerId)
        assertEquals("codebuddy", engine.resolveRoute("腾讯/deepseek-v3")?.providerId)
    }

    @Test
    fun `无前缀时根据模型特征智能路由到 antigravity`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("trae"))
        engine.registry.register(DummyProvider("antigravity"))
        // 反重力有账号，trae 没有
        engine.pool.upsert(ProviderAccount("antigravity", "u1", "谷歌账号", "{}"))

        val route1 = engine.resolveRoute("gemini-3.8-flash-high")
        assertNotNull(route1)
        assertEquals("antigravity", route1.providerId)
        assertEquals("gemini-3.8-flash-high", route1.model)

        val route2 = engine.resolveRoute("claude-opus-4-6-thinking")
        assertNotNull(route2)
        assertEquals("antigravity", route2.providerId)
    }

    @Test
    fun `用户仅配置反重力一个供应商时所有无前缀请求由反重力接管`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("trae"))
        engine.registry.register(DummyProvider("antigravity"))
        engine.registry.register(DummyProvider("codebuddy"))
        // 只有 antigravity 有账号
        engine.pool.upsert(ProviderAccount("antigravity", "u1", "唯一账号", "{}"))

        // 即使用户请求 auto 或随意模型名，也直接由反重力接管，而不是退回到 trae 报 503
        val route = engine.resolveRoute("auto")
        assertNotNull(route)
        assertEquals("antigravity", route.providerId)
    }

    @Test
    fun `关闭故障转移后不再产生跨供应商候选`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // 两家都提供同名模型且都有账号 —— 满足启发式回退产生候选的条件
        engine.registry.register(CatalogProvider("trae", listOf("gemini-2.5-flash")))
        engine.registry.register(CatalogProvider("antigravity", listOf("gemini-2.5-flash")))
        engine.pool.upsert(ProviderAccount("trae", "t1", "trae 账号", "{}"))
        engine.pool.upsert(ProviderAccount("antigravity", "a1", "反重力账号", "{}"))

        val candidates = engine.resolveCandidateRoutes("字节/gemini-2.5-flash")
        assertTrue(candidates.size > 1, "开关开启时应能产生跨供应商候选：$candidates")

        // 关闭后只允许首选路由：此前 enabled 只在「显式路由表」分支生效，
        // 启发式回退照跑，表现为「界面关了故障转移、实际仍在自动换供应商」
        engine.updateFailoverSettings(engine.failoverSettings().copy(enabled = false))
        val disabled = engine.resolveCandidateRoutes("字节/gemini-2.5-flash")
        assertEquals(1, disabled.size, "关闭后不得有跨供应商候选：$disabled")
        assertEquals("trae", disabled.single().providerId)
    }
}
