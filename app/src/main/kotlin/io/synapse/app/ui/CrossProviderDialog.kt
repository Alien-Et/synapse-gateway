package io.synapse.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.synapse.core.failover.CrossProviderCandidate
import io.synapse.core.failover.CrossProviderStrategy
import io.synapse.core.provider.RoutedModel

/**
 * 跨供应商候选配置弹窗：列出全部模型供手动勾选，再选路由策略。
 *
 * ## 为什么让用户逐个勾模型，而不是简单勾「允许哪些供应商」
 *
 * 「允许某家」意味着用户放弃了「用哪个模型」的控制权：灵犀上的 qwen 和腾讯上的 glm
 * 能力、价格、上下文长度都不一样，机器没法替用户判断哪个能顶替另一个。
 * 勾到具体模型，用户自己确认过「这两个可以互换」，换过去才不会突然换出个不合适的模型。
 *
 * ## 为什么默认关闭、且打开还要再过这一关
 *
 * 跨供应商会打破「我写哪家就发哪家」的预期。默认关闭 + 必须显式配置候选，
 * 两道关都在提醒用户这条路径是「故障兜底」，不是日常路由。
 */
@Composable
fun CrossProviderDialog(
    models: List<RoutedModel>,
    initialCandidates: List<CrossProviderCandidate>,
    initialStrategy: CrossProviderStrategy,
    onDismiss: () -> Unit,
    onConfirm: (List<CrossProviderCandidate>, CrossProviderStrategy) -> Unit,
) {
    // 勾选状态用有序 List 保存：顺序在「按顺序」策略下就是优先级，不能丢
    var selected by remember { mutableStateOf(initialCandidates) }
    var strategy by remember { mutableStateOf(initialStrategy) }

    // 按供应商分组展示：一个供应商几十个模型平铺在一起根本没法找
    val grouped = remember(models) {
        models.groupBy { it.providerName.ifEmpty { it.providerId } }
    }

    AppDialog(
        title = "跨供应商故障转移",
        subtitle = "勾选可作为兜底的模型。当前供应商确认不可用时，才会换到这里的候选。",
        onDismiss = onDismiss,
        buttons = {
            AppDialogButton("取消", onDismiss)
            AppDialogButton(
                text = "保存（已选 ${selected.size} 个）",
                onClick = { onConfirm(selected, strategy) },
                emphasis = true,
            )
        },
    ) {
        // ---- 路由策略 ----
        Text(
            text = "换到候选时怎么挑",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        CrossProviderStrategy.entries.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { strategy = item }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = strategy == item, onClick = { strategy = item })
                Column(Modifier.padding(start = 4.dp)) {
                    Text(item.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = item.hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // ---- 模型勾选 ----
        if (grouped.isEmpty()) {
            Text(
                text = "还没有加载到任何模型。先去「模型」页拉取一次模型目录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppDialog
        }

        // 已选顺序可读出来，让「按顺序」策略有据可查
        if (selected.isNotEmpty()) {
            Text(
                text = "当前顺序：" + selected.joinToString(" → ") { "${it.providerId}/${it.model}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        grouped.forEach { (providerName, items) ->
            Text(
                text = providerName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp),
            )
            items.forEach { routed ->
                val candidate = CrossProviderCandidate(routed.providerId, routed.model.id)
                val checked = selected.any { it.key == candidate.key }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selected = if (checked) {
                                selected.filterNot { it.key == candidate.key }
                            } else {
                                // 追加到末尾：勾选顺序 = 优先级顺序
                                selected + candidate
                            }
                        },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Column(Modifier.padding(start = 4.dp)) {
                        Text(
                            text = routed.model.name.ifEmpty { routed.model.id },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = routed.model.id,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
