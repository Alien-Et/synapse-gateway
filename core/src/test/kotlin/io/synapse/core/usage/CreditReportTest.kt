package io.synapse.core.usage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「井水不犯河水」的额度快照回归。
 *
 * 核心：token 和积分是**两个独立的量纲**，各自求和、绝不换算。
 * 这决定了面板的显示逻辑：
 * - 都有 → 两个大字并排
 * - 只有一种 → 只显示那一种
 * - 都没有 → 显示「—」
 */
class CreditReportTest {

    @Test
    fun `token 与积分分开统计`() {
        val report = CreditReport(tokensBalance = 7_095_766, pointsBalance = 1_961)
        assertEquals(7_095_766L, report.tokensBalance)
        assertEquals(1_961L, report.pointsBalance)
    }

    @Test
    fun `两种都有`() {
        val report = CreditReport(tokensBalance = 7_095_766, pointsBalance = 1_961)
        assertTrue(report.hasTokens)
        assertTrue(report.hasPoints)
        assertTrue(report.known)
    }

    @Test
    fun `只有 token 没有积分账户`() {
        val report = CreditReport(tokensBalance = 7_095_766, pointsBalance = 0)
        assertTrue(report.hasTokens)
        assertFalse(report.hasPoints)
        assertTrue(report.known)
    }

    @Test
    fun `只有积分没有 token 账户`() {
        val report = CreditReport(tokensBalance = 0, pointsBalance = 1_961)
        assertFalse(report.hasTokens)
        assertTrue(report.hasPoints)
        assertTrue(report.known)
    }

    @Test
    fun `一个都没有时 known 为 false`() {
        val report = CreditReport()
        assertFalse(report.known)
    }

    @Test
    fun `默认构造是零`() {
        val report = CreditReport()
        assertEquals(0L, report.tokensBalance)
        assertEquals(0L, report.pointsBalance)
    }
}