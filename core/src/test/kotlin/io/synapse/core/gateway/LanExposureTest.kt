package io.synapse.core.gateway

import io.synapse.core.InMemoryKeyValueStore
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 局域网暴露：绑定地址与地址展示。
 *
 * 守两个曾经同时存在的坑：
 * 1. 打开「同时监听局域网」后服务没重启，socket 仍只绑 127.0.0.1，别的设备连不进来；
 * 2. 局域网开关是关的，界面却照样把 192.168.x.x 的地址显示出来给人复制。
 */
class LanExposureTest {

    private val engine = GatewayEngine(
        store = InMemoryKeyValueStore(),
        lanAddressProvider = { listOf("192.168.1.42") },
    )

    @AfterTest
    fun tearDown() {
        engine.stop()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test
    fun `局域网关闭时不给出局域网地址`() {
        engine.updateSettings(engine.settings().copy(exposeLan = false, port = freePort()))
        assertTrue(engine.lanUrls().isEmpty(), "开关关着还显示局域网地址，用户复制过去必然连不通")
    }

    @Test
    fun `局域网开启时才给出局域网地址`() {
        engine.updateSettings(engine.settings().copy(exposeLan = true, port = freePort()))
        assertEquals(listOf("http://192.168.1.42:${engine.settings().port}/v1"), engine.lanUrls())
    }

    @Test
    fun `端口变更后实际监听的端口跟着换`() {
        val first = freePort()
        engine.updateSettings(engine.settings().copy(exposeLan = false, port = first))
        engine.start()
        assertTrue(canConnect(first), "首次启动应监听 $first")

        val second = freePort()
        engine.updateSettings(engine.settings().copy(port = second))

        assertEquals(second, engine.settings().port)
        assertTrue(canConnect(second), "改端口后应立即监听新端口 $second")
        assertTrue(!canConnect(first), "旧端口 $first 应已关闭")
    }

    @Test
    fun `服务未启动时改设置不会把网关拉起来`() {
        engine.updateSettings(engine.settings().copy(exposeLan = true, port = freePort()))
        assertTrue(!engine.isRunning(), "只是改个开关，不应该顺带启动服务")
    }

    @Test
    fun `新端口被占用时回滚配置并抛错`() {
        val occupied = freePort()
        ServerSocket(occupied).use {
            engine.updateSettings(engine.settings().copy(exposeLan = false, port = freePort()))
            engine.start()
            val before = engine.settings().port

            assertFailsWith<IllegalStateException> {
                engine.updateSettings(engine.settings().copy(port = occupied))
            }

            assertEquals(before, engine.settings().port, "端口启动失败时应回滚到原值")
            assertTrue(engine.isRunning(), "回滚后旧服务应继续运行")
            assertTrue(canConnect(before), "旧端口应仍在监听")
        }
    }

    private fun canConnect(port: Int): Boolean = runCatching {
        (URL("http://127.0.0.1:$port/healthz").openConnection() as HttpURLConnection).run {
            connectTimeout = 1500
            readTimeout = 1500
            requestMethod = "GET"
            try {
                responseCode == 200 && inputStream.bufferedReader().readText() == "ok"
            } finally {
                disconnect()
            }
        }
    }.getOrDefault(false)
}
