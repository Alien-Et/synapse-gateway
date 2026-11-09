package io.synapse.app.ui.providers.cline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.synapse.app.ui.SectionCard
import io.synapse.app.ui.SectionLabel
import io.synapse.app.ui.providers.ProviderUi
import io.synapse.app.ui.providers.ProviderUiActions
import io.synapse.core.pool.AccountStatus
import io.synapse.core.provider.cline.ClineProvider

/**
 * Cline 供应商的界面。
 *
 * ## 为什么登录区只有一句话说明
 *
 * Cline 的设备授权是**四步链路**（发起 → 浏览器输码 → 轮询 → 换取会话令牌），
 * 后两步由网关自动完成。用户视角只有一件事：**点一下、去浏览器输码**。
 * 把中间步骤铺在界面上只会让人以为要手动操作。
 *
 * ## 为什么额度区强调「credit」而不是「积分」
 *
 * Cline 的 credit 与其它几家的积分**不是一回事**：它是与美元等价的消费额度
 * （上游给微 credit，网关 ÷1e6 换算），负值表示已欠费。
 * 写成「积分」会让用户以为可以跨供应商比较。
 *
 * ## 池归属（免费 / 订阅）
 *
 * 两个池挂在**同一个账号**上，模型名自带前缀（`cline-free/...` / `cline-pass/...`）。
 * 界面上不把池画成账号属性，只说明「模型名里的前缀决定走哪个池」——
 * 那正是上游的语义，也是本项目「不静默替换模型」的体现。
 */
object ClineUi : ProviderUi {

    override val id: String = ClineProvider.ID

    override fun description() = "免费池 + 订阅池，一个账号两池通用"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Bolt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        SectionCard(enterIndex = 0) {
            SectionLabel("登录")
            Button(
                onClick = { actions.onDeviceLogin(id, "") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("打开 Cline 授权页")
            }
            Text(
                text = "点开后浏览器会打开授权页并显示一个授权码，确认后回到 App 即可，" +
                    "网关会自动取回凭证。登录一次，免费池与订阅池都能用。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /**
     * 账号详情：余额 + 池说明。
     *
     * 余额由 [io.synapse.core.provider.ProviderCapability.CREDIT_REFRESH] 触发刷新，
     * 结果落在 `AccountStatus.credits` / `detail`；这里只负责**展示**余额与池说明。
     * 「刷新额度」按钮由通用账号区（`ProviderDetailScreen`）统一提供，本组件不再重复渲染。
     */
    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionCard {
                SectionLabel("余额")
                if (status.creditsKnown) {
                    Text(
                        // 负值是欠费，如实显示 —— 这是用户最需要看到的事实
                        text = formatCredits(status.credits),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (status.credits < 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                } else {
                    // 未知与「真是 0」必须区分：显示成 0 会让人以为额度用完了
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (status.detail.isNotEmpty()) {
                    Text(
                        text = status.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // ⚠️ 这里**不放**「刷新额度」按钮：通用账号卡（ProviderDetailScreen 的
                // 账号区）在 `managesOwnAccounts = false` 时已经渲染了一个，再放一个
                // 同一页就有两个同名按钮，点哪个都一样，纯属让人犹豫。
                // 本组件的职责只有「把余额展示出来」。
            }

            SectionCard {
                SectionLabel("额度池")
                Text(
                    text = "同一个账号同时拥有免费池与订阅池。模型名里的前缀决定走哪个池：",
                    style = MaterialTheme.typography.bodySmall,
                )
                PoolHint(ClineProvider.FREE_PREFIX, "免费额度，用完为止")
                PoolHint(ClineProvider.PASS_PREFIX, "ClinePass 订阅额度")
                Text(
                    text = "两池都有同名模型（如 deepseek-v4.1-flash），所以网关不自动补前缀 —— " +
                        "写哪个前缀就走哪个池，不会静默替换。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    /** 一行池说明：前缀单独一行显示，便于用户照抄到客户端配置里。 */
    @Composable
    private fun PoolHint(prefix: String, note: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = prefix,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /**
     * credit 显示。
     *
     * 上游给的是微 credit，网关已经 ÷1e6 换算成 credit；这里**不做任何单位换算**
     * —— 换算只有一处（`ClineProvider.MICRO_CREDITS_PER_CREDIT`）。
     */
    private fun formatCredits(credits: Long): String = "$credits credit"
}
