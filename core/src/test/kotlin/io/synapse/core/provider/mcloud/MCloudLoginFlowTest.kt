package io.synapse.core.provider.mcloud

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 整条登录链路的端到端回归，跑在本地假上游上。
 *
 * 覆盖的是**当前真实的实现路径**：
 * ```
 * App 端国密接口（user-njs）  发码 + 登录换 Basic
 *          ↓
 * 会员中心（vip.yun）        apikey/get 换长期 Key
 * ```
 * 早期版本走会员中心自己的 `getDyncPasswd` 发码，那个接口上游已废弃（恒 500），
 * 这组用例就是为了防止有人再改回去。
 */
class MCloudLoginFlowTest {

    private lateinit var server: HttpServer
    private val requests = ConcurrentLinkedQueue<Triple<String, String, String>>() // path, body, auth

    private var smsCodeOk = true

    private val appPubKey =
        "0438e805f1bbef2bb4d46dd4d514cce4c2df25820311a2edb4d891d0e98c3b524e3a89c724a7e5cf4a2d697383db405fa" +
            "f7a83bbc258afe1da8f9edff1feec3e1a"

    private var apiKeyBody = """
        {"success":true,"code":"0000","message":"请求成功","data":{
          "apikey":"test-key-123",
          "modelUrl":"http://127.0.0.1:PORT/api/llm/102001/v1/chat/completions",
          "modelList":[
            {"modelCode":"blian_qwen38_flash","modelName":"qwen3.8-Flash","tokenRatio":"0.18"},
            {"modelCode":"blian_qwen38_max","modelName":"qwen3.8-Max","tokenRatio":"1.5"}
          ]}}
    """.trimIndent()

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val port = { server.address.port }

