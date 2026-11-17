package io.synapse.core.gateway

import io.synapse.core.InMemoryKeyValueStore
import io.synapse.core.provider.AuthKind
import io.synapse.core.provider.ChatCall
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.FailedChatCall
import io.synapse.core.provider.Provider
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderModel
import io.synapse.core.provider.ProviderModelCatalogView
import io.synapse.core.provider.UpstreamError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 模型黑名单（屏蔽）语义：
 * - 屏蔽是「排除」，与开放名单（白名单）正交且优先；
 * - 批量屏蔽幂等，返回值只统计真正新增的条目；
 * - 被屏蔽的模型不对外暴露、也不能按标签路由；
 * - 落盘后新引擎仍保持屏蔽。
 */
class ModelBlocklistTest {

    private class FixedProvider(
        override val id: String,
        private val models: List<ProviderModel>,
    ) : Provider {
        override val displayName = id
        override val authKind = AuthKind.NONE
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = FailedChatCall(500, "{}")
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(models, false, "")
    }

    /** 注册供应商并塞一个账号：resolveRoute 要求「已启用且至少一个账号」才认标签。 */
    private fun engineWith(vararg providers: Provider): GatewayEngine {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        for (provider in providers) {
            engine.registry.register(provider)
            engine.pool.upsert(
                ProviderAccount(
                    providerId = provider.id,
                    uid = "uid-${provider.id}",
                    nickname = provider.id,
                    secret = "{}",
                ),
            )
        }
        return engine
    }

    private fun twoModels(): FixedProvider =
        FixedProvider("alpha", listOf(ProviderModel("m1", "m1"), ProviderModel("m2", "m2")))

    @Test
    fun `默认不屏蔽任何模型`() {
        val blocklist = ModelBlocklist()
        assertTrue(blocklist.blocked.isEmpty())
        assertFalse(blocklist.blocks("alpha/m1"))
    }

    @Test
    fun `with 与 without 幂等`() {
        val once = ModelBlocklist().with("alpha/m1")
        val twice = once.with("alpha/m1")
        assertEquals(once, twice)
        assertEquals(ModelBlocklist(), twice.without("alpha/m1"))
        // 取消一个本来就不在名单里的条目，结果不变
        assertEquals(twice, twice.without("alpha/m9"))
    }

    @Test
    fun `withAll 空集合不改动`() {
        val base = ModelBlocklist(setOf("a/x"))
        assertEquals(base, base.withAll(emptyList()))
        assertEquals(setOf("a/x", "b/y"), base.withAll(listOf("b/y")).blocked)
    }

    @Test
    fun `JSON 往返稳定且按键排序`() {
        val original = ModelBlocklist(setOf("b/y", "a/x"))
        val raw = original.toJson().toString()
        // 排序写盘：避免每次集合插入顺序不同导致无谓的写放大
        assertTrue(raw.indexOf("a/x") < raw.indexOf("b/y"), raw)
        assertEquals(original, ModelBlocklist.fromJson(raw))
    }

    @Test
    fun `非法或缺失输入回退为空名单`() {
        assertEquals(ModelBlocklist(), ModelBlocklist.fromJson("not-json"))
        assertEquals(ModelBlocklist(), ModelBlocklist.fromJson(""))
        assertEquals(ModelBlocklist(), ModelBlocklist.fromJson(null))
        assertEquals(ModelBlocklist(), ModelBlocklist.fromJson("{}"))
    }

    @Test
    fun `解析时忽略非字符串与空串`() {
        val parsed = ModelBlocklist.fromJson("""{"blocked":["a/1","",123,null,{"k":1},"a/2","a/1"]}""")
        assertEquals(listOf("a/1", "a/2"), parsed.blocked.toList())
    }

    @Test
    fun `批量屏蔽只统计真正新增的条目`() {
        val engine = engineWith(twoModels())
        assertEquals(2, engine.blockModels(listOf("alpha/m1", "alpha/m2")))
        // 重复屏蔽同一批：返回 0（曾经在替换字段之后再比较新旧大小，恒为 0，这里锁住回归）
        assertEquals(0, engine.blockModels(listOf("alpha/m1", "alpha/m2")))
        assertEquals(1, engine.blockModels(listOf("alpha/m1", "alpha/m3")))
        assertTrue(engine.isModelBlocked("alpha/m3"))
    }

    @Test
    fun `被屏蔽的模型不出现在对外目录里`() {
        val engine = engineWith(twoModels())
        engine.blockModels(listOf("alpha/m1"))
        assertEquals(
            listOf("alpha/m2"),
            engine.exposedModels().filter { it.providerId == "alpha" }.map { it.fullId },
        )
    }

    @Test
    fun `屏蔽优先于开放名单`() {
        val engine = engineWith(twoModels())
        // 两个都进白名单，再屏蔽其中一个：白名单里那个也不该对外暴露
        engine.updateModelExposure(
            ModelExposureSettings(filterEnabled = true, exposed = setOf("alpha/m1", "alpha/m2")),
        )
        engine.blockModels(listOf("alpha/m1"))
        assertEquals(
            listOf("alpha/m2"),
            engine.exposedModels().filter { it.providerId == "alpha" }.map { it.fullId },
        )
    }

    @Test
    fun `取消屏蔽后恢复对外可见`() {
        val engine = engineWith(twoModels())
        engine.blockModels(listOf("alpha/m1"))
        engine.unblockModel("alpha/m1")
        assertEquals(
            listOf("alpha/m1", "alpha/m2"),
            engine.exposedModels().filter { it.providerId == "alpha" }.map { it.fullId },
        )
    }

    @Test
    fun `被屏蔽的模型无法按标签路由`() {
        // 路由必须走「内置标签」：未知前缀（如 alpha）本就不是合法标签，
        // 拿来测屏蔽会让结论失真 —— 用讯飞（loomy → 标签「讯飞」，无区域）验证。
        val engine = engineWith(
            FixedProvider("loomy", listOf(ProviderModel("m1", "m1"), ProviderModel("m2", "m2"))),
        )
        assertNotNull(engine.resolveRoute("讯飞/m1"))
        engine.blockModels(listOf("讯飞/m1"))
        assertNull(engine.resolveRoute("讯飞/m1"))
        // 未被屏蔽的同家模型不受影响
        assertNotNull(engine.resolveRoute("讯飞/m2"))
    }

    @Test
    fun `屏蔽名单落盘后新引擎仍生效`() {
        val store = InMemoryKeyValueStore()
        val engine = GatewayEngine(store)
        engine.registry.register(twoModels())
        engine.blockModels(listOf("alpha/m1"))

        val reloaded = GatewayEngine(store)
        assertTrue(reloaded.modelBlocklist().blocks("alpha/m1"))
        assertFalse(reloaded.modelBlocklist().blocks("alpha/m2"))
    }
}
