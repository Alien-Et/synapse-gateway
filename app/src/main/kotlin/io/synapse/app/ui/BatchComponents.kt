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
import androidx.compose.material3.AlertDialog
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
import io.synapse.app.ui.theme.LocalAppColors

/**
 * 批量动作的进度卡：显示「已完成 X/N（当前：某账号）」与一条细进度条。
 *
 * 批量从串行改为有界并发后，一次操作可能要等十几秒；只给一句笼统的 busy 用户不知道卡在哪，
 * 所以把每个账号的完成进度实时画出来。由发起批量动作的页面渲染（首页 / 任务中心）。
 */
@Composable
fun BatchProgressCard(progress: BatchProgressUi) {
    val gold = LocalAppColors.current.goldStart
    SectionCard {
        Text(
            text = if (progress.total > 0) {
                "已完成 ${progress.done}/${progress.total}（当前：${progress.current}）"
            } else {
                "批量任务准备中…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
    }
}

/**
 * 批量动作结束后的结果面板：「成功 N 个 / 失败 M 个」+ 每个账号的逐条原因。
 *
 * 改用**系统原生对话框**（[SystemPrompt.alert]）：主人要求全局提示统一走安卓接口，
 * 不再用自绘的 Material3 AlertDialog——同一种「结果提示」在系统里应该长同一张脸。
 *
 * 原生 AlertDialog 只吃纯文本，所以这里把结果拼成结构化文字：
 * 先失败项（用户最需要看的），再成功项，每项「账号：结果」。
 */
@Composable
fun BatchOutcomeDialog(outcome: BatchOutcome, onDismiss: () -> Unit) {
    val message = remember(outcome) {
        buildString {
            append("成功 ${outcome.succeeded} 个，失败 ${outcome.failed} 个")
            val failed = outcome.items.filter { !it.ok }
            val ok = outcome.items.filter { it.ok }
            if (failed.isNotEmpty()) {
                append("\n\n【失败】")
                failed.forEach { append("\n${it.account}：${it.message}") }
            }
            if (ok.isNotEmpty()) {
                append("\n\n【已完成】")
                ok.forEach { append("\n${it.account}：${it.message}") }
            }
        }
    }
    LaunchedEffect(outcome) {
        SystemPrompt.alert("${outcome.title}结果", message, onDismiss)
    }
}

/** 页面级入口：批量结果非空时弹结果面板，为空时什么都不画。 */
@Composable
fun BatchOutcomeHost(outcome: BatchOutcome?, onDismiss: () -> Unit) {
    outcome?.let { BatchOutcomeDialog(it, onDismiss) }
}

/** 页面内联进度：进度为空（未在批量动作）时什么都不画。 */
@Composable
fun BatchProgressHost(progress: BatchProgressUi?) {
    progress?.let { BatchProgressCard(it) }
}
