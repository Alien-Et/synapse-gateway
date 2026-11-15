package io.synapse.core.provider.doubao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 豆包凭证解析。
 *
 * 回归重点：用户是从抓包工具里**整串复制 Cookie**贴进来的，
 * 所以解析必须容错（分号/空格/换行不齐、带不带 `Cookie:` 前缀都要能认）。
 */
class DoubaoCredentialTest {

    private fun cookie(vararg pairs: Pair<String, String>) =
        pairs.joinToString("; ") { (k, v) -> "$k=$v" }

    @Test
    fun `原始 Cookie 串能解析出主凭证`() {
        val raw = cookie(
            "n_mh" to "abc",
            "d_ticket" to "eda83dcc5be28fea960fd19b0bfad678ffa80",
            "ttwid" to "1%7CWCt88ijbgdb",
            "__tea__ug__uid" to "8200331787316270197",
        )
        val c = assertNotNull(DoubaoCredential.parse(raw))
        assertEquals("eda83dcc5be28fea960fd19b0bfad678ffa80", c.cookieValue("d_ticket"))
        assertEquals("8200331787316270197", c.uid)
    }

    @Test
    fun `带 Cookie 前缀也能解析`() {
        val raw = "Cookie: d_ticket=t1; ttwid=w1; __tea__ug__uid=99"
        val c = assertNotNull(DoubaoCredential.parse(raw))
        assertEquals("t1", c.cookieValue("d_ticket"))
        assertEquals("99", c.uid)
    }

    @Test
    fun `多余空白与换行不影响解析`() {
        val raw = "  d_ticket = t2 ;\n ttwid = w2 ;  __tea__ug__uid = 77  "
        val c = assertNotNull(DoubaoCredential.parse(raw))
        assertEquals("t2", c.cookieValue("d_ticket"))
        assertEquals("77", c.uid)
    }

    @Test
    fun `JSON 形态同样接受`() {
        val json = """{"cookie":"d_ticket=t3; ttwid=w3","cdid":"cd-1","conversationId":"conv-9"}"""
        val c = assertNotNull(DoubaoCredential.parse(json))
        assertEquals("t3", c.cookieValue("d_ticket"))
        assertEquals("cd-1", c.cdid)
        assertEquals("conv-9", c.conversationId)
    }

    @Test
    fun `空串与无关键字段返回 null`() {
        assertNull(DoubaoCredential.parse(""))
        assertNull(DoubaoCredential.parse("   "))
        assertNull(DoubaoCredential.parse("foo=bar; baz=qux"))
    }

    @Test
    fun `toSecret 与 parse 往返一致`() {
        val raw = "d_ticket=t4; ttwid=w4; __tea__ug__uid=55"
        val c1 = assertNotNull(DoubaoCredential.parse(raw))
        val c2 = assertNotNull(DoubaoCredential.parse(c1.toSecret()))
        assertEquals(c1.cookieValue("d_ticket"), c2.cookieValue("d_ticket"))
        assertEquals(c1.uid, c2.uid)
    }

    @Test
    fun `conversationId 可改而 cookie 不变`() {
        val c = assertNotNull(DoubaoCredential.parse("d_ticket=t5; __tea__ug__uid=66"))
        val moved = c.copy(conversationId = "38453873148032002")
        assertEquals("t5", moved.cookieValue("d_ticket"))
        assertEquals("38453873148032002", moved.conversationId)
        assertEquals("", c.conversationId, "原对象不应被改动")
    }
}
