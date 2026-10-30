package io.synapse.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import io.synapse.app.MainActivity
import io.synapse.app.R
import io.synapse.core.gateway.GatewayEngine
import io.synapse.core.gateway.QuotaAlert

/**
 * 额度耗尽提醒：震动 + 通知。
 *
 * 两级，对应 [QuotaAlert.Level]：
 * - [QuotaAlert.Level.ACCOUNT_EXHAUSTED]：单个账号没额度、已自动换同家其它账号继续。
 *   轻提醒：短震一下 + 静默通知（不响铃，避免连发时吵）。
 * - [QuotaAlert.Level.PROVIDER_EXHAUSTED]：这家供应商所有账号都没额度了。
 *   强提醒：两段长震 + 高优先级通知，让人立刻知道要去补额度。
 *
 * 与本类的边界：它**只做提醒**，不参与任何调度或换号决策——换号逻辑在
 * GatewayHttpServer 里（见 ErrorKind.QUOTA 分支），这里收到事件时那一步已经处理完了。
 *
 * 线程约定：`QuotaAlerts` 的回调可能在任意网关线程，这里统一 post 到主线程再发通知/震动。
 */
object QuotaAlertNotifier {

    /** 与前台服务通知（1001）、实时状态（1002）都分开。 */
    const val NOTIFICATION_ID = 1003

    const val CHANNEL_ID = "aigw_quota"

    /** 同一账号的重复告警在这么短时间内只提醒一次，避免一批请求连发时连续震动。 */
    private const val DEDUP_WINDOW_MS = 30_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** key = 供应商+账号+级别，value = 上次提醒时间。 */
    private val lastNotified = HashMap<String, Long>()

    @Volatile
    private var installed = false

    /** 幂等安装：多次调用只挂一次监听器。 */
    fun install(context: Context, engine: GatewayEngine) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        engine.quotaAlerts.addListener { alert, level ->
            mainHandler.post { notify(appContext, alert, level) }
        }
    }

    /**
     * 在 [io.synapse.app.AiGatewayApp.onCreate] 里调用，保证渠道提前建好。
     *
     * 用 IMPORTANCE_HIGH：
     * - DEFAULT 在部分 ROM 上不会弹横幅，只在通知栏冒个图标，很容易错过；
     * - 额度用完是需要人立刻处理的（去充值/换号），横幅才「方便操作」。
     * 震动仍由本类自己触发，所以渠道侧关掉振动，避免同一下震两次。
     */
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService<NotificationManager>() ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "额度提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "账号或供应商额度耗尽时提醒"
            enableVibration(false)
            enableLights(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }

    /** 通知权限是否已授予（Android 13+ 是运行时权限，不主动请求就是默认关）。 */
    fun permissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 通知被完全遮挡（权限未给，或用户在系统设置里手动关了）。 */
    fun notificationsBlocked(context: Context): Boolean =
        !permissionGranted(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 跳到系统的通知设置页，方便用户手动开启。 */
    fun notificationSettingsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }

    private fun notify(context: Context, alert: QuotaAlert, level: QuotaAlert.Level) {
        val now = System.currentTimeMillis()
        val dedupKey = "${alert.providerName}|${alert.accountName}|$level"
        // 去重：同一账号同一级别在窗口内只提醒一次
        val last = lastNotified[dedupKey]
        if (last != null && now - last < DEDUP_WINDOW_MS) return
        lastNotified[dedupKey] = now
        // 顺手清理过期条目，避免长时间挂机后 Map 无限增长
        if (lastNotified.size > 64) {
            lastNotified.entries.removeAll { (_, t) -> now - t > DEDUP_WINDOW_MS }
        }

        // 震动与通知是两条独立通道：
        // 哪怕通知权限没给，震动也照颞——至少能“感觉到”。
        vibrate(context, level)
        postNotification(context, alert, level)
    }

    private fun vibrate(context: Context, level: QuotaAlert.Level) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService<VibratorManager>()?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService<Vibrator>()
        } ?: return

        if (!vibrator.hasVibrator()) return

        val pattern = when (level) {
            // 短震一下：已经自动换号了，只是告知
            QuotaAlert.Level.ACCOUNT_EXHAUSTED -> longArrayOf(0, 60)
            // 两段长震：这家没额度 / 账号全停了，都需要人处理
            QuotaAlert.Level.PROVIDER_EXHAUSTED,
            QuotaAlert.Level.ALL_ACCOUNTS_DISABLED,
            QuotaAlert.Level.MODEL_UNAVAILABLE,
            QuotaAlert.Level.PROVIDER_CIRCUIT_OPEN,
            -> longArrayOf(0, 300, 180, 300)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        }
    }

    private fun postNotification(context: Context, alert: QuotaAlert, level: QuotaAlert.Level) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        val title: String
        val text: String
        val priority: Int
        when (level) {
            QuotaAlert.Level.ACCOUNT_EXHAUSTED -> {
                title = "${alert.providerName} 账号额度用完了"
                text = "${alert.accountName}：${alert.reason}｜已自动切换到同供应商其它账号继续"
                priority = NotificationCompat.PRIORITY_HIGH
            }
            QuotaAlert.Level.PROVIDER_EXHAUSTED -> {
                title = "${alert.providerName} 已无可用额度"
                text = "${alert.accountName}：${alert.reason}｜该供应商账号都没额度了，请尽快充值或换账号"
                priority = NotificationCompat.PRIORITY_MAX
            }
            QuotaAlert.Level.ALL_ACCOUNTS_DISABLED -> {
                // 这不是钱的问题，是账号被关掉了：文案必须指向「启用账号 / 换模型」，
                // 不能误导用户去充值。
                title = "${alert.providerName} 的账号全部不可用"
                text = "${alert.reason}。去「供应商」页启用账号，或改用其它标签的模型"
                priority = NotificationCompat.PRIORITY_MAX
            }
            QuotaAlert.Level.MODEL_UNAVAILABLE -> {
                // 同家账号全都试过了，该模型被上游限制住。告诉用户「换哪个模型」。
                title = "${alert.providerName} 的 ${alert.model} 暂不可用"
                text = "${alert.reason}。换个模型或稍后重试"
                priority = NotificationCompat.PRIORITY_MAX
            }
            QuotaAlert.Level.PROVIDER_CIRCUIT_OPEN -> {
                // 熔断隔离 + 同家无号可换。必须把「等多久」和「换供应商」两条路都说出来。
                title = "${alert.providerName} 暂时熔断，已无账号可换"
                text = "${alert.reason}。可等待恢复后重试，或改用其它供应商的模型"
                priority = NotificationCompat.PRIORITY_MAX
            }
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(priority)
            // 横幅（heads-up）的硬条件：高/最高优先级 + 不能静音。
            // 之前 setSilent(true) 会让部分 ROM 降级成普通通知栏条目，主人就看不到横幅了。
            // 振动仍是本类手动触发，所以系统侧振动参数置空，避免“震两下”。
            .setDefaults(0)
            .setVibrate(null)
            .setCategory(NotificationCompat.CATEGORY_ERROR)

        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }
}
