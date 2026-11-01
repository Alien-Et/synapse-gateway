package io.synapse.core.provider.mcloud

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 额度查询回归测试。
 *
 * 响应样本取自 **2026-10-31 抓包实测**（`移动云盘Ai接口抓包数据.har`），
 * 不是编的，改解析逻辑时这组断言就是防线。
 */
class MCloudCreditClientTest {

    private lateinit var server: HttpServer

    /** 实测样本：总量 1100 万、已用 116656。 */
    private val benefitJson = """
        {"result":{"resultCode":"0","resultDesc":"SUCCESS"},
         "userAvailableBenefitList":[{
           "benefitNo":"RHR135","benefitValue":"11000000","benefitName":"aitokens",
           "benefitDesc":"aitokens","isCalExtreme":1,"strategy":2,
           "benefitStatus":"2","benefitUsed":"116656",
           "reserve3":"[1000万 tokens/月]Token","includeMemberBenefit":"1"}]}
    """.trimIndent()

    /** 实测样本：本月累计 116656。 */
    private val monthlyJson =
        """{"result":{"resultCode":"0","resultDesc":"SUCCESS"},"month":"2026-10","totalTokens":116656}"""

    /** 实测样本：两条记录（1=消耗，4=到期清理）。 */
    private val recordsJson = """
        {"resultCode":"0","resultDesc":"请求成功",
         "data":{"aiTokenConsumptionRecordList":[
           {"recordId":"1","benefitNum":16656,"action":"1","recordTime":"2026-10-31 18:11:23",
            "comments":"云盘MClaw","modelCode":"blian_qwen35_plus","modelName":"Qwen3.5-Plus","coefficient":"0.6"},
           {"recordId":"2","benefitNum":1000000,"action":"4","recordTime":"2026-10-30 12:31:41",
            "comments":"100万tokens叠加包到期清理"}]}}
    """.trimIndent()

    private var benefitCalls = 0
    private var lastAuth = ""

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(MCloudConstants.PATH_AVAILABLE_BENEFIT) { ex ->
            benefitCalls++
            lastAuth = ex.requestHeaders.getFirst("Authorization").orEmpty()
            respond(ex, benefitJson)
        }
        server.createContext(MCloudConstants.PATH_MONTHLY_CONSUMPTION) { ex -> respond(ex, monthlyJson) }
        server.createContext(MCloudConstants.PATH_TOKEN_RECORDS) { ex -> respond(ex, recordsJson) }
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

    private fun base() = "http://127.0.0.1:${server.address.port}"

    private fun client() = MCloudCreditClient(
        benefitBase = base(),
        vipBase = base(),
        nowMillis = { 1_800_000_000_000L },
    )

    private fun account() = MCloudAccount(
        uid = "1039762344857421899",
        phone = "13800138000",
        apiKey = "k",
        modelUrl = "u",
        basicCredential = "bW9iaWxlOjEzODAwMTM4MDAwOnRva2Vu",
    )

    @Test
    fun `解析真实权益响应 剩余等于总量减已用`() {
        val credit = client().fetchCredit(account())
        assertNotNull(credit)
        assertEquals(11_000_000L, credit.totalTokens)
        assertEquals(116_656L, credit.usedTokens)
        assertEquals(10_883_344L, credit.remainTokens)
        assertEquals(116_656L, credit.monthlyTokens)
        assertTrue(credit.monthlyKnown)
        assertTrue(credit.isActive, "benefitStatus=2 表示生效中")
        assertEquals("[1000万 tokens/月]Token", credit.benefitDesc)
    }

    @Test
    fun `剩余百分比按总量计算`() {
        val credit = client().fetchCredit(account())!!
        assertEquals(98, credit.remainPercent)
    }

    @Test
    fun `请求带 Basic 认证`() {
        client().fetchCredit(account())
        assertEquals(1, benefitCalls)
        assertTrue(lastAuth.startsWith("Basic "), "实际：$lastAuth")
    }

    @Test
    fun `凭据为空时直接返回 null 不打上游`() {
        val empty = account().copy(basicCredential = "")
        assertNull(client().fetchCredit(empty))
        assertEquals(0, benefitCalls)
    }

    @Test
    fun `消耗明细解析出模型名与系数`() {
        val records = client().fetchRecords(account())
        assertEquals(2, records.size)
        val first = records.first()
        assertEquals("Qwen3.5-Plus", first.modelName)
        assertEquals(16_656L, first.tokens)
        assertEquals("0.6", first.coefficient)
        assertEquals("2026-10-31 18:11:23", first.timeText)
        assertEquals("云盘MClaw", first.comments)
    }

    @Test
    fun `上游字段缺失时不编造数字`() {
        val broken = MCloudCredit(
            totalTokens = 0, usedTokens = 0, remainTokens = 0, monthlyTokens = 0,
            monthlyKnown = false, monthLabel = "2026-11", benefitDesc = "", benefitStatus = "",
            memberLevel = "", fetchedAtMillis = 0,
        )
        assertFalse(broken.isActive)
        assertEquals(0, broken.remainPercent)
    }
}
