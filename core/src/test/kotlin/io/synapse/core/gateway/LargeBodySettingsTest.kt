package io.synapse.core.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「大请求体」开关与并发闸门的纯逻辑测试。
 *
 * 覆盖三件容易写错的事：
 * 1. 开关关闭时必须回到 8MB，**不看**用户填的上限值（否则就是「关了没关干净」）；
 * 2. 上限 / 并发数读写时都要夹回合法区间（设置是从 JSON 读的，可能被手改坏）；
 * 3. 并发池满时 tryAcquire 必须立刻失败而不是排队。
 */
class LargeBodySettingsTest {

    @Test
    fun `默认关闭且沿用 8MB 上限`() {
        val settings = GatewaySettings()
        assertFalse(settings.largeBodyEnabled, "默认必须关闭，不能悄悄放开")
        assertEquals(8 * 1024 * 1024, settings.effectiveBodyLimitBytes())
    }

    @Test
    fun `开关关闭时忽略用户填的上限`() {
        // 用户先调成 256MB 再关开关：期望回到 8MB，而不是「关了但没完全关」
        val settings = GatewaySettings(largeBodyEnabled = false, maxBodyMb = 256)
        assertEquals(8 * 1024 * 1024, settings.effectiveBodyLimitBytes())
    }

    @Test
    fun `开关打开后按设置生效`() {
        val settings = GatewaySettings(largeBodyEnabled = true, maxBodyMb = 32)
        assertEquals(32 * 1024 * 1024, settings.effectiveBodyLimitBytes())
    }

    @Test
    fun `上限被夹回合法区间`() {
        assertEquals(
            GatewaySettings.MIN_MAX_BODY_MB,
            GatewaySettings.clampMaxBodyMb(0),
        )
        assertEquals(
            GatewaySettings.MAX_MAX_BODY_MB,
            GatewaySettings.clampMaxBodyMb(99999),
        )
        assertEquals(32, GatewaySettings.clampMaxBodyMb(32))
    }

    @Test
    fun `并发数被夹回合法区间`() {
        assertEquals(1, GatewaySettings.clampMaxBodyConcurrency(0))
        assertEquals(8, GatewaySettings.clampMaxBodyConcurrency(100))
        assertEquals(2, GatewaySettings.clampMaxBodyConcurrency(2))
    }

    @Test
    fun `JSON 往返保留三项新配置`() {
        val original = GatewaySettings(
            largeBodyEnabled = true,
            maxBodyMb = 64,
            maxBodyConcurrency = 3,
        )
        val restored = GatewaySettings.fromJson(original.toJson().toString())
        assertTrue(restored.largeBodyEnabled)
        assertEquals(64, restored.maxBodyMb)
        assertEquals(3, restored.maxBodyConcurrency)
    }

    @Test
    fun `旧配置缺少新字段时落到默认值`() {
        // 老版本存下来的 JSON 里根本没有这三个键
        val legacy = """{"port":8790,"maxRotate":3}"""
        val restored = GatewaySettings.fromJson(legacy)
        assertFalse(restored.largeBodyEnabled)
        assertEquals(GatewaySettings.DEFAULT_MAX_BODY_MB, restored.maxBodyMb)
        assertEquals(GatewaySettings.DEFAULT_MAX_BODY_CONCURRENCY, restored.maxBodyConcurrency)
        assertEquals(8 * 1024 * 1024, restored.effectiveBodyLimitBytes())
    }

    @Test
    fun `手改坏的上限值读入时被夹住`() {
        val hacked = """{"largeBodyEnabled":true,"maxBodyMb":99999,"maxBodyConcurrency":0}"""
        val restored = GatewaySettings.fromJson(hacked)
        assertTrue(restored.largeBodyEnabled)
        assertEquals(GatewaySettings.MAX_MAX_BODY_MB, restored.maxBodyMb)
        assertEquals(GatewaySettings.MIN_MAX_BODY_CONCURRENCY, restored.maxBodyConcurrency)
    }

    @Test
    fun `并发池满时立即失败而不是排队`() {
        val slots = BodyReadSlots(GatewaySettings.MAX_MAX_BODY_CONCURRENCY)
        assertTrue(slots.tryAcquire(limit = 2))
        assertTrue(slots.tryAcquire(limit = 2))
        assertEquals(2, slots.current())
        assertFalse(slots.tryAcquire(limit = 2), "第 3 条必须立刻被拒，不能排队占内存")
        slots.release()
        assertTrue(slots.tryAcquire(limit = 2), "释放后应能再接一条")
    }

    @Test
    fun `并发数调小后立即收紧`() {
        val slots = BodyReadSlots(GatewaySettings.MAX_MAX_BODY_CONCURRENCY)
        repeat(4) { assertTrue(slots.tryAcquire(limit = 4)) }
        // 用户在设置里把并发从 4 改成 1：在途的 4 条不受影响，但新的立刻被拒
        assertFalse(slots.tryAcquire(limit = 1))
        repeat(4) { slots.release() }
        assertTrue(slots.tryAcquire(limit = 1))
        slots.release()
    }

    @Test
    fun `并发上限超过池容量时按池容量夹住`() {
        val slots = BodyReadSlots(GatewaySettings.MAX_MAX_BODY_CONCURRENCY)
        // 传入超过池容量的上限不应突破池的物理容量
        repeat(GatewaySettings.MAX_MAX_BODY_CONCURRENCY) {
            assertTrue(slots.tryAcquire(limit = 999))
        }
        assertFalse(slots.tryAcquire(limit = 999))
    }
}
