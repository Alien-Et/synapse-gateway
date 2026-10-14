package dev.aigw.core.provider.trae

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.ProviderAccount
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 区域推断：凭证里的 domain 既可能是区域 id（cn/global），也可能是主机名（trae.cn / www.trae.ai），
 * 缺字段时还会被 TraeAccount.parse 填成 trae.cn 默认值。因此 apiHost 必须参与判定——
 * 只认 domain 会把 apiHost 已是 api.trae.ai 的国际版凭证判成国内版，
 * 它的模型目录、对话与额度就都会打到国内 host。
 */
class TraeRegionTest {

    @Test
    fun `区域 id 优先，主机名参与兜底`() {
        assertEquals(TraeRegion.INTL, TraeRegion.infer("global", "https://api.trae.cn"))
        assertEquals(TraeRegion.CN, TraeRegion.infer("cn", "https://api.trae.ai"))
        assertEquals(TraeRegion.INTL, TraeRegion.infer("www.trae.ai", "https://api.trae.ai"))
        assertEquals(TraeRegion.CN, TraeRegion.infer("trae.cn", "https://api.trae.com.cn"))
        assertEquals(TraeRegion.INTL, TraeRegion.infer(null, "https://api.trae.ai"))
        assertEquals(TraeRegion.CN, TraeRegion.infer(null, null))
    }

    @Test
    fun `缺 domain 的凭证按 apiHost 判区域`() {
        val provider = TraeProvider(
            store = InMemoryKeyValueStore(),
            version = { TraeVersion() },
            defaultModel = { "glm-5.2" },
        )

        assertEquals(TraeRegion.INTL.id, provider.regionOf(accountWith(apiHost = "https://api.trae.ai")))
        assertEquals(TraeRegion.CN.id, provider.regionOf(accountWith(apiHost = "https://api.trae.com.cn")))
    }

    private fun accountWith(apiHost: String): ProviderAccount {
        val secret = """
            {"auth":{"accessToken":"at","refreshToken":"rt","expiresAt":0,"apiHost":"$apiHost",
             "machineId":"${"m".repeat(64)}","deviceId":"1700000000000000"},
             "account":{"uid":"u1","enterpriseId":"","nickname":"昵称"}}
        """.trimIndent()
        return ProviderAccount(TraeProvider.ID, "u1", "昵称", secret)
    }
}
