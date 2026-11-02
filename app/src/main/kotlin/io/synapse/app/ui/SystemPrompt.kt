package io.synapse.app.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import java.lang.ref.WeakReference

/**
 * 全局统一的**系统级**提示入口。
 *
 * 规则（主人定的）：
 * - **短文本 → 系统 Toast**（一闪而过，不打断操作）；
 * - **长文本 → 应用内统一弹窗**（[AppMessageDialog]，与其它弹窗同一套外观）。
 *
 * 为什么长文本不再用 `android.app.AlertDialog`：那个是系统原生窗口，圆角、内边距、
 * 按钮排布都由系统决定，跟 App 里其它弹窗完全不是一个长相——同一类提示出现两种脸，
 * 用户会以为是不同层级的功能。现在统一由 [ComposeDialogHost] 在 Compose 层渲染，
 * 外观与 [AppDialog] 完全一致。
 *
 * 线程约定：[alert] 只把待显示内容塞进一个 [MutableState]，由 Compose 侧读取后弹窗，
 * 所以任何线程都能安全调用（不用自己切主线程）。
 *
 * 线程约定：任何线程都可以调用，内部统一切到主线程。
 * 前台 Activity 由 [attach]/[detach] 维护，**App 在后台时直接丢弃提示**：
 * 没有窗口可承载时弹出来也没人看见，还会白白占用一个待显示状态。
 */
object SystemPrompt {

    /** 超过这个字数就用对话框。太短的话弹窗太重，太长的话 Toast 会被截断。 */
    private const val DIALOG_THRESHOLD = 20

    private var activityRef: WeakReference<Activity>? = null

    private val main = Handler(Looper.getMainLooper())

    /**
     * 待显示的对话内容；非空时 [ComposeDialogHost] 会画出弹窗。
     *
     * 用可观察状态而不是直接 `AlertDialog.Builder.show()`：后者是系统窗口，
     * 既没法统一外观，也没法在 Compose 里复用 [AppDialog] 的尺寸约定。
     */
    private val pending = mutableStateOf<PromptRequest?>(null)

    /** 一次待显示的长提示。[onDismiss] 在用户关掉后回调（只回调一次）。 */
    class PromptRequest(val title: String, val message: String, val onDismiss: (() -> Unit)?)

    /** 供 [ComposeDialogHost] 读取的待显示提示。 */
    fun pendingRequest(): MutableState<PromptRequest?> = pending

    /** 用户关掉弹窗后调用，清空待显示状态并回调。 */
    fun consume(request: PromptRequest) {
        if (pending.value === request) pending.value = null
        request.onDismiss?.invoke()
    }

    /** 记录当前可用的 Activity（在 Activity.onResume / onPause 里调用）。 */
    fun attach(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun detach(activity: Activity) {
        if (activityRef?.get() === activity) activityRef = null
    }

    /**
     * 统一的自动分流提示：短句走 Toast，长句走对话框。
     *
     * @param message 提示正文。
     * @param title 对话框标题；只有走到对话框分支时才会用到。
     */
    fun show(message: String, title: String = "提示") {
        val text = message.trim()
        if (text.isEmpty()) return
        if (text.length > DIALOG_THRESHOLD || text.contains('\n')) {
            alert(title, text)
        } else {
            toast(text)
        }
    }

    /** 明确要一句话带过的提示。 */
    fun toast(message: String) {
        val text = message.trim()
        if (text.isEmpty()) return
        main.post {
            val ctx = activityRef?.get() ?: return@post
            runCatching {
                Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 明确需要用户点「知道了」的提示。 */
    fun alert(title: String, message: String, onDismiss: (() -> Unit)? = null) {
        // 不用切主线程：只是写一个 mutableState，Compose 侧会在自己的线程读到。
        // 唯一要求是「有前台 Activity」，因为没窗口时弹窗会被直接丢弃（与原来一致）。
        val activity = activityRef?.get()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            onDismiss?.invoke()
            return
        }
        pending.value = PromptRequest(title, message, onDismiss)
    }
}
