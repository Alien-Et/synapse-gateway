package io.synapse.core.provider

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 凭证判定的判据必须保守：只有「读到了额度」或「明确的失效措辞」才下结论。
 *
 * 这条规则决定用户点「启用」时是恢复账号还是继续停用它 ——
 * 误判成 VALID 会把坏凭证放回选号池（下一次请求又是 401）；
 * 误判成 INVALID 会让一个其实可用的账号必须重新登录。
 */
class CredentialVerdictTest {

    @Test
    fun `能读到额度就是有效`() {
        assertEquals(
            CredentialVerdict.VALID,
            judgeCredentialVerdict(CreditInfo(balance = 1000, known = true)),
        )
    }

    @Test
    fun `明确的失效措辞判为失效`() {
        val reasons = listOf(
            "登录已失效，请重新登录",
            "凭证已失效",
            "上游 HTTP 401（未授权）",
            "HTTP 403 forbidden",
            "session expired, please login",
            "unauthorized",
        )
        for (reason in reasons) {
            assertEquals(
                CredentialVerdict.INVALID,
                judgeCredentialVerdict(CreditInfo(balance = 0, known = false, detail = reason)),
                "应把「$reason」判为凭证失效",
            )
        }
    }

    @Test
    fun `网络错误与未知报错一律算无法确认`() {
        val reasons = listOf(
            "异常 连接超时",
            "java.net.UnknownHostException",
            "上游服务异常（HTTP 500）",
            "额度接口未返回总量",
            "",
        )
        for (reason in reasons) {
            assertEquals(
                CredentialVerdict.UNKNOWN,
                judgeCredentialVerdict(CreditInfo(balance = 0, known = false, detail = reason)),
                "「$reason」不足以判定凭证坏掉",
            )
        }
    }

    @Test
    fun `actionHint 也参与判定`() {
        assertEquals(
            CredentialVerdict.INVALID,
            judgeCredentialVerdict(
                CreditInfo(balance = 0, known = false, detail = "", actionHint = "登录已失效"),
            ),
        )
    }
}
