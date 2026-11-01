package io.synapse.core.usage

/**
 * 已知额度的「井水不犯河水」快照。
 *
 * 池子里的余额是**两种量纲混在一起**的：
 *
 * - **token**：灵犀这类按 token 计价的（detail 里写「剩余 … tokens」）
 * - **积分**：字节/腾讯/讯飞/商汤这类按积分计价的
 *
 * 这两个数**不存在同一个刻度上**，硬要「换算成 token」需要知道每家的
 * 基准单价与模型倍率——而对话响应里几乎不报本次积分消耗，标定不出来。
 * 所以这里**分开统计、两个数并排显示**，谁也别想污染谁（井水不犯河水）。
 *
 * @property tokensBalance 所有「本来就用 token 计价」的账号余额合计（灵犀这类）。
 * @property pointsBalance 所有「用积分计价」的账号余额合计（其余家）。
 */
data class CreditReport(
    /** token 计价账号的余额合计。 */
    val tokensBalance: Long = 0,
    /** 积分计价账号的余额合计。 */
    val pointsBalance: Long = 0,
) {
    /** 有没有任何以 token 计价的已知余额。 */
    val hasTokens: Boolean get() = tokensBalance > 0

    /** 有没有任何以积分计价的已知余额。 */
    val hasPoints: Boolean get() = pointsBalance > 0

    /** 有没有任何一个已知额度（两种量纲之一）。 */
    val known: Boolean get() = tokensBalance > 0 || pointsBalance > 0
}