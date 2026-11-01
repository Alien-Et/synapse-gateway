package io.synapse.core.provider.mcloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 账号序列化与续期窗口：凭证是这条路唯一的状态，写错一个字就会「登录了但用不了」。 */
class MCloudAccountTest {

    private fun sample() = MCloudAccount(
        uid = "1039762344857421899",
        phone = "13800138000",
        apiKey = "a7a330229929e019efc014404067ec40829311116b2b2be4af6200aeb4d16786",
        modelUrl = "https://ai.yun.139.com/api/llm/102001/v1/chat/completions",
        basicCredential = "bW9iaWxlOjEzODAwMTM4MDAwOnRva2Vu",
        nickname = "",
        identity = "白银会员",
        models = listOf(
            MCloudModelInfo(code = "blian_qwen38_flash", name = "qwen3.8-Flash", tokenRatio = "0.18"),
            MCloudModelInfo(code = "blian_deepseek_v4_pro", name = "DeepSeek-V4-Pro", tokenRatio = "2.5"),
        ),
        createdAtMillis = 1_000L,
        basicExpiresAtMillis = 2_000L,
    )

    @Test
    fun `序列化后能原样读回全部字段`() {
        val back = assertNotNull(MCloudAccount.parse(sample().toJson().toString()))
        assertEquals("1039762344857421899", back.uid)
        assertEquals("13800138000", back.phone)
        assertEquals("a7a330229929e019", back.apiKey.take(16))
        assertEquals("bW9iaWxlOjEzODAwMTM4MDAwOnRva2Vu", back.basicCredential)
        assertEquals("白银会员", back.identity)
        assertEquals(2, back.models.size)
        assertEquals("qwen3.8-Flash", back.models[0].name)
        assertEquals("0.18", back.models[0].tokenRatio)
        assertEquals(2_000L, back.basicExpiresAtMillis)
    }

    @Test
    fun `缺 modelUrl 时回落成渠道号拼好的默认地址`() {
        val obj = sample().toJson().apply { remove("modelUrl") }
        val back = MCloudAccount.fromJson(obj)
        assertEquals(
            "https://ai.yun.139.com/api/llm/102001/v1/chat/completions",
            back.modelUrl,
        )
    }

    @Test
    fun `没有 Basic 凭证时不能自动续期`() {
        assertFalse(sample().copy(basicCredential = "").canAutoRefresh(nowMillis = 0L))
    }

    @Test
    fun `Basic 到期后不能自动续期`() {
        assertFalse(sample().canAutoRefresh(nowMillis = 2_001L))
        assertTrue(sample().canAutoRefresh(nowMillis = 1_999L))
    }

    @Test
    fun `withRefreshed 只改 Key 与过期时间 不碰身份信息`() {
        val refreshed = sample().withRefreshed("new-key", "", 9_999L)
        assertEquals("new-key", refreshed.apiKey)
        assertEquals("白银会员", refreshed.identity)
        assertEquals("13800138000", refreshed.phone)
        // 传空 modelUrl 时保留原值，别把可用地址清掉
        assertEquals(sample().modelUrl, refreshed.modelUrl)
        assertEquals(9_999L, refreshed.basicExpiresAtMillis)
    }

    @Test
    fun `脏 JSON 返回 null 而不是崩`() {
        assertEquals(null, MCloudAccount.parse("不是 JSON"))
        assertEquals(null, MCloudAccount.parse(""))
    }
}
