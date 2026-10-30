package io.synapse.core.provider.antigravity

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 客户端版本解析。
 *
 * 上游按 `User-Agent` 里的版本决定可用模型，**低于 2.23.0 会被拒绝新模型**
 * （CLIProxyAPI 注释原话：Cloud Code rejects newer models for clients below 2.23.0）。
 * 版本写死会让账号随上游推进逐渐失效，因此改为从官方清单解析，失败时退回兜底值。
 */
class AntigravityVersionTest {

    private val minute = 60_000L
    private val hour = 60 * minute

    @AfterTest
    fun tearDown() {
        // 恢复真实回源，避免污染其它单测（本对象是单例）
        AntigravityVersion.resetForTest()
    }

    @Test
    fun `从官方清单解析 version 字段`() {
        val manifest = """
            version: 2.23.1
            files:
              - url: https://storage.googleapis.com/antigravity-public/antigravity-hub/2.23.1-x/darwin-arm/Antigravity.zip
                sha512: abc==
            path: Antigravity.zip
        """.trimIndent()
        assertEquals("2.23.1", AntigravityVersion.parseManifest(manifest))
    }

    @Test
    fun `清单缺少 version 行时返回 null 交给兜底`() {
        assertEquals(null, AntigravityVersion.parseManifest("files:\n  - url: x"))
        assertEquals(null, AntigravityVersion.parseManifest(""))
    }

    @Test
    fun `兜底版本不低于上游下限`() {
        // 低于 2.23.0 会拿不到新模型，兜底值必须 >= 该下限
        val fallback = AntigravityVersion.FALLBACK_VERSION
        assertEquals("2.23.0", fallback)
        assertTrue(fallback >= "2.23.0", fallback)
    }

    // ------------------------------------------------------- 缓存窗口

    @Test
    fun `成功拿到的版本缓存六小时`() {
        var calls = 0
        AntigravityVersion.resetForTest { calls++; "2.31.7" }
        assertEquals("2.31.7", AntigravityVersion.current(0L))
        assertEquals("2.31.7", AntigravityVersion.current(5 * hour))
        assertEquals(1, calls, "六小时内不应重复回源：$calls")
        assertEquals("2.31.7", AntigravityVersion.current(6 * hour + 1))
        assertEquals(2, calls, "超过六小时应重新回源")
    }

    @Test
    fun `拉取失败只退避五分钟而不是锁六小时`() {
        var calls = 0
        AntigravityVersion.resetForTest { calls++; null }
        assertEquals(AntigravityVersion.FALLBACK_VERSION, AntigravityVersion.current(0L))
        assertEquals(1, calls)
        // 退避窗口内不重试
        AntigravityVersion.current(2 * minute)
        assertEquals(1, calls, "五分钟内不应重试：$calls")
        // 超过五分钟必须重试——这里正是「一次抖动锁六小时」的回归点
        AntigravityVersion.current(6 * minute)
        assertEquals(2, calls, "失败只退避 5 分钟，之后要允许重试")
    }

    @Test
    fun `成功后拉取失败时保留已拿到的真实版本而不是退回兜底值`() {
        var version: String? = "2.40.0"
        AntigravityVersion.resetForTest { version }
        assertEquals("2.40.0", AntigravityVersion.current(0L))
        // 超过六小时后清单失效：应继续用 2.40.0，而不是退回 2.23.0
        version = null
        assertEquals("2.40.0", AntigravityVersion.current(7 * hour), "失败时应保留上次成功的版本")
    }
}
