package io.synapse.core.gateway

import io.synapse.core.provider.ProviderModel
import io.synapse.core.provider.RoutedModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 模型目录变更对比。
 *
 * 这一组测试锁住的核心语义只有一条：**拉取失败不等于模型下架**。
 * 它对应项目里「未知 ≠ 零」的同一条纪律 —— 一次网络抖动不能让用户的模型凭空消失。
 */
class ModelCatalogChangesTest {

    private fun model(providerId: String, prefix: String, id: String) =
        RoutedModel(providerId, providerId, ProviderModel(id, id), routePrefix = prefix)

    @Test
    fun `首次加载不报变更`() {
        val current = listOf(model("cline", "Cline", "a"), model("cline", "Cline", "b"))
        val changes = ModelCatalogChanges.of(
            previous = emptyList(),
            current = current,
            refreshedProviders = setOf("cline"),
            firstLoad = true,
        )
        assertTrue(changes.isEmpty, "首次加载没有基准，不该报「新增 N 个」")
        assertEquals("", changes.summary())
    }

    @Test
    fun `新增与移除都被识别`() {
        val previous = listOf(
            model("cline", "Cline", "keep"),
            model("cline", "Cline", "gone"),
        )
        val current = listOf(
            model("cline", "Cline", "keep"),
            model("cline", "Cline", "fresh"),
        )
        val changes = ModelCatalogChanges.of(previous, current, refreshedProviders = setOf("cline"))
        assertEquals(listOf("Cline/fresh"), changes.added["cline"])
        assertEquals(listOf("Cline/gone"), changes.removed["cline"])
        assertEquals(2, changes.total)
        assertTrue(changes.summary().contains("新增 1"))
        assertTrue(changes.summary().contains("移除 1"))
    }

    @Test
    fun `拉取失败的供应商不参与比较`() {
        val previous = listOf(
            model("cline", "Cline", "a"),
            model("antigravity", "谷歌", "g1"),
            model("antigravity", "谷歌", "g2"),
        )
        // 本轮谷歌拉取失败 → 目录里没有它；但它在 refreshedProviders 里缺席，
        // 所以不能把 g1/g2 判成「已移除」。
        val current = listOf(model("cline", "Cline", "a"))
        val changes = ModelCatalogChanges.of(previous, current, refreshedProviders = setOf("cline"))
        assertTrue(changes.isEmpty, "没拉到的供应商不能被判成模型下架：$changes")
    }

    @Test
    fun `本轮一个都没拉到时不报变更`() {
        val previous = listOf(model("cline", "Cline", "a"))
        val changes = ModelCatalogChanges.of(previous, emptyList(), refreshedProviders = emptySet())
        assertTrue(changes.isEmpty)
        assertFalse(changes.summary().isNotEmpty())
    }

    @Test
    fun `区域型供应商按前缀分组比较`() {
        // 腾讯国内与腾讯国外共用 providerId，但 fullId 前缀不同，属于两组独立目录。
        val previous = listOf(
            model("codebuddy", "腾讯国内", "glm-5.2"),
            model("codebuddy", "腾讯国外", "glm-5.2"),
        )
        val current = listOf(
            model("codebuddy", "腾讯国内", "glm-5.2"),
        )
        val changes = ModelCatalogChanges.of(previous, current, refreshedProviders = setOf("codebuddy"))
        // 按 providerId 聚合，所以移除项里同时包含国外那条
        assertEquals(listOf("腾讯国外/glm-5.2"), changes.removed["codebuddy"])
        assertTrue(changes.added.isEmpty())
    }

    @Test
    fun `摘要里每家只报数量不铺开清单`() {
        val previous = (1..30).map { model("cline", "Cline", "old$it") }
        val current = (1..5).map { model("cline", "Cline", "new$it") }
        val changes = ModelCatalogChanges.of(previous, current, refreshedProviders = setOf("cline"))
        val summary = changes.summary()
        assertTrue(summary.contains("新增 5"), summary)
        assertTrue(summary.contains("移除 30"), summary)
        // 摘要只报数量：几十条模型名铺进日志会把有用的信息挤掉
        assertFalse(summary.contains("old1"), "摘要不该列出具体模型名：$summary")
    }

    @Test
    fun `fullId 相同但模型 id 不同也算不同模型`() {
        val previous = listOf(model("cline", "Cline", "x"))
        // 同名不同 id：fullId 不同，属于一增一减
        val current = listOf(model("cline", "Cline", "y"))
        val changes = ModelCatalogChanges.of(previous, current, refreshedProviders = setOf("cline"))
        assertEquals(listOf("Cline/y"), changes.added["cline"])
        assertEquals(listOf("Cline/x"), changes.removed["cline"])
    }
}
