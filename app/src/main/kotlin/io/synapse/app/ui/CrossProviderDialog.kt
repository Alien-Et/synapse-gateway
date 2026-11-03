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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.synapse.core.failover.CrossProviderCandidate
import io.synapse.core.failover.CrossProviderStrategy
import io.synapse.core.provider.RoutedModel

/**
 * 跨供应商候选配置弹窗：列出全部模型供手动勾选，再选路由策略。
 *
 * ## 保存方式：勾选即保存，没有「确认」这一步
 *
 * 这个弹窗**不持有草稿状态**：每次勾选/取消/切策略都立刻通过回调写回设置。
 * 原因是原来那套「本地暂存 + 底部保存按钮」制造了一个必然出错的场景：
 * `AppDialog` 默认允许点外部或按返回键关闭，而关闭走 `onDismiss`，
 * 暂存的勾选被静默丢弃——用户勾完模型顺手点一下外面，回来就是空的，
 * 看起来就是「选了根本存不住」。
 *
 * 去掉草稿态之后，点外部关、按返回关、点「完成」关，三种退出方式结果完全一致。
 * 底部按钮因此从「保存」改成「完成」，它的语义是「我看完了」，不是「提交」。
 * 另外这一页的开关与数字框本来就是改完立即落盘，候选也照这个规矩才一致。
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
    modelsLoading: Boolean,
    modelsError: String,
    candidates: List<CrossProviderCandidate>,
    strategy: CrossProviderStrategy,
    /** 各供应商已缓存的剩余额度文案（providerId → 「积分 12,345」），未知的供应商不放进 map。 */
    providerCredits: Map<String, String>,
    /** 勾选/取消某个候选（true = 勾上）。调用方应立即落盘。 */
    onToggleCandidate: (CrossProviderCandidate, Boolean) -> Unit,
    onStrategyChange: (CrossProviderStrategy) -> Unit,
    /** 打开弹窗时自动调用一次（节流版，进程内拉过就不重复打上游）。 */
    onEnsureModels: () -> Unit,
    /** 用户点「重新拉取模型」时调用（强制重打上游）。 */
    onRefreshModels: () -> Unit,
    onDismiss: () -> Unit,
) {
    // 进弹窗就自动拉一次：模型目录只在「首次进模型页」和「手动刷新」时更新，
    // 用户从设置页直接点进来时 state.models 往往是空的，不自动拉就只看到一句
    // 「先去模型页拉取」——那等于把用户踢去另一个页面绕一圈。
    // 这里用节流版：反复开关弹窗不会反复打上游。
    LaunchedEffect(Unit) { onEnsureModels() }

    val grouped = models.groupBy { it.providerName.ifEmpty { it.providerId } }

    AppDialog(
        title = "跨供应商故障转移",
        subtitle = "勾选可作为兜底的模型。当前供应商确认不可用时，才会换到这里的候选。" +
            "勾选即时生效并保存，随时可以关掉本窗口。",
        onDismiss = onDismiss,
        buttons = {
            // 只有一个按钮：选择已经实时落盘了，这里没有「提交」动作，
            // 叫「完成」而不是「保存」，避免用户以为不点就走。
            AppDialogButton(
                text = "完成（已选 ${candidates.size} 个）",
                onClick = onDismiss,
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
                    .clickable { onStrategyChange(item) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = strategy == item, onClick = { onStrategyChange(item) })
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
        if (modelsLoading && models.isEmpty()) {
            Text(
                text = "正在拉取模型目录…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@AppDialog
        }
        if (grouped.isEmpty()) {
            Text(
                text = if (modelsError.isBlank()) {
                    "没有拉到任何模型。供应商需要一个可用账号才能列出模型目录。"
                } else {
                    "没有拉到任何模型：$modelsError"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlineActionButton(text = "重新拉取模型", onClick = onRefreshModels)
            return@AppDialog
        }

        // 已选顺序可读出来，让「按顺序」策略有据可查
        if (candidates.isNotEmpty()) {
            Text(
                text = "当前顺序（按顺序策略即按此优先级）：" +
                    candidates.joinToString(" → ") { "${it.providerId}/${it.model}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        grouped.forEach { (providerName, items) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = providerName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                // 额度是「按供应商」的量级，所以放在分组标题上，不逐行重复。
                // 未刷新过额度的供应商不显示——「不知道」比「显示 0」诚实。
                providerCredits[items.first().providerId]?.let { credits ->
                    Text(
                        text = credits,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items.forEach { routed ->
                val candidate = CrossProviderCandidate(routed.providerId, routed.model.id)
                val checked = candidates.any { it.key == candidate.key }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleCandidate(candidate, !checked) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Column(Modifier.padding(start = 4.dp).weight(1f)) {
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
                    // 倍率：上游给的才有（腾讯/字节/商汤/讯飞都往 extra["rate"] 填）。
                    // 选兜底模型时这是最关键的决策依据——0.5× 和 6.5× 差一个数量级，
                    // 不显示的话用户只能凭模型名猜。
                    ModelRateLabel(routed)
                }
            }
        }
    }
}

/** 模型行右侧的倍率标签；上游没给倍率时不渲染任何东西（不猜）。 */
@Composable
private fun ModelRateLabel(routed: RoutedModel) {
    val extra = routed.model.extra
    val rate = extra["rate"]?.takeIf { it.isNotBlank() } ?: return
    val discountTag = extra["rateDiscountTag"]?.takeIf { it.isNotBlank() }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = "倍率 $rate",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        if (discountTag != null) {
            Text(
                text = discountTag,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
