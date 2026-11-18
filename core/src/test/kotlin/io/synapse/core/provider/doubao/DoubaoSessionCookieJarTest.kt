package io.synapse.core.provider.doubao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cookie 罐的协议纪律单测。
 *
 * 这些断言锁的都是「和真机逐字对齐」的细节：Cookie 回带的**顺序**、**属性丢弃**、
 * **空值不覆盖**。任何一条被改坏，抓包对照就会对不上，而且不会报错 —— 只会静默变成
 * 「看起来带了 Cookie，其实内容不对」，所以必须用测试钉死。
 */
class DoubaoSessionCookieJarTest {

    @Test
    fun `merge 只取 name=value，丢弃 Path Domain Max-Age 属性`() {
        val jar = DoubaoSessionCookieJar()
        val changed = jar.merge(
            listOf(
                "odin_tt=abc123; Path=/; Domain=.doubao.com; Max-Age=15552000; HttpOnly",
                "passport_csrf_token=deadbeef; Path=/",
            ),
        )
        assertTrue(changed)
        assertEquals("odin_tt=abc123; passport_csrf_token=deadbeef", jar.header())
    }

    @Test
    fun `空值不覆盖已有值`() {
        val jar = DoubaoSessionCookieJar("odin_tt=abc")
        val changed = jar.merge(listOf("odin_tt=; Max-Age=0"))
        assertFalse(changed)
        assertEquals("odin_tt=abc", jar.header())
    }

    @Test
    fun `同值重复下发不算变化，不同值算变化`() {
        val jar = DoubaoSessionCookieJar("odin_tt=abc")
        assertFalse(jar.merge(listOf("odin_tt=abc; Path=/")))
        assertTrue(jar.merge(listOf("odin_tt=xyz; Path=/")))
        assertEquals("odin_tt=xyz", jar.header())
    }

    @Test
    fun `插入顺序稳定，重复 key 原位覆盖`() {
        val jar = DoubaoSessionCookieJar()
        jar.merge(listOf("a=1", "b=2", "c=3"))
        jar.merge(listOf("b=22"))
        assertEquals("a=1; b=22; c=3", jar.header())
    }

    @Test
    fun `load 解析一整串 Cookie，忽略非法片段`() {
        val jar = DoubaoSessionCookieJar(" ttwid=t1 ; garbage ; passport_csrf_token=c1 ")
        assertEquals("ttwid=t1; passport_csrf_token=c1", jar.header())
        assertEquals("t1", jar.get("ttwid"))
        assertEquals(2, jar.size)
    }

    @Test
    fun `变化时触发落盘回调，未变化不触发`() {
        val persisted = ArrayList<String>()
        val jar = DoubaoSessionCookieJar(persist = { persisted += it })
        jar.merge(listOf("odin_tt=abc"))
        jar.merge(listOf("odin_tt=abc"))
        assertEquals(listOf("odin_tt=abc"), persisted)
    }

    @Test
    fun `空罐返回空串，调用方据此省略整个 Cookie 头`() {
        val jar = DoubaoSessionCookieJar()
        assertTrue(jar.isEmpty())
        assertEquals("", jar.header())
    }
}