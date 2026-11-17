package io.synapse.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.synapse.app.AiGatewayApp
import io.synapse.app.MainActivity
import io.synapse.app.R

/**
 * 浏览器登录期间临时拉起的前台服务：只负责把进程抬离「后台 idle」，不碰网关引擎。
 *
 * ## 为什么需要它
 *
 * 浏览器登录用的是**回环回调服务器**，跑在本进程里（`LoopbackCallbackServer`，
 * Trae 51120 / Antigravity 51121）。进程一旦被系统判为 idle 就会被冻结，冻结后
 * 监听 socket 不再 accept，浏览器把授权结果重定向过来只能停在 listen backlog 里
 * 没人应答 —— 用户看到的是「授权页点完确认一直在转圈」，然后超时、重新登录。
 *
 * Custom Tab 只解决外观与返回栈，保不住端口；真正让进程在后台保持可见级 adj 的
 * 只有前台服务。系统对后台进程的管控越严（MIUI / HyperOS / ColorOS），缺这一层
 * 越容易表现为「登录不上」。
 *
 * ## 生命周期
 *
 * 登录发起时启动，[TIMEOUT_MS] 后自行退出（引擎侧回调窗口是
 * `GatewayEngine.CALLBACK_TIMEOUT_MS` = 5 分钟，这里多留一分钟余量），不常驻。
 * 登录成功 / 失败 / 超时都显式 [stop]，不必等它自己到期。
 */
class LoginWaitService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopSelf() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知必须先发：startForegroundService 拉起的服务若不在数秒内 startForeground，
        // 系统会判定超时并直接崩溃（ForegroundServiceDidNotStartInTime）
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, TIMEOUT_MS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AiGatewayApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(getString(R.string.login_wait_title))
            .setContentText(getString(R.string.login_wait_text))
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1002

        /** 引擎侧回调窗口 5 分钟，这里多留一分钟余量后自行退出。 */
        private const val TIMEOUT_MS = 6L * 60 * 1000

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, LoginWaitService::class.java),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LoginWaitService::class.java)) }
        }
    }
}
