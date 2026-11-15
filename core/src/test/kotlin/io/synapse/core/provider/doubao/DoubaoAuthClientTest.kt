package io.synapse.core.provider.doubao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `mix_mode=1` 混淆、多账号选号判据、发码头纪律。
 *
 * 数据全部来自真实登录抓包（docs/17.6）与真机频控边界探测（§18.18），
 * 不是构造的 —— 锁死 XOR 0x05、sel token 双通道、csrf/ guard 头边界。
 */
class DoubaoAuthClientTest {

    @Test
    fun `XOR 编码与真实抓包一致`() {
        // 实测：+8613600000001 → 2e3d3334363d3030363030303031
        assertEquals(
            "2e3d3334363d3030363030303031",
            DoubaoAuthClient.xorEncode("+8613600000001"),
        )
        // 实测：验证码 259670 → 37303c333235
        assertEquals("37303c333235", DoubaoAuthClient.xorEncode("259670"))
        // 实测：type=24 → 3731
        assertEquals("3731", DoubaoAuthClient.xorEncode("24"))
    }

    @Test
    fun `XOR 解码是编码的逆`() {
        val plain = "+8613800138000"
        assertEquals(plain, DoubaoAuthClient.xorDecode(DoubaoAuthClient.xorEncode(plain)))
    }

    @Test
    fun `sms_code_key 实测样本可解码`() {
        // 实测请求体里的 sms_code_key（XOR 后的 hex）
        val decoded = DoubaoAuthClient.xorDecode(
            "643337316137353d61353c3130673564323c343c326464333c3d603737356334",
        )
        // 解出的是 32 位 hex 形态的 key（不是明文语义，但必须是可读 ASCII）
        assertTrue(decoded.matches(Regex("[0-9a-f<>=`]+")), "实际解出：$decoded")
    }

    // -------------------------------------------------------------- A：选号断链

    /**
     * 实测 UI 的传法：多账号对话框 `onSmsLogin(id, phone, selMsgid, ccode)`
     * 把 `sel:<sec_uid>` 放在**第 3 个参数 code** 上，而 msgid 里是发码时存的
     * encodedMobile（hex）。旧实现只判 msgid ⇒ 选号必失败。
     */
    @Test
    fun `sel token 在 code 通道也要能认出来`() {
        val encodedMobile = DoubaoAuthClient.xorEncode("+8613800138000")
        val sel = "sel:MS4wLjABAAAA_sec_uid"
        assertEquals(sel, DoubaoAuthClient.extractSelToken(encodedMobile, sel),
            "UI 把 sel 放在 code 上，必须认出来")
        assertEquals(sel, DoubaoAuthClient.extractSelToken(sel, "123456"),
            "标准 msgid 通道仍然优先")
        assertEquals(sel, DoubaoAuthClient.extractSelToken(sel, sel),
            "两个通道都带 sel 时不应报错")
        assertEquals("", DoubaoAuthClient.extractSelToken(encodedMobile, "259670"),
            "普通验证码登录不能被误判成选号")
    }

    // -------------------------------------------------------------- B：头纪律

    @Test
    fun `发码默认不带 csrf 也不带假 guard 头`() {
        val h = DoubaoAuthClient().buildLoginHeaders()
        // 真机埋点：request_csrf_token=0，csrf 是响应才下发的
        assertFalse(h.containsKey("x-tt-passport-csrf-token"), "csrf 不该出现在发码请求里")
        assertFalse(h.containsKey("X-SS-STUB"), "登录链路不需要 X-SS-STUB")
        assertEquals("3", h["bd-ticket-guard-version"], "只声明协议版本，不冒充六件套齐了")
        // 没真值就一个动态 guard 头都不发（写死过期值只会让裁决位更差）
        assertFalse(h.containsKey("bd-ticket-guard-client-cert"))
        assertFalse(h.containsKey("bd-ticket-guard-ree-public-key"))
        assertFalse(h.containsKey("bd-ticket-guard-static-sign"))
        assertFalse(h.containsKey("bd-ticket-guard-server-cert-sn"))
    }

    @Test
    fun `设备票据注入口能带真值且覆盖同名头`() {
        val injected = mapOf(
            "bd-ticket-guard-ree-public-key" to "REE_PUB",
            "bd-ticket-guard-client-cert" to "pub.CERT",
            "bd-ticket-guard-iteration-version" to "2",
            "bd-ticket-guard-version" to "3",
        )
        val h = DoubaoAuthClient(guardHeaders = DeviceGuardHeaders { injected }).buildLoginHeaders()
        assertEquals("REE_PUB", h["bd-ticket-guard-ree-public-key"])
        assertEquals("pub.CERT", h["bd-ticket-guard-client-cert"])
        assertEquals("2", h["bd-ticket-guard-iteration-version"])
        assertEquals("3", h["bd-ticket-guard-version"])
    }

    // -------------------------------------------------------------- 多账号序列化

    @Test
    fun `多账号异常消息能往返解析出全部候选`() {
        val subs = listOf(
            DoubaoAuthClient.SubAccount("uid-1", "阿飘", "", "", false),
            DoubaoAuthClient.SubAccount("uid-2", "小号|测试", "", "", false),
        )
        val hint = "sel:uid-1"
        val ex = DoubaoMultiAccountException(subs, "TICKET", "SMSKEY", hint)
        val parsed = DoubaoMultiAccountException.parse(ex.message)
        assertTrue(parsed != null, "应能解析：$parsed")
        val (backHint, backSubs) = parsed!!
        assertEquals(hint, backHint)
        assertEquals(2, backSubs.size, "候选不能丢：$backSubs")
        assertEquals("uid-1", backSubs[0].secUid)
        assertEquals("阿飘", backSubs[0].name)
        assertEquals("uid-2", backSubs[1].secUid)
        assertEquals("小号/测试", backSubs[1].name, "名称里的 | 必须被替换，否则格式会炸")
    }
}
