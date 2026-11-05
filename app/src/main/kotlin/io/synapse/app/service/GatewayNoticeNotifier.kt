package io.synapse.app.service

import android.os.Handler
import android.os.Looper
import io.synapse.app.ui.SystemPrompt
import io.synapse.core.gateway.GatewayEngine
import io.synapse.core.gateway.NoticeEvent
import io.synapse.core.gateway.NoticeKind

/**
 * 系统吐司提醒的 App 侧接线。
 *
 * 只做一件事：把 [GatewayEngine.noticeEvents] 里的事件在系统 Toast 里显示出来。
 * **不参与任何调度**——事件发出来的时候，故障转移那一步已经在网关里做完了。
 *
 * 为什么不用横幅/对话框：这类信息是「系统刚刚自动做了什么」，
 * 用户不需要做任何事，弹窗反而会打断正在进行的对话。Toast 一闪而过刚好。
 *
 * 线程约定：引擎回调可能在任意网关线程，这里统一 post 到主线程。
 */
object GatewayNoticeNotifier {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var installed = false

    /** 幂等安装：多次调用只挂一次监听器。 */
    fun install(engine: GatewayEngine) {
        if (installed) return
        installed = true
        engine.noticeEvents.addListener { event ->
            mainHandler.post { show(event) }
        }
    }

    private fun show(event: NoticeEvent) {
        // 事件自带去重键，总线已经挡过重复；这里只负责显示。
        // 用 toast() 而不是 show()：文案往往超过 20 字，走 show() 会变成打断人的弹窗。
        SystemPrompt.toast(prefixOf(event.kind) + event.message)
    }

    /** 给不同提醒加一个短前缀，扫一眼就知道是哪类事件。 */
    private fun prefixOf(kind: NoticeKind): String = when (kind) {
        NoticeKind.COOLDOWN_FAILOVER -> "模型冷却 · "
        NoticeKind.BANNED_FAILOVER -> "模型禁用 · "
        NoticeKind.MODEL_BANNED -> "已禁用 · "
        NoticeKind.CIRCUIT_FAILOVER -> "熔断 · "
        NoticeKind.QUOTA_FAILOVER -> "额度 · "
        NoticeKind.CROSS_PROVIDER_FAILOVER -> "故障转移 · "
        NoticeKind.ACCOUNT_DISABLED -> "账号 · "
        NoticeKind.INFO -> ""
    }
}
