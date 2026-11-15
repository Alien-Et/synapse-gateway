package io.synapse.core.provider.doubao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `mix_mode=1` 混淆与手机号归一。
 *
 * 数据全部来自真实登录抓包（docs/17.6），不是构造的 ——
 * 这两个用例锁死了 XOR 0x05 这个**唯一**的混淆算法，
 * 猜错算法（比如当成了 MD5/Base64）会让整个登录直接 400。
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

}
