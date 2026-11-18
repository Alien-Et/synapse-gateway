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
        assertFalse(h.containsKey("X-SS-STUB"), "STUB 依赖 body，由 sendSmsCode 另加，不在通用头里")
        assertEquals("3", h["bd-ticket-guard-version"], "只声明协议版本，不冒充六件套齐了")
        // 真机 send_code 带的确定性常量头（不含随机性）
        assertEquals("-1", h["x-ttnet-bypass-to"])
        assertEquals("2", h["sdk-version"])
        assertEquals("60594", h["passport-sdk-version"])
        assertEquals("x-tt-token,sec_user_id,device_transfer_s_0,device_transfer_ab_0", h["passport-sdk-settings"])
        // 业务请求的 request-tag 是 t=1;n=1（t=0;n=0 只给 verify_pub_key 用）
        assertEquals("t=1;n=1", h["x-tt-request-tag"])
        // passport-sdk-sign 是「实际带了哪些凭据」的清单，不是签名：
        // 匿名发码两样凭据都没有 ⇒ 必须整个头都不出现，不能照抄真机的 22 字符清单。
        assertFalse(h.containsKey("passport-sdk-sign"), "没带凭据就不该声明带了凭据")
        assertEquals("", DoubaoAuthClient().sdkSignValue(hasXttToken = false, hasSecUserId = false))
        assertEquals("x-tt-token", DoubaoAuthClient().sdkSignValue(hasXttToken = true, hasSecUserId = false))
        assertEquals("sec_user_id", DoubaoAuthClient().sdkSignValue(hasXttToken = false, hasSecUserId = true))
        assertEquals(
            "x-tt-token,sec_user_id",
            DoubaoAuthClient().sdkSignValue(hasXttToken = true, hasSecUserId = true),
            "两样都带时才等于真机抓包里的 22 字符",
        )
        assertEquals("application/x-www-form-urlencoded; charset=UTF-8", h["Content-Type"])
        // 没真值就一个动态 guard 头都不发（写死过期值只会让裁决位更差）
        assertFalse(h.containsKey("bd-ticket-guard-client-cert"))
        assertFalse(h.containsKey("bd-ticket-guard-ree-public-key"))
        assertFalse(h.containsKey("bd-ticket-guard-static-sign"))
        assertFalse(h.containsKey("bd-ticket-guard-server-cert-sn"))
    }

    @Test
    fun `记住响应下发的 csrf 后再次发码会带上`() {
        val client = DoubaoAuthClient()
        // 首次：没有 csrf，头里不能出现（服务端按新会话处理并下发 csrf）
        assertFalse(client.buildLoginHeaders().containsKey("x-tt-passport-csrf-token"))
        assertEquals("", client.currentCsrfToken())

        // 服务端 Set-Cookie（真机实测会同时下发 default 与不带后缀两种）
        client.rememberCsrfFrom(
            listOf(
                "passport_csrf_token=4b15654abff6b6ef10fdb1ec92a0f322; Path=/; Domain=.doubao.com; Secure",
                "passport_csrf_token_default=4b15654abff6b6ef10fdb1ec92a0f322; Path=/; Domain=.doubao.com",
                "reg-store-region=cn; Path=/",
            ),
        )
        assertEquals("4b15654abff6b6ef10fdb1ec92a0f322", client.currentCsrfToken())
        assertEquals(
            "4b15654abff6b6ef10fdb1ec92a0f322",
            client.buildLoginHeaders()["x-tt-passport-csrf-token"],
            "第二次请求必须复用服务端下发的 csrf",
        )
    }

    @Test
    fun `空 csrf 不覆盖已记住的值`() {
        val client = DoubaoAuthClient()
        client.rememberCsrfFrom(listOf("passport_csrf_token=abc123; Path=/"))
        // 个别响应会下发清空指令（同名空值），不能把已有状态打坏
        client.rememberCsrfFrom(listOf("passport_csrf_token=; Max-Age=0", "other=xyz"))
        assertEquals("abc123", client.currentCsrfToken())
        assertEquals("abc123", client.buildLoginHeaders()["x-tt-passport-csrf-token"])
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

    @Test
    fun `X-SS-STUB 等于 body 的 MD5 大写`() {
        // 真机 send_code 抓包：body(235B) → STUB 逐字节一致（不是随机值）
        val body = "terminal_type=3&app_name=nova_ai&auto_read=0&account_sdk_source=app&" +
            "passport_support_flow=captcha%2Cpassport_user_ban%2Cverify&unbind_exist=35&" +
            "mix_mode=1&os_type=4&mobile=2e3d3334363d3030363030303031&is6Digits=1&" +
            "multi_login=1&type=3731"
        assertEquals("D1E29E3416F5007C8002FAD64452DCA4", DoubaoAuthClient().md5Upper(body))
    }
}
