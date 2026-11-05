package io.synapse.app.ui.providers.chatgpt

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.synapse.app.ui.QuotaPackBar
import io.synapse.app.ui.SectionCard
import io.synapse.app.ui.SectionLabel
import io.synapse.app.ui.providers.ProviderUi
import io.synapse.app.ui.providers.ProviderUiActions
import io.synapse.core.pool.AccountStatus

/**
 * ChatGPT（Codex 订阅）供应商的界面。
 *
 * 登录走浏览器 OAuth：点按钮由网关拉起本地回调服务器（1455）并打开授权页，
 * 授权完成后浏览器会被回调打到本地，页面显示「登录成功」即可返回 App。
 *
 * 额度不显示「剩余次数」而是两个**滚动窗口的百分比**（5 小时 / 周）：
 * 订阅通道的额度模型就是这样的，把它硬画成「还剩 N 次」是编数字。
 */
object ChatGPTUi : ProviderUi {

    override val id = "chatgpt"

    override fun description() = "ChatGPT 订阅额度（Codex 后端）"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.AutoAwesome,
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
                onClick = { actions.onWebLogin(id, "") },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("在浏览器中登录 ChatGPT 账号") }
            Text(
                text = "使用 ChatGPT Plus / Pro 订阅额度，不需要 API Key。" +
                    "授权页在浏览器里打开，完成后会自动回调到本机，看到「登录成功」就可以返回。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        val packs = actions.creditPacksOf(id, status.uid)
        // 首次进详情页没有数据时拉一次（打上游；结果回到 state.creditPacks）
        LaunchedEffect(id, status.uid) {
            if (packs.isEmpty()) {
                actions.onLoadCreditPacks(id, status.uid)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (packs.isEmpty()) {
                // 没有额度包时说清原因（订阅档位 / 邮箱），否则用户只看到一片空白
                if (status.detail.isNotEmpty()) {
                    Text(
                        text = status.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }
            for (pack in packs) {
                QuotaPackBar(pack)
            }
            Text(
                text = "两个窗口都是「已用百分比」：5 小时窗口滚动重置，周窗口按自然周重置。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
