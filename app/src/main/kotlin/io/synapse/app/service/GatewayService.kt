package io.synapse.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.synapse.app.MainActivity
import io.synapse.app.R
import io.synapse.app.AiGatewayApp
import io.synapse.app.gatewayEngine
import io.synapse.core.gateway.EngineWatchdog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 只负责「让进程活着」：网关本身由 UI 侧启动，成功后拉起本服务，
 * 这样锁屏或切后台后端口仍有人监听。
 *
 * 服务同时是引擎的**监护人**：存活期间每 [WATCHDOG_INTERVAL_MS] 做一次真实探活
 * （连端口发 `GET /healthz`），端口没响应就重启引擎；连续失败则诚实收摊
 * （停引擎 + 撤通知 + 停服务），不留「通知说运行中、端口已 refused」的僵尸态。
 *
 * 刻意**不**改成 `START_STICKY`：进程被系统回收后自重建属于灰色软件检测的典型特征，
 * 现在的取舍是「用户点磁贴 / 打开 App 恢复」，而不是自愈复活。
 */
class GatewayService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val watchdogHandler = Handler(Looper.getMainLooper())

    /** 服务是否仍处于「应该让网关运行」的状态；看门狗线程与主线程都读。 */
    @Volatile
    private var active = false

    /** 上一次探活还没返回时跳过本轮，避免请求堆积（探活本身有 2 秒上限）。 */
    @Volatile
    private var probing = false

    private lateinit var watchdog: EngineWatchdog

    private val watchdogTick = object : Runnable {
        override fun run() {
            if (!active) return
            if (probing) {
                scheduleNextTick()
                return
            }
            probing = true
            Thread({
                runCatching { watchdog.tick() }
                probing = false
                watchdogHandler.post {
                    if (active && !watchdog.gaveUp) scheduleNextTick()
                }
            }, "gateway-watchdog").start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        watchdog = EngineWatchdog(
            probe = ::engineHealthy,
            restart = ::restartEngine,
            onGaveUp = ::onEngineUnrecoverable,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = applicationContext.gatewayEngine
        // 通知必须最先发：startForegroundService 拉起的服务不调 startForeground
        // 会被系统判定超时直接崩溃（ForegroundServiceDidNotStartInTime），
        // 而且「服务已启动」本身就是用户要的反馈，不应等引擎验证完才弹。
        // NanoHTTPD.start(timeout=0) 会同步等 bind 完成/抛出，engine.start() 返回
        // 即端口已就绪，所以这里不需要任何先导耗时操作。
        startForeground(NOTIFICATION_ID, buildNotification(engine.settings().port))
        val started = engine.isRunning() || runCatching { engine.start() }.isSuccess
        if (started) {
            engine.requestLog.info("前台服务已拉起，通知已发布")
            acquireLocks()
            // 自动签到的定时排程跟着网关生命周期走：服务在跑才排，服务停了就撤。
            // 复用共享 scheduler，不额外占线程（每天只醒一次）。
            runCatching { engine.refreshAutoCheckinSchedule() }
                .onFailure { engine.requestLog.warn("自动签到排程失败：${it.message}") }
            applyOverlay(engine)
            // 实时状态通知：仅在网关运行期间存在，跟随在途请求增删（onDestroy 一并注销）
            LiveStatusNotifier.setProviderLabelResolver { providerId ->
                engine.registry.get(providerId)?.displayName ?: providerId
            }
            LiveStatusNotifier.start(
                context = this,
                requests = engine.activeRequests,
                enabled = io.synapse.app.data.KeepAlivePrefs.liveNotificationEnabled(engine.store()),
                log = { engine.requestLog.info(it) },
            )
            // START_NOT_STICKY：进程被杀后不自重建（自重建是灰色软件检测的典型特征）。
            // 代价：系统回收后网关不会自动恢复，用户需重新打开 App 或点磁贴。
            startWatchdog()
            return START_NOT_STICKY
        }
        // 引擎没起来：撤掉刚发的通知再退出，避免「通知说运行中、端口没人听」
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 先让看门狗闭嘴：用户主动停服务之后，它不该再把引擎拉起来
        active = false
        if (::watchdog.isInitialized) watchdog.cancel()
        watchdogHandler.removeCallbacks(watchdogTick)
        // 不在这里撤销签到排程：网关服务只负责「让端口活着」，与签到是两件事。
        // 进程仍在（例如悬浮窗保活）时到点照样签到；真正该停的情况是用户关掉
        // 「定时签到」开关 —— 那会走 updateAutoCheckinSettings → refresh 自动撤销。
        LiveStatusNotifier.stop()
        FloatingKeepAlive.hide(this)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------ 看门狗

    private fun startWatchdog() {
        if (active) return
        active = true
        watchdogHandler.removeCallbacks(watchdogTick)
        watchdogHandler.postDelayed(watchdogTick, WATCHDOG_INTERVAL_MS)
    }

    private fun scheduleNextTick() {
        watchdogHandler.removeCallbacks(watchdogTick)
        watchdogHandler.postDelayed(watchdogTick, WATCHDOG_INTERVAL_MS)
    }

    /**
     * 真实探活：连上端口并发一个最小的 HTTP 请求，只有拿到 200 才算引擎在服务。
     *
     * 不能只看 `engine.isRunning()`：它只判断 server 对象非空，accept 线程死掉后
     * 仍然是 true —— 这正是「通知说运行中、端口已 refused」的来源。
     * 也不能只做 TCP connect：监听 socket 还在、accept 循环已死时，内核仍会完成
     * 三次握手，连接能建立但永远没有响应。
     *
     * 回环地址写死 IPv4：`getLoopbackAddress()` 可能返回 `::1`，而网关只绑在
     * `127.0.0.1` 上，拿 IPv6 去探必然失败 —— 会把自己的健康实例误杀。
     */
    private fun engineHealthy(): Boolean {
        val engine = applicationContext.gatewayEngine
        if (!engine.isRunning()) return false
        val port = engine.settings().port
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK_IPV4, port), PROBE_CONNECT_TIMEOUT_MS)
                socket.soTimeout = PROBE_READ_TIMEOUT_MS
                val output = socket.getOutputStream()
                output.write(PROBE_REQUEST.toByteArray(Charsets.US_ASCII))
                output.flush()
                val statusLine = BufferedReader(
                    InputStreamReader(socket.getInputStream(), Charsets.US_ASCII),
                ).readLine()
                statusLine != null && statusLine.contains(" 200")
            }
        }.getOrDefault(false)
    }

    /** 探活失败后的重启：先清掉僵尸 server 对象再重新 bind。 */
    private fun restartEngine(): Boolean {
        val engine = applicationContext.gatewayEngine
        return runCatching {
            engine.requestLog.warn("看门狗：端口无响应，正在重启引擎")
            engine.stop()
            engine.start()
            engine.requestLog.info("看门狗：引擎已重启")
            true
        }.onFailure {
            applicationContext.gatewayEngine.requestLog.warn("看门狗：重启引擎失败（${it.message}）")
        }.getOrDefault(false)
    }

    /**
     * 连续多次救不回来：诚实收摊，不留僵尸通知。
     *
     * 这里显式停引擎 —— 本服务平时的约定是「服务只是陪跑，引擎由 UI 管」，
     * 但走到「端口已死且重启无效」这一步，继续留着引擎对象只会让界面显示假运行中。
     */
    private fun onEngineUnrecoverable() {
        val engine = applicationContext.gatewayEngine
        engine.requestLog.warn("看门狗：连续 ${watchdog.consecutiveFailures} 次探活失败，已停止网关")
        runCatching { engine.stop() }
        notifyGaveUp()
        active = false
        stopSelf()
    }

    /** 收摊时给用户一条可点的说明，避免「通知突然消失、不知道为什么」。 */
    private fun notifyGaveUp() {
        runCatching {
            val open = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(this, AiGatewayApp.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_gateway)
                .setContentTitle("网关已停止")
                .setContentText("端口连续无响应且重启无效，请重新打开应用")
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(GAVE_UP_NOTIFICATION_ID, notification)
        }
    }

    /**
     * 锁屏后 CPU 与 Wi-Fi 会进省电，已有的 TCP 连接会被挂起，客户端只会看到「连接断开」；
     * 前台服务只保证进程不被杀，不保证网络与 CPU 常醒，所以这里显式持锁。
     */
    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, WIFI_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    /**
     * 可选的悬浮窗保活：用户开启时才显示（需要「显示在其他应用上层」权限），
     * 服务停止时一并移除。未授权时静默跳过，不影响网关运行。
     */
    private fun applyOverlay(engine: io.synapse.core.gateway.GatewayEngine) {
        if (!io.synapse.app.data.KeepAlivePrefs.overlayEnabled(engine.store())) return
        val shown = FloatingKeepAlive.show(this, "网关 ${engine.settings().port}")
        if (shown) {
            engine.requestLog.info("悬浮窗保活已开启")
        } else {
            engine.requestLog.warn("悬浮窗保活已开启但未获授权（显示在其他应用上层），本次未显示")
        }
    }

    private fun buildNotification(port: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AiGatewayApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("本地网关运行中 · 端口 $port")
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val GAVE_UP_NOTIFICATION_ID = 1003
        private const val WAKE_LOCK_TAG = "aigw:gateway"
        private const val WIFI_LOCK_TAG = "aigw:gateway-wifi"

        /** 巡检间隔：够快发现僵尸态，又不会给 CPU 添负担。 */
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        private const val PROBE_CONNECT_TIMEOUT_MS = 2_000
        private const val PROBE_READ_TIMEOUT_MS = 2_000

        /** 回环地址写死 IPv4：网关只绑 127.0.0.1，用 ::1 探会误杀。 */
        private const val LOOPBACK_IPV4 = "127.0.0.1"
        private const val PROBE_REQUEST = "GET /healthz HTTP/1.0\r\nHost: 127.0.0.1\r\n\r\n"
    }
}
