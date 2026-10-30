package io.synapse.app.ui.providers.antigravity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.synapse.app.ui.QuotaPackBar
import io.synapse.app.ui.SectionCard
import io.synapse.app.ui.SectionLabel
import io.synapse.app.ui.providers.ProviderUi
import io.synapse.app.ui.providers.ProviderUiActions
import io.synapse.core.pool.AccountStatus

object AntigravityUi : ProviderUi {

    override val id = "antigravity"

    override fun description() = "Google 账号 OAuth 登录"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Cloud,
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
            ) { Text("在浏览器中登录 Google 账号") }
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        val packs = actions.creditPacksOf(id, status.uid)
        // 首次进详情页没有数据时拉一次（打上游；拉取结果回到 state.creditPacks）
        LaunchedEffect(id, status.uid) {
            if (packs.isEmpty()) {
                actions.onLoadCreditPacks(id, status.uid)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // 需要用户动手时（如账号要人机验证）把指引放最上面并高亮，
            // 不然用户只看到「没有额度包」完全不知道该干什么。
            if (status.actionHint.isNotEmpty()) {
                val accent = MaterialTheme.colorScheme.error
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = accent.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = status.actionHint,
                        style = MaterialTheme.typography.bodySmall,
                        color = accent,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (packs.isEmpty()) {
                // 没有额度包时也说明一下原因（之前这里直接 return，错误信息完全看不到）
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
        }
    }
}
