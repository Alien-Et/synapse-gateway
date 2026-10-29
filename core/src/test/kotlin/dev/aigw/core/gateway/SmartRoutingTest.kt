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
        // 新规则：标签必须指向一个「已启用且有账号」的供应商，否则标签作废。
        engine.pool.upsert(ProviderAccount("antigravity", "u1", "谷歌账号", "{}"))

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
        // 账号必须先加：新的标签规则要求标签指向的供应商「已启用且有账号」，
        // 否则标签会被判无效、直接返回 null。
        engine.pool.upsert(ProviderAccount("codebuddy", "u-cn", "国内号", "{}"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u-global", "国外号", "{}"))

        val routeCn = engine.resolveRoute("腾讯国内/deepseek-v3")
        assertEquals("codebuddy", routeCn?.providerId)
        assertEquals("deepseek-v3", routeCn?.model)
        assertEquals("cn", routeCn?.region)

        val routeGlobal = engine.resolveRoute("腾讯国外/claude-3-5-sonnet")
        assertEquals("codebuddy", routeGlobal?.providerId)
        assertEquals("claude-3-5-sonnet", routeGlobal?.model)
        assertEquals("global", routeGlobal?.region)

        // 裸中文品牌名（腾讯 / 字节）不再识别：区域型供应商只认带区域的写法，
        // 所以「腾讯/deepseek-v3」会整串当模型名处理，不应该解析出 codebuddy 前缀
        val bare = engine.resolveRoute("腾讯/deepseek-v3")
        assertTrue(
            bare == null || bare.model != "deepseek-v3" || bare.providerId != "codebuddy",
            "裸品牌前缀不应再被识别：$bare",
        )

        // 模型列表里针对区域型供应商拆出两个独立前缀组（每个区域各用本区域账号拉一次）
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
    fun `没标签时一律不路由`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(SplitRegionProvider("codebuddy"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u-cn", "国内号", "{}"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u-global", "国外号", "{}"))

        // 新规则：不存在「无标签」这回事。哪怕目录里真能查到，也不给路由。
        assertTrue(engine.resolveRoute("global-only-model") == null, "无标签不得路由")
        assertTrue(engine.resolveRoute("cn-only-model") == null, "无标签不得路由")

        // 写了标签才走，且区域由标签决定
        val viaGlobal = engine.resolveRoute("腾讯国外/global-only-model")
        assertEquals("codebuddy", viaGlobal?.providerId)
        assertEquals("global", viaGlobal?.region)
        val viaCn = engine.resolveRoute("腾讯国内/cn-only-model")
        assertEquals("cn", viaCn?.region)
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
        // 每个供应商都要有账号，否则标签会被判无效（未登录的标签直接作废）
        listOf("codebuddy", "trae", "antigravity", "loomy", "raccoon").forEach {
            engine.pool.upsert(ProviderAccount(it, "u-$it", "$it 号", "{}"))
        }

        // 中文前缀：区域型供应商必须带区域后缀
        assertEquals("codebuddy", engine.resolveRoute("腾讯国内/glm-5.2")?.providerId)
        assertEquals("codebuddy", engine.resolveRoute("腾讯国外/glm-5.2")?.providerId)
        assertEquals("trae", engine.resolveRoute("字节国内/qwen-max")?.providerId)
        assertEquals("trae", engine.resolveRoute("字节国外/qwen-max")?.providerId)
        assertEquals("antigravity", engine.resolveRoute("谷歌/gemini-3.8")?.providerId)
        assertEquals("loomy", engine.resolveRoute("讯飞/spark-4")?.providerId)
        assertEquals("raccoon", engine.resolveRoute("商汤/raccoon-chat")?.providerId)
        assertEquals("glm-5.2", engine.resolveRoute("腾讯国内/glm-5.2")?.model)

        // 裸品牌前缀（腾讯 / 字节）已删除：区域标签本身就带国内/国外，裸品牌无意义
        listOf("腾讯/glm-5.2", "字节/qwen-max").forEach { bare ->
            val r = engine.resolveRoute(bare)
            assertTrue(
                r == null || r.model != bare.substringAfter('/'),
                "裸品牌前缀不应再被识别：$bare -> $r",
            )
        }

        // 小浣熊已全面改名为商汤，旧前缀不再识别
        val oldRaccoon = engine.resolveRoute("小浣熊/raccoon-chat")
        assertTrue(
            oldRaccoon == null || oldRaccoon.model != "raccoon-chat" || oldRaccoon.providerId != "raccoon",
            "小浣熊前缀应已删除：$oldRaccoon",
        )

        // 区域型供应商只保留「国内 / 国外」两种写法，『中国 / 国际』别名已删除。
        // 简单的问题不复杂化：两套同义写法只会让人以为有区别。
        listOf("腾讯中国/glm-5.2", "腾讯国际/glm-5.2", "字节中国/qwen-max", "字节国际/qwen-max").forEach { alias ->
            val r = engine.resolveRoute(alias)
            assertTrue(
                r == null || r.model != alias.substringAfter('/'),
                "中国/国际 别名应已删除：$alias -> $r",
            )
        }

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
    fun `选了哪家就只去哪家不再根据目录归属跨家`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(CatalogProvider("codebuddy", listOf("deepseek-v3", "glm-5.2")))
        engine.registry.register(CatalogProvider("trae", listOf("qwen-max", "seed-1.6")))
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "腾讯号", "{}"))
        engine.pool.upsert(ProviderAccount("trae", "t1", "字节号", "{}"))

        // 写了腾讯标签，就一定去腾讯——哪怕这个模型其实在字节目录里
        assertEquals("codebuddy", engine.resolveRoute("腾讯国内/qwen-max")?.providerId)
        // 反之亦然
        assertEquals("trae", engine.resolveRoute("字节国内/glm-5.2")?.providerId)
        // 无标签则一律不路由
        assertTrue(engine.resolveRoute("qwen-max") == null, "无标签不得路由")
        assertTrue(engine.resolveRoute("deepseek-v3") == null, "无标签不得路由")
    }

    @Test
    fun `部分账号停用时自动用同供应商其它账号`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("codebuddy"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "号1", "{}"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u2", "号2", "{}"))

        // 停用其中一个：选号必须落到另一个，而不是返回 null
        engine.pool.setEnabled("codebuddy", "u1", false, "手动停用")
        val picked = engine.pickAccount("codebuddy")
        assertNotNull(picked)
        assertEquals("u2", picked.uid)

        // 可用性快照要能区分「手动停用」
        val avail = engine.accountAvailability("codebuddy", null)
        assertEquals(2, avail.total)
        assertEquals(1, avail.usable)
        assertEquals(1, avail.offByUser)
        assertEquals(0, avail.deadCredential)
        assertTrue(!avail.allDisabled)
    }

    @Test
    fun `同供应商账号全部停用时选不出账号`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("codebuddy"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "号1", "{}"))
        engine.pool.upsert(ProviderAccount("codebuddy", "u2", "号2", "{}"))
        engine.pool.setEnabled("codebuddy", "u1", false, "手动停用")
        engine.pool.setEnabled("codebuddy", "u2", false, "手动停用")

        assertTrue(engine.pickAccount("codebuddy") == null, "全停用时不应选出账号")

        // 全停用 → allDisabled，转发层据此发横幅
        val avail = engine.accountAvailability("codebuddy", null)
        assertTrue(avail.allDisabled, "应判定为全部不可用")
        assertEquals(2, avail.offByUser)
        assertEquals(0, avail.deadCredential)

        // 路由本身仍然成立（标签有效、账号条目还在），只是发不出去——
        // 所以“通知”是必要的，否则用户只看到一条 503。
        assertNotNull(engine.resolveRoute("腾讯国内/glm-5.2"))
    }

    @Test
    fun `凭证失效与手动停用要分开统计`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("codebuddy"))
        engine.pool.upsert(ProviderAccount("codebuddy", "dead", "失效号", "{}"))
        engine.pool.upsert(ProviderAccount("codebuddy", "off", "停用号", "{}"))
        engine.pool.disable("codebuddy", "dead", "凭证已失效")
        engine.pool.setEnabled("codebuddy", "off", false, "手动停用")

        val avail = engine.accountAvailability("codebuddy", null)
        assertEquals(1, avail.deadCredential, "应识别出 1 个凭证失效")
        assertEquals(1, avail.offByUser, "应识别出 1 个手动停用")
        assertEquals(0, avail.usable)
        assertTrue(avail.allDisabled)
    }

    @Test
    fun `标签指向的供应商没登录或不存在时直接作废`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(CatalogProvider("codebuddy", listOf("deepseek-v3")))
        // 注意：故意不给 codebuddy 加账号

        // 有标签但供应商没登录 → 作废（不失效到别家）
        assertTrue(engine.resolveRoute("腾讯国内/deepseek-v3") == null, "没登录的标签应作废")

        // 标签压根不存在 → 作废
        assertTrue(engine.resolveRoute("火星/deepseek-v3") == null, "不存在的标签应作废")
    }

    @Test
    fun `显式前缀优先于目录归属`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(CatalogProvider("codebuddy", listOf("deepseek-v3")))
        engine.registry.register(CatalogProvider("trae", listOf("deepseek-v3")))
        engine.pool.upsert(ProviderAccount("codebuddy", "u1", "腾讯号", "{}"))
        engine.pool.upsert(ProviderAccount("trae", "t1", "字节号", "{}"))

        // 两家都有 deepseek-v3，写了标签就必须按标签走
        assertEquals("trae", engine.resolveRoute("字节国内/deepseek-v3")?.providerId)
        assertEquals("codebuddy", engine.resolveRoute("腾讯国内/deepseek-v3")?.providerId)
        assertEquals("trae", engine.resolveRoute("字节国外/deepseek-v3")?.providerId)
        assertEquals("codebuddy", engine.resolveRoute("腾讯国外/deepseek-v3")?.providerId)
    }

    @Test
    fun `无标签时不再根据模型名猜供应商`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("trae"))
        engine.registry.register(DummyProvider("antigravity"))
        // 反重力有账号，trae 没有
        engine.pool.upsert(ProviderAccount("antigravity", "u1", "谷歌账号", "{}"))

        // 名称特征兜底已彻底删除（主人要求「选了哪家就路由到哪家」）。
        // gemini / claude 这种看上去“一眼就能认出属于谷歌”的模型，
        // 只要没写标签、且目录里查不到，就不应该被猜着路由。
        assertTrue(
            engine.resolveRoute("gemini-3.8-flash-high") == null,
            "无标签时不得按模型名猜供应商",
        )
        assertTrue(
            engine.resolveRoute("claude-opus-4-6-thinking") == null,
            "无标签时不得按模型名猜供应商",
        )

        // 写了标签才路由
        assertEquals("antigravity", engine.resolveRoute("谷歌/gemini-3.8-flash-high")?.providerId)
    }

    @Test
    fun `无归属的模型名不再被单供应商或默认供应商兜底`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(DummyProvider("trae"))
        engine.registry.register(DummyProvider("antigravity"))
        engine.registry.register(DummyProvider("codebuddy"))
        // 只有 antigravity 有账号
        engine.pool.upsert(ProviderAccount("antigravity", "u1", "唯一账号", "{}"))

        // 旧的「单供应商接管」兜底已删除：auto / 随意模型名 不得被硬塞给任何一家。
        // 注意 gemini/claude 这类有专属品牌特征的模型仍然会路由（那是名称特征，不是兜底）。
        assertTrue(engine.resolveRoute("auto") == null, "不得再单供应商接管")
        assertTrue(engine.resolveRoute("some-random-model") == null, "不得再兜底猜供应商")
    }

    @Test
    fun `标签只绑定自己的供应商不再自动跨家找同款模型`() {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        // 两家都提供同名模型且都有账号 —— 正是原先「自动跨家找同款」会产生候选的条件
        engine.registry.register(CatalogProvider("trae", listOf("gemini-2.5-flash")))
        engine.registry.register(CatalogProvider("antigravity", listOf("gemini-2.5-flash")))
        engine.pool.upsert(ProviderAccount("trae", "t1", "trae 账号", "{}"))
        engine.pool.upsert(ProviderAccount("antigravity", "a1", "反重力账号", "{}"))

        // 写了「字节国内」标签，就永远只可能去字节——
        // 即便谷歌目录里也有同名模型，也不得自动改发谷歌。
        val candidates = engine.resolveCandidateRoutes("字节国内/gemini-2.5-flash")
        assertEquals(1, candidates.size, "标签不得产生跨供应商候选：$candidates")
        assertEquals("trae", candidates.single().providerId)
        assertEquals("cn", candidates.single().region, "区域约束应保留")
    }
}
