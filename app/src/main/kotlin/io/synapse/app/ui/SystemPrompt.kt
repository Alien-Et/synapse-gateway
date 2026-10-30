package io.synapse.app.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.synapse.app.R
import java.lang.ref.WeakReference

/**
 * 全局统一的**系统级**提示入口。
 *
 * 规则（主人定的）：
 * - **短文本 → 系统 Toast**（一闪而过，不打断操作）；
 * - **长文本 → 系统对话框**（`android.app.AlertDialog`，需要用户看一眼、点一下）。
 *
 * 为什么要有这个类：以前提示走三套完全不同的实现——Compose 的 Snackbar（底部灰条，
 * 会盖住内容）、自绘的 Material3 AlertDialog（与系统风格不一致）、零散的 Toast。
 * 同一个「已保存」在三个页面可能是三种长相，长文本还会把 Snackbar 撑成好几行挡在底部。
 *
 * 线程约定：任何线程都可以调用，内部统一切到主线程。
 * 前台 Activity 由 [attach]/[detach] 维护，**App 在后台时直接丢弃提示**——
 * 没有窗口可依附时强行弹窗会抛 BadTokenException（崩溃）。
 */
object SystemPrompt {

    /** 超过这个字数就用对话框。太短的话弹窗太重，太长的话 Toast 会被截断。 */
    private const val DIALOG_THRESHOLD = 20

    private var activityRef: WeakReference<Activity>? = null

    /** 当前正在显示的系统对话框：新提示到来时先关掉旧的，避免叠成两层。 */
    private var currentDialog: AlertDialog? = null

    private val main = Handler(Looper.getMainLooper())

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
        main.post {
            val activity = activityRef?.get() ?: run {
                // 没有前台 Activity（App 在后台）：不能弹窗，会 BadTokenException。
                // 这类场景本来就应该由通知栏负责，这里静默丢弃。
                onDismiss?.invoke()
                return@post
            }
            if (activity.isFinishing || activity.isDestroyed) {
                onDismiss?.invoke()
                return@post
            }
            runCatching {
                // 先关掉上一个提示：连续两次操作时不能叠出两层对话框。
                currentDialog?.let { runCatching { it.dismiss() } }
                currentDialog = null
                // onDismiss 只生效一次：点「知道了」和手动关掉都会走到监听器，
                // 用标记避免重复回调（比如把「已读」状态翻来覆去地设）。
                var notified = false
                fun finish() {
                    if (notified) return
                    notified = true
                    onDismiss?.invoke()
                }
                AlertDialog.Builder(activity, R.style.Theme_AiGateway_Dialog)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("知道了") { dialog, _ -> dialog.dismiss() }
                    .setOnDismissListener {
                        currentDialog = null
                        finish()
                    }
                    .setCancelable(true)
                    .create()
                    .also { dialog ->
                        currentDialog = dialog
                        dialog.show()
                    }
            }.onFailure { onDismiss?.invoke() }
        }
    }
}