        // App 端：加密公钥
        context(MCloudConstants.PATH_PUBLIC_KEY) {
            """{"success":true,"code":"0000","data":{"pubKey":"$appPubKey","pubIndex":"20_5"}}"""
        }
        // App 端：发码
        context(MCloudConstants.PATH_SEND_SMS) {
            if (smsCodeOk) """{"success":true,"code":"0000","data":{"msgid":"msg-1"}}"""
            else """{"success":false,"code":"01000010","message":"发送太频繁"}"""
        }
        // App 端：验证码登录（真实形态：data 为 thirdlogin 内层密文）
        context(MCloudConstants.PATH_THIRD_LOGIN) {
            if (smsCodeOk) {
                val inner = """{"authToken":"bW9iaWxlOjEzODAwMTM4MDAwOnRva2Vu","userDomainId":"1039","nickName":"测试"}"""
                val hex = MCloudCrypto.encryptThirdLogin(inner.toByteArray(Charsets.UTF_8))
                """{"success":true,"code":"0000","message":"请求成功","data":"$hex"}"""
            } else {
                """{"success":false,"code":"9441","message":"验证码不正确","data":null}"""
            }
        }
        // 会员中心：换长期 Key
        context(MCloudConstants.PATH_APIKEY_GET) { apiKeyBody.replace("PORT", port().toString()) }
        context("/api/llm/102001/v1/chat/completions") {
            """{"id":"1","choices":[{"index":0,"message":{"role":"assistant","content":"ok"}}]}"""
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.stop(0) }
    }

    private fun context(path: String, respond: () -> String) {
        server.createContext(path) { ex: HttpExchange ->
            val body = ex.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            requests.add(Triple(path, body, ex.requestHeaders.getFirst("Authorization").orEmpty()))
            val bytes = respond().toByteArray(Charsets.UTF_8)
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
    }

    private fun provider() = MCloudProvider(
        nowMillis = { 1_000_000L },
        authClient = MCloudAuthClient(vipBase = "http://127.0.0.1:${server.address.port}"),
        keyClient = MCloudKeyClient(aiBase = "http://127.0.0.1:${server.address.port}"),
        appClient = MCloudAppClient(
            userBase = "http://127.0.0.1:${server.address.port}",
            orchesBase = "http://127.0.0.1:${server.address.port}",
        ),
    )

    @Test
    fun `完整登录链路产出可用账号`() {
        val p = provider()
        // 先发码（真实流程就是这样两步），再登录
        val msgId = p.sendSmsCode("13800138000")
        assertEquals("msg-1", msgId)
        val account = p.loginBySmsCode("13800138000", "123456", msgId, "86")

        assertEquals("mcloud", account.providerId)
        assertEquals("1039", account.uid)
        assertEquals("测试", account.nickname, "内层 nickName 应被透出")

        val parsed = MCloudAccount.parse(account.secret)!!
        assertEquals("test-key-123", parsed.apiKey)
        assertEquals(2, parsed.models.size)
        assertEquals("bW9iaWxlOjEzODAwMTM4MDAwOmJXOWlhV3hsT2pFek9EQXdNVE00TURBd09uUnZhMlZ1", parsed.basicCredential)

        // 换 Key 必须带 Basic 头
        val got = requests.joinToString("\n") { it.first }
        val keyReq = requests.firstOrNull { it.first == MCloudConstants.PATH_APIKEY_GET }
            ?: error("没收到 apikey/get 请求，实际收到：\n$got")
        assertTrue(keyReq.third.startsWith("Basic "), "实际：${keyReq.third}")

        // App 端请求体必须是密文，不能出现明文手机号/验证码
        val smsReq = requests.firstOrNull { it.first == MCloudConstants.PATH_SEND_SMS }
            ?: error("没收到发码请求，实际：\n" + requests.joinToString("\n") { it.first })
        assertFalse(smsReq.second.contains("13800138000"), "手机号不能明文出现")
        // Hook 实测：发码走 AESUtil.encryptIV(KEY_BIZ) = Base64(IV‖AES-192-CBC)，不是 hex
        val smsPlain = MCloudCrypto.decryptAesIv(smsReq.second, MCloudConstants.KEY_BIZ)
        assertTrue(smsPlain.contains("\"phoneNumber\":\"13800138000\""), "发码 body 必须能用 KEY_BIZ 解回明文：$smsPlain")
        val loginReq = requests.first { it.first == MCloudConstants.PATH_THIRD_LOGIN }
        assertFalse(loginReq.second.contains("123456"), "验证码不能明文出现")
        // Hook 实测：thirdlogin 是唯一走 hex 的（AES-128-ECB-rlAesKey + 旋转）
        assertTrue(loginReq.second.all { it in "0123456789abcdef" }, "thirdlogin body 必须是全小写 hex：${loginReq.second.take(60)}")
        val loginPlain = MCloudCrypto.decryptThirdLoginInner(loginReq.second)
        assertTrue(loginPlain != null && loginPlain.contains("\"msisdn\":\"13800138000\""), "thirdlogin body 必须能用 rlAesKey 解回明文：$loginPlain")
    }

    @Test
    fun `验证码错误时把上游中文文案透出来`() {
        smsCodeOk = false
        val e = runCatching {
            provider().loginBySmsCode("13800138000", "000000", "", "86")
        }.exceptionOrNull()
        assertTrue(e is MCloudApiException, "实际：$e")
        assertEquals("验证码不正确", e!!.message)
    }

    @Test
    fun `发送验证码失败时给出上游原因`() {
        smsCodeOk = false
        val e = runCatching { provider().sendSmsCode("13800138000") }.exceptionOrNull()
        assertEquals("发送太频繁", e?.message)
    }

    @Test
    fun `非大陆号码在本地就拦掉 不浪费一次上游请求`() {
        val before = requests.size
        val e = runCatching { provider().sendSmsCode("12345") }.exceptionOrNull()
        assertTrue(e is IllegalStateException)
        assertEquals(before, requests.size, "不该打上游")
    }

    @Test
    fun `模型清单从落盘的账号里读 不打上游`() {
        val account = provider().loginBySmsCode("13800138000", "123456", "", "86")
        val view = provider().listModels(account)
        assertEquals(2, view.models.size)
        assertEquals("blian_qwen38_flash", view.models[0].id)
        assertEquals("qwen3.8-Flash", view.models[0].name)
        assertEquals("0.18×", view.models[0].extra["rate"])
        assertEquals("1.5×", view.models[1].extra["rate"])
    }

    @Test
    fun `没有账号时列出空目录并说明原因`() {
        val view = provider().listModels(null)
        assertTrue(view.models.isEmpty())
        assertEquals("还没有登录账号", view.error)
    }

    @Test
    fun `不带账号时只剥标签前缀 不做猜测`() {
        val p = provider()
        assertEquals("qwen3.8-Flash", p.resolveModel("移动云盘/qwen3.8-Flash"))
        assertEquals("blian_qwen38_max", p.resolveModel("blian_qwen38_max"))
        assertEquals("", p.resolveModel("auto"))
    }

    @Test
    fun `用户填展示名时 实际发给上游的是模型编码`() {
        val account = provider().loginBySmsCode("13800138000", "123456", "", "86")
        val call = provider().openChat(
            account,
            """{"model":"qwen3.8-Flash","messages":[{"role":"user","content":"hi"}]}""",
        )
        call.close()
        val chatReq = requests.first { it.first == "/api/llm/102001/v1/chat/completions" }
        assertTrue(
            chatReq.second.contains("\"model\":\"blian_qwen38_flash\""),
            "实际请求体：${chatReq.second}",
        )
    }

    @Test
    fun `认不出的模型名原样透传 不静默改成别的模型`() {
        val account = provider().loginBySmsCode("13800138000", "123456", "", "86")
        val call = provider().openChat(
            account,
            """{"model":"gpt-5-unknown","messages":[{"role":"user","content":"hi"}]}""",
        )
        call.close()
        val chatReq = requests.first { it.first == "/api/llm/102001/v1/chat/completions" }
        assertTrue(chatReq.second.contains("gpt-5-unknown"), "实际：${chatReq.second}")
    }
}
