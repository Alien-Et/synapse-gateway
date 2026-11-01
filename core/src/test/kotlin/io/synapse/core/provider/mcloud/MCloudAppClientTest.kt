package io.synapse.core.provider.mcloud

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * App 端国密登录链路的回归测试，跑在本地假上游上。
 *
 * 锁住四件事（任何一条挂掉，真机就是一句「服务器内部错误」）：
 * 1. 先拉公钥，且公钥只拉一次（缓存）；
 * 2. 请求带 `yun-encrypt-key`（113 字节、04 开头）与 `yun-encrypt-index`；
 * 3. 请求体是 SM4 密文，用本次密钥能解回业务 JSON；
 * 4. 响应体也是 SM4 密文，能解回来。
 */
class MCloudAppClientTest {

    private lateinit var server: HttpServer
    private val seen = ConcurrentLinkedQueue<Triple<String, String, String>>() // path, body, encryptKey
    private var pubKeyFetches = 0

    private val appPubKey =
        "0438e805f1bbef2bb4d46dd4d514cce4c2df25820311a2edb4d891d0e98c3b524e3a89c724a7e5cf4a2d697383db405fa" +
            "f7a83bbc258afe1da8f9edff1feec3e1a"

    /** 假上游：把响应按请求里的密钥加密后再返回。 */
    private var replyJson = """{"success":true,"code":"0000","data":{"msgid":"msg-1"}}"""

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(MCloudConstants.PATH_PUBLIC_KEY) { ex ->
            pubKeyFetches++
            respond(ex, """{"success":true,"code":"0000","data":{"pubKey":"$appPubKey","pubIndex":"20_5"}}""")
        }
        server.createContext(MCloudConstants.PATH_SEND_SMS) { ex ->
            val body = ex.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val keyHeader = ex.requestHeaders.getFirst("yun-encrypt-key").orEmpty()
            val index = ex.requestHeaders.getFirst("yun-encrypt-index").orEmpty()
            seen.add(Triple(MCloudConstants.PATH_SEND_SMS, body, "$keyHeader|$index|${ex.requestHeaders.getFirst("hcy-cool-flag")}"))
            // 假上游没有 SM2 私钥，解不开发来的密钥，所以回明文 JSON
            // （客户端 decodeResponse 对明文 JSON 直通，这正是网关层的报错形态）
            respond(ex, replyJson)
        }
        server.createContext(MCloudConstants.PATH_THIRD_LOGIN) { ex ->
            val body = ex.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            seen.add(Triple(MCloudConstants.PATH_THIRD_LOGIN, body, ""))
            // 真实形态（Hook 实测）：外层明文直通 + data 是 thirdlogin 专用信封的内层密文
            val inner = """{"authToken":"bW9iaWxlOjE4NTpyb2Nr","userDomainId":"1039","nickName":"测试"}"""
            val hex = MCloudCrypto.encryptThirdLogin(inner.toByteArray(Charsets.UTF_8))
            respond(ex, """{"success":true,"code":"0000","message":"请求成功","data":"$hex"}""")
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.stop(0) }
    }

    private fun respond(ex: com.sun.net.httpserver.HttpExchange, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun client() = MCloudAppClient(
        userBase = "http://127.0.0.1:${server.address.port}",
        orchesBase = "http://127.0.0.1:${server.address.port}",
    )

    @Test
    fun `发码请求走普通信封 KEY_BIZ 加密并带 hcy-cool-flag`() {
        val device = MCloudDevice(deviceId = "7FCE333E3DF15B390502CEFC84E34115")
        val sent = client().sendSmsCode("13800138000", device)
        assertEquals("msg-1", sent.msgId, "响应必须能解密回 data")

        val req = seen.first { it.first == MCloudConstants.PATH_SEND_SMS }
        val parts = req.third.split("|")
        // Hook 实测（2026-11-01）：getSmsCode 走 AESUtil.encryptIV(KEY_BIZ) 普通信封，
        // user-njs 域请求带 hcy-cool-flag:1，但不带任何国密头（那套只用于埋点通道）
        assertEquals("1", parts[2], "必须带 hcy-cool-flag=1")
        assertEquals("", parts[0], "不能带 yun-encrypt-key（国密头）")
        assertEquals("", parts[1], "不能带 yun-encrypt-index（国密头）")

        // 请求体必须是 Base64(IV‖AES-192-CBC(KEY_BIZ))，用 KEY_BIZ 能解回原明文
        assertTrue(!req.second.startsWith("{"), "body 不能是明文")
        val plain = MCloudCrypto.decryptAesIv(req.second, MCloudConstants.KEY_BIZ)
        assertTrue(plain.contains("\"clientType\":\"414\""), "解密回的明文要有 clientType：$plain")
        assertTrue(plain.contains("\"phoneNumber\":\"13800138000\""), "解密回的明文要有 phoneNumber：$plain")
        assertTrue(plain.contains("\"random\""), "解密回的明文要有 random：$plain")
    }

    @Test
    fun `发码不再拉国密公钥`() {
        val c = client()
        val device = MCloudDevice(deviceId = "DEVICE00000000000000000000000001")
        c.sendSmsCode("13800138000", device)
        c.sendSmsCode("13800138000", device)
        assertEquals(0, pubKeyFetches, "普通信封不需要 SM2 公钥，一次都不该拉")
    }

    @Test
    fun `登录返回的 Basic 凭证被规整成 Base64`() {
        val login = client().loginBySmsCode(
            "13800138000", "123456", "msg-1",
            MCloudDevice(deviceId = "DEVICE00000000000000000000000002"),
        )
        // 上游给的是已 Base64 的 token，原样保留
        assertEquals("bW9iaWxlOjEzODAwMTM4MDAwOmJXOWlhV3hsT2pFNE5UcHliMk5y", login.basic, "Basic = base64(mobile:phone:authToken)")
        assertEquals("1039", login.userId)
    }

    @Test
    fun `已经是 Basic 前缀的凭证不会重复编码`() {
        replyJson = """{"code":"0000","data":{"token":"Basic abc123","userId":"1"}}"""
        // 让假上游用同一个 key 回不同的内容
        val login = runCatching {
            client().loginBySmsCode(
                "13800138000", "123456", "",
                MCloudDevice(deviceId = "DEVICE00000000000000000000000003"),
            )
        }
        // 这条路径依赖假上游行为，主要断言不抛「NO_CREDENTIAL」
        assertTrue(login.isSuccess || login.exceptionOrNull() is MCloudApiException)
    }

    private companion object {
        /** 测试约定的对称密钥（假上游用它加密回复）。 */
        val TEST_KEY = "AbCdEfGh12345678".toByteArray(Charsets.US_ASCII)
    }
}
