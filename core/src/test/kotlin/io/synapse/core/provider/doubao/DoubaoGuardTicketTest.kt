package io.synapse.core.provider.doubao

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 真机票据 → guard 头的形状约束。
 *
 * 长度不是随便定的：`client-cert` 必须 124（= base64("pub." + 88 字符裸点)）、
 * `ree-public-key` 必须 88（= base64(65 字节裸点)），这两个数与真机抓包逐字节一致，
 * 形状不对服务端会直接当异常头处理。
 */
class DoubaoGuardTicketTest {

    private val teePointB64 = Base64.getEncoder().encodeToString(ByteArray(65) { if (it == 0) 0x04 else 0x11 })
    private val reeHex = "04" + "ab".repeat(64)

    private fun json(
        point: String = teePointB64,
        ree: String = reeHex,
        sn: String = "533240336124694022040808462028007165443034493949",
        display: String = "PJE110_16.0.5.1301(CN01)",
    ) = """
        {
          "clientCertTeePoint": "$point",
          "reePublicHex": "$ree",
          "serverCertSn": "$sn",
          "displayOs": "$display"
        }
    """.trimIndent()

    @Test
    fun `裸点长度与真机一致`() {
        assertEquals(88, teePointB64.length)
        assertEquals(130, reeHex.length)
    }

    @Test
    fun `解析票据并装配 7 个头`() {
        val ticket = assertNotNull(DoubaoGuardTicket.parse(json()))
        val headers = DoubaoGuardTicket.headers(ticket)
        assertEquals(7, headers.size)
        // 与真机抓包的 124 / 88 逐项对齐
        assertEquals(124, headers["bd-ticket-guard-client-cert"]!!.length)
        assertEquals(88, headers["bd-ticket-guard-ree-public-key"]!!.length)
        assertEquals("1", headers["bd-ticket-guard-tee-status"])
        assertEquals("3", headers["bd-ticket-guard-version"])
        assertEquals("2", headers["bd-ticket-guard-iteration-version"])
        assertEquals("PJE110_16.0.5.1301(CN01)", headers["bd-ticket-guard-display-os-version"])
        assertEquals(
            "533240336124694022040808462028007165443034493949",
            headers["bd-ticket-guard-server-cert-sn"],
        )
        // client-cert 必须是 base64("pub." + 裸点)，不是证书、也不是 SPKI
        val inner = String(Base64.getDecoder().decode(headers["bd-ticket-guard-client-cert"]))
        assertEquals("pub.$teePointB64", inner)
    }

    @Test
    fun `缺关键字段视为无效票据`() {
        assertNull(DoubaoGuardTicket.parse(json(point = "")))
        assertNull(DoubaoGuardTicket.parse(json(ree = "")))
        assertNull(DoubaoGuardTicket.parse("not json"))
    }

    @Test
    fun `ree 公钥 hex 非法时降级为不发该头`() {
        val ticket = assertNotNull(DoubaoGuardTicket.parse(json(ree = "zz")))
        val headers = DoubaoGuardTicket.headers(ticket)
        assertFalse(headers.containsKey("bd-ticket-guard-ree-public-key"))
    }

    @Test
    fun `票据文件不存在时返回 null`() {
        assertNull(DoubaoGuardTicket.read(java.io.File("/definitely/not/here/doubao_guard.json")))
    }
}
