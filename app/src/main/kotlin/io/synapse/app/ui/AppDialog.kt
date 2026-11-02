package io.synapse.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 全应用唯一的弹窗外观。
 *
 * ## 为什么要有这一个组件
 *
 * 改造前同一个 App 里同时存在三种弹窗：Material3 `AlertDialog`（9 处）、自绘 `Dialog`
 * （4 处）、系统原生 `AlertDialog.Builder`（5 处）。三者圆角、内边距、标题字号、按钮排布
 * 各不相同，同一类提示在不同页面长得不一样，用户会以为是不同层级的功能。
 *
 * 现在所有弹窗都从这里出，只保证三件事：**同一套圆角与内边距、同一个标题样式、
 * 同一套按钮位置（取消在左、确认在右）**。
 *
 * ## 为什么不用系统原生
 *
 * 进度条、模型勾选列表这类内容原生 `AlertDialog` 画不出来（它只吃纯文本），
 * 强行用就得把结构化内容压成一段文字，可读性更差。所以统一走自绘。
 *
 * ## 尺寸约定
 *
 * - 宽度固定 [widthFraction]（默认 0.92，屏幕过宽时封顶最大宽度）
 * - 高度不写死：内容少时自适应，内容多时封顶并在内部滚动，
 *   这样「确认删除」这种短弹窗和「勾选几十个模型」这种长弹窗都不会撑爆屏幕。
 */
@Composable
fun AppDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 点外部/返回键是否可以关闭。进度类弹窗要传 false：动作已经发出，关掉也只是看不见。 */
    dismissible: Boolean = true,
    /** 标题下的补充说明，小字灰色，用来讲清楚这个弹窗是干什么的。 */
    subtitle: String? = null,
    /** 按钮区；为 null 时不画按钮条（进度弹窗用）。 */
    buttons: (@Composable () -> Unit)? = null,
    /** 正文是否可滚动。默认 true；内容本身是列表时保持 true。 */
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = MAX_WIDTH_DP)
                .heightIn(max = MAX_HEIGHT_DP),
        ) {
            Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                if (buttons != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        buttons()
                    }
                }
            }
        }
    }
}

/** 弹窗按钮：文字按钮，[emphasis] 为 true 时用主色（主操作）。 */
@Composable
fun AppDialogButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasis: Boolean = false,
    destructive: Boolean = false,
) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(
            text = text,
            color = when {
                destructive -> MaterialTheme.colorScheme.error
                emphasis -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 弹窗宽度上限：平板/横屏下不让弹窗铺满整屏。 */
private val MAX_WIDTH_DP = 480.dp

/** 弹窗高度上限：超过就内部滚动，避免长列表把按钮挤出屏幕。 */
private val MAX_HEIGHT_DP = 560.dp

/**
 * 纯文本提示弹窗：正文一段文字 + 一个「知道了」。
 *
 * 对照原来的系统原生 `AlertDialog.Builder.setMessage()`：行为一致（只有一个按钮、
 * 点外部可关），区别只是外观走 [AppDialog]，跟其它弹窗统一。
 */
@Composable
fun AppMessageDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    confirmText: String = "知道了",
) {
    AppDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = {
            AppDialogButton(confirmText, onDismiss, emphasis = true)
        },
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
