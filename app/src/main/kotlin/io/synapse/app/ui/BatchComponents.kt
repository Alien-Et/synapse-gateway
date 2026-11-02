package io.synapse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.synapse.core.gateway.BatchStage
import io.synapse.app.ui.theme.LocalAppColors

/**
 * 批量动作的进度浮层：标题 + 阶段 + 「已完成 X/N」+ 进度条 + 当前账号。
 *
 * **为什么用浮层而不是页内卡片**：页面内的进度卡只在发起动作的那一页可见，
 * 用户一旦切页/滑走就完全看不到进度，只能对着没有变化的界面干等。
 * 批量签到要串行打十几次上游（每个账号一次签到 + 一次刷额度），十几秒内没有任何反馈
 * 会被当成「点了没反应」，然后再点一次，反而让上游把请求翻倍。
 *
 * 所以这里用**不可取消的浮层**：动作进行期间始终盖在最上层，走完自动消失。
 * 不给「取消」按钮——批量动作已经发出的请求不会因为关掉浮层而撤回，
 * 留一个按了也不生效的取消键只会误导。
 */
@Composable
fun BatchProgressDialog(progress: BatchProgressUi) {
    val gold = LocalAppColors.current.goldStart
    AppDialog(
        title = progress.title,
        onDismiss = {},
        dismissible = false,
        subtitle = when {
            progress.total <= 0 -> "正在准备…"
            progress.stage == BatchStage.REFRESH -> "正在刷新额度 ${progress.done}/${progress.total}"
            else -> "正在处理 ${progress.done}/${progress.total}"
        },
    ) {
        val fraction = if (progress.total > 0) {
            (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
        } else {
            0f
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .padding(vertical = 3.dp)
                    .clip(CircleShape)
                    .background(gold),
            )
        }
        if (progress.current.isNotEmpty()) {
            Text(
                text = if (progress.stage == BatchStage.REFRESH) {
                    "当前：${progress.current} 的额度"
                } else {
                    "当前：${progress.current}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        // 已完成的账号：让用户看到「确实一个个在过」，而不是只看到数字在动
        if (progress.recent.isNotEmpty()) {
            Text(
                text = "已完成：" + progress.recent.joinToString("、") { it.account },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 批量动作结束后的结果面板：「成功 N 个 / 失败 M 个」+ 每个账号的逐条原因。
 *
 * 走统一的 [AppDialog]（不再是系统原生 AlertDialog）：失败项用错误色排前面，
 * 成功项跟在后面补充细节——签到场景下「今日已签到」和「签到成功」是两条不同的事实。
 */
@Composable
fun BatchOutcomeDialog(outcome: BatchOutcome, onDismiss: () -> Unit) {
    val failed = remember(outcome) { outcome.items.filter { !it.ok } }
    val ok = remember(outcome) { outcome.items.filter { it.ok } }
    AppDialog(
        title = "${outcome.title}结果",
        onDismiss = onDismiss,
        subtitle = "成功 ${outcome.succeeded} 个，失败 ${outcome.failed} 个",
        buttons = {
            AppDialogButton("知道了", onDismiss, emphasis = true)
        },
    ) {
        if (failed.isNotEmpty()) {
            Text(
                text = "失败",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            failed.forEach { item ->
                Text(
                    text = "${item.account}：${item.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (ok.isNotEmpty()) {
            Text(
                text = "已完成",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ok.forEach { item ->
                Text(
                    text = "${item.account}：${item.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 页面级入口：批量结果非空时弹结果面板，为空时什么都不画。 */
@Composable
fun BatchOutcomeHost(outcome: BatchOutcome?, onDismiss: () -> Unit) {
    outcome?.let { BatchOutcomeDialog(it, onDismiss) }
}

/** 页面级入口：批量动作进行中时显示进度浮层，否则什么都不画。 */
@Composable
fun BatchProgressHost(progress: BatchProgressUi?) {
    progress?.let { BatchProgressDialog(it) }
}
