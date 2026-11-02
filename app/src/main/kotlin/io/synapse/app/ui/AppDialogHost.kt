package io.synapse.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue

/**
 * 全应用弹窗的**唯一挂载点**：放在 MainActivity 的最终布局里，包住所有页面。
 *
 * ## 为什么必须挂在最外层
 *
 * 弹窗类 UI 如果跟着某个页面挂（比如只挂在首页），用户切到别的 Tab 就看不到它了。
 * 批量签到要串行打十几次上游、十几秒内没有反馈，用户会以为「点了没反应」然后重复点，
 * 反而把上游请求翻倍——所以进度浮层必须跨页面可见。
 *
 * 把挂载点放在这里还有一个好处：页面本身的 Composable 不需要关心「有没有弹窗要画」，
 * 各自只管自己的内容。
 */
@Composable
fun AppDialogHost() {
    // 由 SystemPrompt.alert 投递过来的长文本提示（任何线程都可能写，Compose 自己会重组）
    val request by SystemPrompt.pendingRequest()
    val current = request
    if (current != null) {
        AppMessageDialog(
            title = current.title,
            message = current.message,
            onDismiss = { SystemPrompt.consume(current) },
        )
    }
}
