package dev.aigw.core.provider.loomy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 模型目录在「没有账号」时的行为。
 *
 * 登录前本就不该有模型，这是正常状态：返回空列表即可，不能当成失败报错
 * （否则模型页会顶一条红色「没有可用账号，无法拉取模型列表」吓人）。
 */
class LoomyModelCatalogTest {

    @Test
    fun `倍率从模型名称里解析出来`() {
        val catalog = LoomyModelCatalog()

        // 半角括号
        val a = catalog.parseRateFromName("DeepSeek V4.1 Flash(x2.0)")
        assertEquals("DeepSeek V4.1 Flash", a.cleanName)
        assertEquals(2.0, a.rate)
        assertEquals(null, a.tag)

        // 全角括号 + 空格
        val b = catalog.parseRateFromName("Kimi k2.6 （x6.5）")
        assertEquals("Kimi k2.6", b.cleanName)
        assertEquals(6.5, b.rate)

        // 限时免费文案要单独抽出来
        val c = catalog.parseRateFromName("Spark-X2.6 正式版(限时免费)(x0.0)")
        assertEquals("Spark-X2.6 正式版", c.cleanName)
        assertEquals(0.0, c.rate)
        assertEquals("限时免费", c.tag)

        // 全角「（限时免费）」
        val d = catalog.parseRateFromName("Spark-X2.5（限时免费）(x0.0)")
        assertEquals("Spark-X2.5", d.cleanName)
        assertEquals(0.0, d.rate)
        assertEquals("限时免费", d.tag)

        // 没有倍率后缀就原样返回，不猜
        val e = catalog.parseRateFromName("Hy image 3.5 preview")
        assertEquals("Hy image 3.5 preview", e.cleanName)
        assertEquals(null, e.rate)
        assertEquals(null, e.tag)
    }

    @Test
    fun `没有账号时返回空列表且不算失败`() {
        val catalog = LoomyModelCatalog()
        val models = catalog.models(null)
        assertTrue(models.isEmpty(), "无账号不该列出模型，实际=${models.map { it.id }}")
        assertFalse(catalog.fromFallback, "无账号是正常状态，不是回退")
        assertEquals("", catalog.lastError, "无账号不该报错")
    }

    @Test
    fun `空 session 与 null 行为一致`() {
        val catalog = LoomyModelCatalog()
        assertTrue(catalog.models("").isEmpty())
        assertFalse(catalog.fromFallback)
        assertEquals("", catalog.lastError)
    }
}