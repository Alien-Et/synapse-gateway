package io.synapse.core.provider.mcloud

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 灵犀额度的「残缺响应」回归。
 *
 * 权益接口偶发会回**残缺的成功响应**（HTTP 200 但 `userAvailableBenefitList` 为空或缺字段）。
 * 这类响应算出来的 total 是 0，若把它当成真实结果，界面会显示「额度 0」——
 * 用户会以为额度用完了，而真实剩余可能还有几百万。比显示「查询失败」更误导。
 *
 * 判据必须是「权益接口有没有真的给 benefitValue」（[MCloudCredit.balanceKnown]），
 * 而不是 `totalTokens > 0`：后者把「不知道」和「确实用光」混成了一种情况。
 */
class MCloudPartialCreditTest {

    private var benefitBody: String? = null
    private var benefitStatus: Int = 200
    private var monthlyCalled = false
    private var monthlyFails = false
    private lateinit var server: HttpServer

    private fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(MCloudConstants.PATH_AVAILABLE_BENEFIT) { ex ->
            if (benefitBody == null) {
                respond(ex, """{"resultCode":"1","resultDesc":"no benefit"}""", benefitStatus)
            } else {
                respond(ex, benefitBody!!, benefitStatus)
            }
        }
        server.createContext(MCloudConstants.PATH_MONTHLY_CONSUMPTION) { ex ->
            monthlyCalled = true
            if (monthlyFails) respond(ex, """{"err":1}""", 500) else respond(ex, """{"totalTokens":123456}""")
        }
        server.createContext(MCloudConstants.PATH_USER_IDENTITY) { ex ->
            respond(ex, """{"data":[]}""")
        }
        server.start()
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.stop(0) }
    }

    private fun respond(ex: HttpExchange, text: String, status: Int = 200) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun client() = MCloudCreditClient(
        benefitBase = "http://127.0.0.1:${server.address.port}",
        vipBase = "http://127.0.0.1:${server.address.port}",
        nowMillis = { 1_800_000_000_000L },
    )

    private fun account() = MCloudAccount(
        uid = "1039762344857421899",
        phone = "13800138000",
        apiKey = "k",
        modelUrl = "u",
        basicCredential = "bW9iaWxlOjEzODAwMTM4MDAwOnRva2Vu",
    )

    // ---------------------------------------------------------------- 用例

    @Test
    fun `权益列表为空时余额是未知，不是 0`() {
        start()
        benefitBody = """{"resultCode":"0","userAvailableBenefitList":[]}"""
        val probe = client().fetchCreditDetailed(account())
        // 只有本月用量拿到手时仍应给出 credit（用量是真的），但余额必须标成未知
        val credit = probe.credit
        assertNotNull(credit, "本月用量拿到了，不该整条丢弃：${probe.trace}")
        assertTrue(!credit.balanceKnown, "权益接口没给 benefitValue，余额必须标成未知")
        assertEquals(0L, credit.totalTokens)
        assertTrue(credit.monthlyKnown, "本月用量应当保留")
    }

    @Test
    fun `真的用光时余额是已知的 0`() {
        start()
        benefitBody =
            """{"resultCode":"0","userAvailableBenefitList":[
               {"benefitValue":"1000","benefitUsed":"1000","benefitStatus":"2","reserve3":"[1000 tokens]Token"}
               ]}"""
        val credit = client().fetchCredit(account()).also { assertNotNull(it) }!!
        assertTrue(credit.balanceKnown, "给了 benefitValue 就是已知")
        assertEquals(0L, credit.remainTokens, "确实用光才是 0")
        assertEquals(0, credit.remainPercent)
    }

    @Test
    fun `权益接口彻底失败且没有月用量时返回 null`() {
        start()
        benefitBody = null
        benefitStatus = 500
        monthlyFails = true
        assertNull(client().fetchCredit(account()), "什么都没拿到就该是未知，不能编 0")
    }

    @Test
    fun `benefitValue 缺失时不算成功`() {
        start()
        // 缺 benefitValue：解析失败，benefit 为 null，余额未知但月用量仍在
        benefitBody = """{"resultCode":"0","userAvailableBenefitList":[{"benefitStatus":"2"}]}"""
        val probe = client().fetchCreditDetailed(account())
        val credit = probe.credit
        assertTrue(credit == null || !credit.balanceKnown, "缺字段不能当成 0 额度：${probe.trace}")
    }
}
