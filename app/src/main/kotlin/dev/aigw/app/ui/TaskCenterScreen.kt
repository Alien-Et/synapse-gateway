package dev.aigw.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 折叠时仍然展示的账号数：超过这个数才值得折叠，否则收起来反而多点一次。 */
private const val COLLAPSE_THRESHOLD = 3

/**
 * 任务中心：批量签到与额度维护。
 *
 * 只对声明了签到能力的供应商（Trae / WorkBuddy）执行签到；
 * 额度刷新对所有供应商生效（自定义供应商没有额度接口，会返回「不支持」）。
 *
 * 账号按供应商分卡片：账号多的供应商默认收起，避免一屏被某个供应商占满、其它账号翻不到。
 */
@Composable
fun TaskCenterScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onOpenProviders: () -> Unit = {},
    onBack: () -> Unit,
) {
    BatchOutcomeHost(state.batchOutcome) { viewModel.dismissBatchOutcome() }
    PageScaffold(
        title = "任务中心",
        subtitle = "签到与额度",
        leading = { BackButton(onBack) },
    ) {
        BatchProgressHost(state.batchProgress)

        SectionCard(enterIndex = 0) {
            SectionLabel("批量操作")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = { viewModel.checkinAll() }, modifier = Modifier.weight(1f)) {
                    Text("一键签到")
                }
                Button(onClick = { viewModel.refreshAllCredits() }, modifier = Modifier.weight(1f)) {
                    Text("刷新全部额度")
                }
            }
            Text(
                text = "签到作用于 Trae、WorkBuddy、小浣熊（国际版没有签到制度，自动跳过）；" +
                    "任务作用于 Loomy 的新手任务；额度刷新作用于全部账号。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.providers.none { state.accountsOf(it.id).isNotEmpty() }) {
            SectionCard(enterIndex = 1) {
                EmptyHint(
                    title = "还没有账号",
                    message = "先去「供应商」页添加账号，再回来签到。",
                    actionLabel = "去供应商页",
                    onAction = onOpenProviders,
                )
            }
            return@PageScaffold
        }

        // 折叠状态按供应商记住（键 = providerId）；默认全收起，先让人看到有几个供应商各多少账号
        var expandedIds by rememberSaveable { mutableStateOf(emptySet<String>()) }

        for (info in state.providers) {
            val accounts = state.accountsOf(info.id)
            if (accounts.isEmpty()) continue
            val collapsible = accounts.size > COLLAPSE_THRESHOLD
            val isExpanded = !collapsible || info.id in expandedIds
            val shown = if (isExpanded) accounts else accounts.take(COLLAPSE_THRESHOLD)

            SectionCard(enterIndex = 2) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .then(
                            if (collapsible) Modifier.clickable {
                                expandedIds = if (isExpanded) expandedIds - info.id else expandedIds + info.id
                            } else {
                                Modifier
                            },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        SectionLabel(info.displayName)
                        Text(
                            text = "${accounts.size} 个账号 · 可用 ${accounts.count { it.usable }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (collapsible) {
                        Text(
                            text = if (isExpanded) "收起" else "展开",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "收起" else "展开",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 2.dp).size(18.dp),
                        )
                    }
                }

                for (account in shown) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = account.nickname.ifEmpty { account.uid },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = buildString {
                                append("额度 ")
                                append(if (account.creditsKnown) account.credits.toString() else "—")
                                append(" · ")
                                append(accountStatusText(account))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (account.detail.isNotEmpty()) {
                            Text(
                                text = account.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (collapsible && !isExpanded) {
                    Text(
                        text = "还有 ${accounts.size - shown.size} 个账号未显示，点上方「展开」",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
