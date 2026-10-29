package dev.aigw.core.gateway

import com.google.gson.JsonObject
import dev.aigw.core.pool.AccountImportResult
import dev.aigw.core.pool.AccountPool
import dev.aigw.core.pool.AccountSnapshot
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.pool.AccountTransfer
import dev.aigw.core.pool.PoolSummary
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.DeviceAuthTicket
import dev.aigw.core.provider.DeviceCodeSupport
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.LoopbackOAuthSupport
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderTaskListView
import dev.aigw.core.provider.PointsBill
import dev.aigw.core.provider.PointsBillListView
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.ProviderRegistry
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.RoutedModel
import dev.aigw.core.provider.SmsLoginSupport
import dev.aigw.core.provider.WebLoginSupport
import dev.aigw.core.provider.WebLoginTicket
import dev.aigw.core.provider.antigravity.AntigravityProvider
import dev.aigw.core.provider.custom.CustomProvider
import dev.aigw.core.provider.raccoon.RaccoonProvider
import dev.aigw.core.provider.trae.TraeProvider
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.usage.CallLogStore
import dev.aigw.core.usage.RequestLog
import dev.aigw.core.usage.StorageAudit
import dev.aigw.core.usage.StorageReport
import dev.aigw.core.usage.UsageStats
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

/** 登录状态。 */
enum class LoginState { SUCCESS, FAILED }

/** 一次登录/导入的结果。 */
data class LoginOutcome(
    val state: LoginState,
    val uid: String = "",
    val nickname: String = "",
    val error: String = "",
) {
    val ok: Boolean get() = state == LoginState.SUCCESS
}

/** 额度刷新结果。 */
data class CreditRefreshResult(
    val providerId: String,
    val uid: String,
    val nickname: String,
    val balance: Long,
    val known: Boolean,
    val error: String,
)

/** 批量动作的目标账号（providerId + uid）。 */
data class BatchTarget(val providerId: String, val uid: String)

/**
 * 批量动作中单个账号的结果。
 *
 * 用结构化对象逐个带回，调用方才能「成功 N 个 / 失败 M 个 + 失败逐条原因」地展示，
 * 而不是把整批失败拼成一条读不完的异常字符串。
 */
data class BatchActionResult(
    val providerId: String,
    val uid: String,
    val nickname: String,
    val ok: Boolean,
    val message: String,
)

/**
 * 批量任务进行中的进度事件（在后台线程发出，调用方自行切回 UI 线程）。
 * [done] 为已完成个数、[total] 为总个数，[nickname]/[uid] 是刚完成的那一个账号。
 */
data class BatchProgress(
    val done: Int,
    val total: Int,
    val providerId: String,
    val uid: String,
    val nickname: String,
)

/** 流式拉取模型目录时，单个供应商的就绪事件（成功带 models，失败带 error）。 */
data class ProviderModelsChunk(
    val providerId: String,
    val models: List<RoutedModel>,
    /** 非空表示该供应商拉取失败（异常消息或供应商自报的目录错误），此时 models 为空。 */
    val error: String? = null,
)

/** 全部供应商模型目录的最终聚合结果。 */
data class ModelsCatalog(
    val models: List<RoutedModel>,
    /** 拉取失败的供应商与原因，键为 providerId。 */
    val failures: Map<String, String> = emptyMap(),
)

/** 供应商概览，供「供应商」列表展示。 */
data class ProviderInfo(
    val id: String,
    val displayName: String,
    val authKind: AuthKind,
    val capabilities: Set<ProviderCapability>,
    val enabled: Boolean,
    val accountCount: Int,
    val usableCount: Int,
    /** 用户手动导入的供应商（可编辑/删除）。 */
    val custom: Boolean,
)

/** 服务运行状态，供「首页」展示。 */
data class GatewayStatus(
    val running: Boolean,
    val port: Int,
    val localUrl: String,
    val lanUrls: List<String>,
    val pool: PoolSummary,
    val today: UsageStats,
    val missingApiKey: Boolean,
    val providerCount: Int,
)

/**
 * 模型路由结果：哪个供应商的哪个模型。
 *
 * [region] 非空时（区域型前缀，如 `codebuddy-cn/xxx`）选号只在该区域的账号中进行。
 */
data class Route(val providerId: String, val model: String, val region: String? = null)

/**
 * 浏览器登录入口的返回值。
 *
 * [loginUrl] 交给系统浏览器打开；[pollState] 非空表示该供应商靠轮询换取 token
 * （WorkBuddy），调用方需要在打开浏览器后启动轮询。
 *
 * [region] 是创建本次授权所用的区域（区域型供应商）：轮询必须用同一个区域，
 * 否则会打到另一个站点、永远查不到这个 state。
 */
data class BrowserLoginTicket(val loginUrl: String, val pollState: String = "", val region: String = "")

/** 模型探测结果：延迟（毫秒）与失败原因。 */
data class ProbeResult(val latencyMs: Long, val error: String = "")

/** 局域网地址来源。Android 侧用系统 API 提供，命令行/测试用默认实现。 */
fun interface LanAddressProvider {
    fun lanAddresses(): List<String>
}

/**
 * 网关引擎：把账号池、各供应商、HTTP 服务、日志与设置串起来。
 * UI 直接调用这里的方法，不需要走 HTTP。
 */
class GatewayEngine(
    private val store: KeyValueStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val lanAddressProvider: LanAddressProvider = DefaultLanAddressProvider,
    private val onLog: (String) -> Unit = {},
    /**
     * 调用记录是否在构造时同步载入。一次性 CLI 工具需要同步（命令立即读数据）；
     * App 传 false，预载与过期清理改在后台线程执行，冷启动不再阻塞主线程。
     */
    preloadCallLogs: Boolean = true,
    /** 完整原文溢出目录：加密存储只留每字段 8K 摘要，超出部分写这里供详情页回读；null 时仅摘要。 */
    spillDir: java.io.File? = null,
) {
    val settingsRepository = SettingsRepository(store)
    val pool = AccountPool(store, nowMillis)
    val callLogStore = CallLogStore(store, preloadOnConstruction = preloadCallLogs, spillDir = spillDir)
    val requestLog = RequestLog(store, nowMillis = nowMillis)
    val registry = ProviderRegistry()

    val sanitizer = dev.aigw.core.security.RequestSanitizer(nowMillis)
    val rateLimiter = dev.aigw.core.security.AccountRateLimiter(nowMillis)

    /** 在途请求表（仅供状态展示：通知/界面）；不参与调度，见 [ActiveRequests]。 */
    val activeRequests = ActiveRequests(nowMillis)

    /**
     * 额度告警总线：某个账号/整个供应商没额度时向外发事件，由 App 层做震动+通知。
     *
     * 只负责「告知」，**不参与调度**：告警没人监听或监听器抛异常，转发照跑。
     */
    val quotaAlerts = QuotaAlerts()

    @Volatile
    private var failoverSettings: dev.aigw.core.failover.ModelFailoverSettings =
        dev.aigw.core.failover.ModelFailoverSettings.fromJson(store.read(dev.aigw.core.failover.ModelFailoverSettings.STORE_KEY))

    private val circuitBreakers = ConcurrentHashMap<String, dev.aigw.core.failover.ProviderCircuitBreaker>()
    private val providerMetrics = ConcurrentHashMap<String, dev.aigw.core.failover.ProviderMetricsTracker>()

    /** 模型级冷却表：该 (供应商, 模型) 暂时不可用时直接跳过，换号无意义。仅内存。 */
    private val modelCooldown = dev.aigw.core.failover.ModelCooldownTracker(nowMillis)

    /** 上游请求并发闸门：配置随 failoverSettings 动态生效。 */
    val concurrencyGate = dev.aigw.core.failover.ConcurrencyGate { failoverSettings.concurrency }

    @Volatile
    private var securitySettings: dev.aigw.core.security.SecuritySettings =
        dev.aigw.core.security.SecuritySettings.fromJson(store.read(dev.aigw.core.security.SecuritySettings.STORE_KEY))

    @Volatile
    private var settings: GatewaySettings = settingsRepository.load()

    private val providerSettingsCache = ConcurrentHashMap<String, ProviderSettings>()

    /**
     * 供应商上游域名表的缓存：`providerHosts()` 被 [GatewayProxySelector] 在每次建连时读取
     * （仅当配置了 `excludedProviderIds`），原来每次新建一个 Map 并遍历全部 provider。
     * providers 注册变更（[reloadCustomProviders]）时失效即可，其余时间复用。
     */
    @Volatile
    private var providerHostsCache: Map<String, List<String>>? = null

    /**
     * 共享 I/O 线程池：模型目录、额度查询等会阻塞在网络上的并行任务都走这里。
     * 之前每次请求都 new 一个线程池再 shutdown，短连接 + 线程反复创建销毁纯属浪费；
     * 这里常驻复用，空闲线程 60 秒自行回收，不常占资源。
     */
    private val ioPool = ThreadPoolExecutor(
        0, 8, 60L, TimeUnit.SECONDS, SynchronousQueue(),
        { runnable -> Thread(runnable, "aigw-io").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    /** 共享定时器：SSE 心跳等周期性小任务复用，避免每条流各建一个调度线程。 */
    private val scheduler: ScheduledExecutorService = ScheduledThreadPoolExecutor(2) { runnable ->
        Thread(runnable, "aigw-sched").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    /** 供 HTTP 层复用同一套后台线程池与定时器。 */
    internal fun ioExecutor(): ThreadPoolExecutor = ioPool

    internal fun schedulerExecutor(): ScheduledExecutorService = scheduler

    /** 对话成功后的额度补刷新：没有流内计费回报的供应商（如 Loomy）靠它让池里的数字跟上消耗。 */
    private val creditRefresher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "credit-refresh").apply { isDaemon = true }
    }
    private val lastAutoCreditRefresh = ConcurrentHashMap<String, Long>()

    private var server: GatewayHttpServer? = null

    /** 串行化 start/stop：startForegroundService 与 UI 协程可能同时调 start()，无锁会出现双建 server 或假失败。 */
    private val lifecycleLock = Any()

    @Volatile
    private var lastAutoPurgeDay: Long = -1L

    internal val hooks = ProviderHooks(
        onBilling = { providerId, uid, credits ->
            pool.updateCredits(providerId, uid, credits, known = true, detail = "来自对话计费回报")
            log("计费回报（$providerId/$uid）：可用额度=$credits")
        },
        onAccountError = { providerId, uid, error ->
            // 只有凭证失效才硬禁用；其它错误不再冷却账号，每次如实上报
            if (error.kind == ErrorKind.SESSION_DEAD) {
                pool.disable(providerId, uid, error.message)
            }
            log("供应商 [$providerId] 账号 ${uid} 流内错误：${error.message}")
        },
        onAccountUpdated = { account ->
            pool.saveAccount(account)
            log("账号凭证已自动持久化更新（${account.providerId}/${account.nickname.ifEmpty { account.uid }}）")
            onAccountsChanged?.invoke()
        },
        onLog = { log(it) },
        onVerbose = { tag, text -> logVerbose(tag, text) },
    )

    /** 账号池发生变化（登录成功、导入等）时回调，供 UI 刷新。 */
    var onAccountsChanged: (() -> Unit)? = null

    /** 代理设置（境外供应商需要）。 */
    @Volatile
    private var proxy: ProxySettings = ProxySettings.fromJson(store.read(ProxySettings.STORE_KEY))

    /** 模型开放名单：独立于 [settings] 存储与保存入口，勾选模型不触发 provider 重载。 */
    @Volatile
    private var modelExposure: ModelExposureSettings = ModelExposureSettings.load(store)

    /** 当前正在监听的登录回调服务（同一时间只允许一个登录流程）。 */
    private var callbackServer: LoopbackCallbackServer? = null

    /** 首屏数据（调用记录与统计、过期清理）是否已在后台就绪。 */
    @Volatile
    private var startupDataReady = false

    @Volatile
    private var startupDataListener: (() -> Unit)? = null

    init {
        registerBuiltinProviders(this)
        reloadCustomProviders()
        installProxy()
        sanitizer.reloadPipeline(securitySettings.extraWords)
        lastAutoPurgeDay = nowMillis() / DAY_MILLIS
        if (preloadCallLogs) {
            runCatching { purgeExpiredRecords() }
        } else {
            // 调用记录预载与过期清理要扫全部 logs/calls/ 键并逐个解密，
            // App 冷启动时丢到后台执行，不让主线程等它；完成后通知 UI 补一次刷新。
            ioExecutor().execute {
                runCatching {
                    callLogStore.load()
                    purgeExpiredRecords()
                }
                startupDataReady = true
                startupDataListener?.invoke()
            }
        }
    }

    /**
     * 注册首屏数据（调用记录/统计）就绪回调。
     *
     * 这些数据在后台线程预载，冷启动时可能晚于 UI 首帧；注册时若已就绪会立即触发一次。
     */
    fun setStartupDataReadyListener(listener: (() -> Unit)?) {
        startupDataListener = listener
        if (startupDataReady) listener?.invoke()
    }

    internal fun now(): Long = nowMillis()

    /** 上层（UI/CLI）复用同一个存储，避免重复打开加密容器。 */
    fun store(): KeyValueStore = store

    // ------------------------------------------------------------------ 安全防护与风控

    fun securitySettings(): dev.aigw.core.security.SecuritySettings = securitySettings

    fun updateSecuritySettings(updated: dev.aigw.core.security.SecuritySettings) {
        securitySettings = updated
        store.write(dev.aigw.core.security.SecuritySettings.STORE_KEY, updated.toJson().toString())
        sanitizer.reloadPipeline(updated.extraWords)
        log("安全防护设置已更新（脱敏=${if (updated.sanitizeEnabled) "开启" else "关闭"}，限速=${updated.minIntervalMillis}ms，抖动=${updated.jitterMillis}ms）")
    }

    /** 重新加载脱敏处理链与规则词表。 */
    fun reloadSanitizerPipeline(): Int {
        sanitizer.reloadPipeline(securitySettings.extraWords)
        val count = sanitizer.currentRulesCount()
        log("反审核脱敏处理链已重新加载，当前生效规则词汇数：$count")
        return count
    }

    // ------------------------------------------------------------------ 故障转移与容灾调度

    fun failoverSettings(): dev.aigw.core.failover.ModelFailoverSettings = failoverSettings

    fun updateFailoverSettings(updated: dev.aigw.core.failover.ModelFailoverSettings) {
        failoverSettings = updated
        store.write(dev.aigw.core.failover.ModelFailoverSettings.STORE_KEY, updated.toJson().toString())
        log(
            "故障转移配置已更新（启用=${updated.enabled}，单供应商最大重试=${updated.retry.maxAttempts}，" +
                "熔断阈值=${updated.circuitBreaker.failureThreshold}，并发：单账号=${updated.concurrency.maxPerAccount}，" +
                "全局=${updated.concurrency.maxGlobal}，0 表示不限；模型级冷却=${updated.modelCooldown.cooldownMs}ms）",
        )
    }

    fun circuitBreakerOf(providerId: String): dev.aigw.core.failover.ProviderCircuitBreaker =
        circuitBreakers.computeIfAbsent(providerId) {
            dev.aigw.core.failover.ProviderCircuitBreaker(providerId, config = { failoverSettings.circuitBreaker }, nowMillis = nowMillis)
        }

    fun metricsOf(providerId: String): dev.aigw.core.failover.ProviderMetricsTracker =
        providerMetrics.computeIfAbsent(providerId) { dev.aigw.core.failover.ProviderMetricsTracker() }

    fun modelCooldownOf(): dev.aigw.core.failover.ModelCooldownTracker = modelCooldown

    // ------------------------------------------------------------------ 模型开放名单

    fun modelExposureSettings(): ModelExposureSettings = modelExposure

    /** 更新开放名单并落盘；独立入口，不调用 reconfigureProviders。 */
    fun updateModelExposure(updated: ModelExposureSettings) {
        modelExposure = updated
        store.write(ModelExposureSettings.STORE_KEY, updated.toJson().toString())
        log("模型开放名单已更新（过滤=${if (updated.filterEnabled) "开启" else "关闭"}，已勾选=${updated.exposed.size}）")
    }

    /** [filterEnabled] 为 false 时原样返回，否则只保留在开放名单中的模型。 */
    private fun filterExposed(models: List<RoutedModel>): List<RoutedModel> =
        if (!modelExposure.filterEnabled) models else models.filter { modelExposure.allows(it.fullId) }

    /**
     * 对外暴露的模型目录：模型页 UI 用 `modelsStreaming`（不过滤，展示全量，供勾选）；
     * HTTP `/v1/models` 用本方法（按开放名单过滤）。
     */
    fun exposedModels(): List<RoutedModel> = filterExposed(models())

    // ------------------------------------------------------------------ 代理

    fun proxySettings(): ProxySettings = proxy

    fun updateProxySettings(updated: ProxySettings) {
        proxy = updated
        store.write(ProxySettings.STORE_KEY, updated.toJson().toString())
        log(if (updated.usable) "代理已启用：${updated.host}:${updated.port}" else "代理未启用")
    }

    /**
     * 各供应商的上游域名，供代理规则匹配（按供应商开关代理）。
     *
     * provider 注册集合只在 [reloadCustomProviders] 改变，故建连热路径（ProxySelector 每次
     * `select`）复用缓存，避免每次都遍历全部 provider 现拼一个 Map。
     */
    fun providerHosts(): Map<String, List<String>> {
        providerHostsCache?.let { return it }
        val built = registry.all().associate { it.id to it.hosts() }
        providerHostsCache = built
        return built
    }

    /**
     * 安装全局代理选择器。
     *
     * 用全局 [java.net.ProxySelector] 而不是逐请求传 Proxy：各 provider 自己 new 连接，
     * 逐个改造成本高；而「按供应商」本质上就是「按上游域名」，选择器能直接表达。
     */
    private fun installProxy() {
        runCatching {
            java.net.ProxySelector.setDefault(
                GatewayProxySelector(settings = { proxy }, providerHosts = { providerHosts() }),
            )
            java.net.Authenticator.setDefault(GatewayProxyAuthenticator { proxy })
        }.onFailure { logWarn("代理设置安装失败：${it.message}") }
    }

    // ------------------------------------------------------------------ 设置

    fun settings(): GatewaySettings = settings

    fun updateSettings(updated: GatewaySettings) {
        // 值未变时短路：避免每次保存都重建所有 provider 内部客户端
        if (updated == settings) return
        val oldCapacity = settings.requestLogCapacity
        settings = updated
        settingsRepository.save(updated)
        if (updated.requestLogCapacity != oldCapacity) requestLog.resize(updated.requestLogCapacity)
        reconfigureProviders()
    }

    fun providerSettings(id: String): ProviderSettings =
        providerSettingsCache.getOrPut(id) { settingsRepository.loadProvider(id) }

    fun updateProviderSettings(id: String, value: ProviderSettings) {
        if (providerSettingsCache[id] == value) return
        providerSettingsCache[id] = value
        settingsRepository.saveProvider(id, value)
        reconfigureProviders()
    }

    /** 设置变更后让 provider 重建内部客户端（如 Trae 的版本号）。 */
    private fun reconfigureProviders() {
        for (provider in registry.all()) {
            (provider as? Reconfigurable)?.reconfigure()
        }
    }

    /** 供应商可选实现：设置变更时需要重建内部状态。 */
    interface Reconfigurable {
        fun reconfigure()
    }

    // ------------------------------------------------------------------ 生命周期

    /** 启动网关；端口被占用等错误通过异常抛出。 */
    @Throws(Exception::class)
    fun start() {
        synchronized(lifecycleLock) {
            if (server != null) return
            val host = if (settings.exposeLan) null else "127.0.0.1"
            val created = GatewayHttpServer(this, host, settings.port)
            created.start(0, false)
            server = created
            log("网关已启动，监听 ${created.listeningAddress()}")
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            server?.stop()
            server = null
            requestLog.flush()
            log("网关已停止")
        }
    }

    fun isRunning(): Boolean = server != null

    fun localUrl(): String = "http://127.0.0.1:${settings.port}/v1"

    fun lanUrls(): List<String> = lanAddressProvider.lanAddresses().map { "http://$it:${settings.port}/v1" }

    fun status(): GatewayStatus = GatewayStatus(
        running = isRunning(),
        port = settings.port,
        localUrl = localUrl(),
        lanUrls = lanUrls(),
        pool = pool.summary(),
        // 用注入的 nowMillis（而非系统时钟），“今天”才能在固定时钟的测试里可控
        today = callLogStore.todayStats(nowMillis()),
        missingApiKey = !settings.allowNoKey && settings.apiKey.isEmpty(),
        providerCount = registry.all().count { providerSettings(it.id).enabled },
    )

    // ------------------------------------------------------------------ 供应商

    fun providers(): List<ProviderInfo> = registry.all().map { provider ->
        val summary = pool.summary(provider.id)
        ProviderInfo(
            id = provider.id,
            displayName = provider.displayName,
            authKind = provider.authKind,
            capabilities = provider.capabilities,
            enabled = providerSettings(provider.id).enabled,
            accountCount = summary.total,
            usableCount = summary.usable,
            custom = provider.id.startsWith(CUSTOM_PREFIX),
        )
    }

    /** 重新读取自定义供应商配置并登记（增删改后调用）。 */
    fun reloadCustomProviders() {
        // provider 集合即将变化，先失效域名表缓存（见 [providerHosts]）
        providerHostsCache = null
        // 先摘掉已登记的，避免删掉的供应商还留在列表里
        for (provider in registry.all()) {
            if (provider.id.startsWith(CUSTOM_PREFIX)) registry.unregister(provider.id)
        }
        for (config in settingsRepository.loadCustomProviders()) {
            registry.register(
                dev.aigw.core.provider.custom.CustomProvider(
                    config = config,
                    nowMillis = nowMillis,
                ),
            )
        }
    }

    /**
     * 把自定义供应商配置里的 API Key 同步成账号池里的账号（新增加入、移除的删号）。
     *
     * 两处存储容易脱节：详情页读的是账号池，而新建时填的 key 先落在供应商配置里，
     * 不同步的话保存完进详情页会看不到刚填的 key。
     */
    /**
     * 把自定义供应商配置里的 API Key 同步成账号池里的账号。
     *
     * **只增不删**。早期版本会按「配置里没有的就删账号」对齐，这在新表单下是危险的：
     * 编辑页出于安全不回显旧 Key，提交时只能带上「原有摘要 + 新增 Key」，
     * 一旦对账就会把用户没动过的账号误删。
     * 删除账号只在明确动作里发生（详情页的「删除账号」或整个供应商删除）。
     *
     * 空表直接返回：新建时没填 Key 也合法（稍后到详情页添加）。
     */
    fun syncCustomAccounts(config: CustomProviderConfig) {
        if (config.apiKeys.isEmpty()) return
        val providerId = config.providerId
        val existing = accounts(providerId).map { it.uid }.toHashSet()
        for (key in config.apiKeys) {
            val uid = CustomProvider.uidOf(key)
            if (uid in existing) continue
            existing.add(uid)
            pool.upsert(ProviderAccount(providerId, uid, config.name, CustomProvider.secretOf(key)))
        }
    }

    // ------------------------------------------------------------------ 账号

    fun accounts(providerId: String? = null): List<AccountStatus> = pool.statuses(providerId)

    /** 取账号的原始凭证（供供应商专属 UI 组件做凭证级操作，如校正设备指纹）。 */
    fun account(providerId: String, uid: String): ProviderAccount? = pool.account(providerId, uid)

    /** 直接替换账号凭证（保留状态）。 */
    fun updateAccountSecret(providerId: String, uid: String, secret: String) {
        val account = pool.account(providerId, uid) ?: return
        pool.saveAccount(account.copy(secret = secret))
    }

    fun removeAccount(providerId: String, uid: String): Boolean {
        val removed = pool.remove(providerId, uid)
        if (removed) log("已删除账号：$providerId/$uid")
        return removed
    }

    fun setAccountEnabled(providerId: String, uid: String, enabled: Boolean) {
        pool.setEnabled(providerId, uid, enabled, reason = if (enabled) "" else "手动停用")
        log("${if (enabled) "启用" else "停用"}账号：$providerId/$uid")
    }

    /**
     * 对话成功后异步补刷一次该账号的额度。
     *
     * Trae 在流内回报余额（onBilling），其它供应商上游不回——对话扣了积分但池里
     * 还是上次手动刷新的旧值，看起来像「不消耗」。这里补一刷；同账号按时间窗节流。
     */
    fun refreshCreditsSoon(providerId: String, uid: String) {
        val key = "$providerId/$uid"
        val now = nowMillis()
        // 距上次不足最小间隔就直接跳过（连发对话时最多每 10s 补刷一次）。
        // 注意：判断必须用「读到的值」而不是当前值做 CAS 期望——
        // 原先的 replace(key, lastAutoCreditRefresh[key] ?: 0L, now) 期望值即当前值，
        // CAS 恒成功，节流形同虚设。
        val prev = lastAutoCreditRefresh[key]
        if (prev != null && now - prev < AUTO_CREDIT_REFRESH_MIN_INTERVAL_MS) return
        // 占坑式节流：同账号并发对话只放行一个，其余直接放弃（下一轮对话还会再试）。
        val acquired = if (prev == null) {
            lastAutoCreditRefresh.putIfAbsent(key, now) == null
        } else {
            lastAutoCreditRefresh.replace(key, prev, now)
        }
        if (acquired) creditRefresher.execute { runCatching { refreshCredits(providerId, uid) } }
    }

    fun refreshCredits(providerId: String, uid: String): CreditRefreshResult {
        val provider = registry.get(providerId)
            ?: return CreditRefreshResult(providerId, uid, "", 0, false, "未知供应商")
        val account = pool.account(providerId, uid)
            ?: return CreditRefreshResult(providerId, uid, "", 0, false, "账号不存在")
        val info: CreditInfo = try {
            // 额度刷新本身不保鲜：动作/对话成功后都会紧接着刷一次额度，这里再保鲜会让同一次动作多打一轮刷新端点，
            // 而「刷新额度」按钮所在的页面随后会走 creditPacks/pointsBills（它们会保鲜）。
            provider.creditInfo(account)
                ?: return CreditRefreshResult(providerId, uid, account.nickname, 0, false, "该供应商不支持额度刷新")
        } catch (e: Exception) {
            // 与「provider 自己报 known=false」保持一致：刷新失败不能把旧值继续当最新值展示
            logWarn("额度刷新异常（$providerId/${account.nickname.ifEmpty { uid }}）：${e.message}")
            val reason = e.message ?: "刷新额度失败"
            pool.updateCredits(providerId, uid, 0, false, reason)
            return CreditRefreshResult(providerId, uid, account.nickname, 0, false, reason)
        }
        pool.updateCredits(providerId, uid, info.balance, info.known, info.detail, info.actionHint)
        if (info.known) {
            log("额度刷新 $providerId/${account.nickname.ifEmpty { uid }}：${info.balance}（${info.detail}）")
        } else {
            logWarn("额度刷新失败（$providerId/${account.nickname.ifEmpty { uid }}）：${info.detail}")
        }
        return CreditRefreshResult(
            providerId, uid, account.nickname, info.balance, info.known,
            // 失败时优先给可操作指引：技术描述（含状态码/上游原文）对普通用户没用，
            // 他们需要的是「接下来做什么」。两者都放，指引在前、细节在后。
            when {
                info.known -> ""
                info.actionHint.isNotEmpty() -> info.actionHint
                else -> info.detail
            },
        )
    }

    /**
     * 刷新额度。
     *
     * [parallelism] 为并发度（默认 3）：逐个串行刷在多账号下慢得离谱，全部齐发又会同时打上游
     * 触发风控，3 是有界并发下的保守折中（见 [performActionAll]）。
     * [onProgress] 在每个账号刷完后于后台线程回调，供 UI 显示进度。
     */
    fun refreshAllCredits(
        providerId: String? = null,
        parallelism: Int = DEFAULT_BATCH_PARALLELISM,
        onProgress: ((BatchProgress) -> Unit)? = null,
    ): List<CreditRefreshResult> {
        val targets = pool.accounts(providerId).map { BatchTarget(it.providerId, it.uid) }
        return refreshCreditsBounded(targets, parallelism, onProgress)
    }

    /** 取某账号的额度包明细（会打上游，调用方自行控制频率）。 */
    fun creditPacks(providerId: String, uid: String): List<QuotaPack> {
        val provider = registry.get(providerId) ?: return emptyList()
        val account = pool.account(providerId, uid) ?: return emptyList()
        return try {
            provider.creditPacks(freshAccount(provider, account))
        } catch (e: Exception) {
            logWarn("拉取额度包失败（$providerId/$uid）：${e.message}")
            emptyList()
        }
    }

    /** 调整账号使用优先级（↑/↓）：排在前面的先用，同级仍按余额。 */
    fun moveAccountPriority(providerId: String, uid: String, up: Boolean): Boolean {
        val moved = pool.movePriority(providerId, uid, up)
        if (moved) onLog("账号优先级调整：$providerId/$uid ${if (up) "上移" else "下移"}")
        return moved
    }

    // ------------------------------------------------------------------ 账号导入导出

    /**
     * 导出账号（含明文凭证）。[selection] 是 `providerId/uid` 集合，空集合表示全部。
     *
     * 只取当前确实在池里的账号：勾选后又被删掉的不会凭空出现在导出内容里。
     */
    /**
     * 导出账号（含它们所属的自定义供应商定义与当前状态）。
     *
     * 这是一份**快照**：账号的凭证、手动启用开关、使用优先级，加上自定义供应商的
     * 标签/地址/协议/启用状态/模型列表，全都按当前的样子记下来；导入时原样恢复。
     *
     * **自定义供应商必须一起导出**：它们的标签/地址/模型存在
     * `settings/custom.json` 里，不在账号池里。只导账号的话，换到新设备后
     * 这些账号所属的供应商根本不存在，会被当成「本机未创建」整批跳过。
     */
    fun exportAccounts(selection: Set<String> = emptySet()): String {
        val all = pool.accounts()
        val picked = if (selection.isEmpty()) all else all.filter { "${it.providerId}/${it.uid}" in selection }
        // 连池里的状态一起快照：手动停用的号、↑/↓ 调过的优先级都要跟着走。
        // 只导凭证的话，一个被主动停用的号导入后会自己变成启用——悄悄改了用户的策略。
        val stateById = pool.statuses().associateBy { "${it.providerId}/${it.uid}" }
        val snapshots = picked
            .sortedWith(compareBy({ it.providerId }, { it.uid }))
            .map { account ->
                val status = stateById["${account.providerId}/${account.uid}"]
                AccountSnapshot(
                    account = account,
                    enabled = status?.enabled ?: true,
                    priority = status?.priority ?: 0,
                )
            }
        // 只带与导出账号相关的自定义供应商，不相关的不带
        val ownerProviderIds = picked.map { it.providerId }.filter { it.startsWith(CUSTOM_PREFIX) }.toSet()
        val providers = if (ownerProviderIds.isEmpty()) {
            emptyList()
        } else {
            settingsRepository.loadCustomProviders().filter { it.providerId in ownerProviderIds }
        }
        return AccountTransfer.encode(
            accounts = snapshots,
            exportedAt = nowMillis(),
            providers = providers,
        )
    }

    /**
     * 导入账号。文件的语义是**快照**，导入就是把它恢复回来。
     *
     * 供应商：文件里带了定义的，本机没有就建、已有就按文件盖回去（地址/协议/模型/启用状态）；
     * 没带定义的（v1 老文件）按标签建占位，不报「未创建」。
     *
     * 账号：[overwrite] 为 true 时同 (providerId, uid) 的已有账号被覆盖
     * （凭证、昵称、手动启用开关、使用优先级），否则跳过。
     * 未注册的 providerId 不落池，单独报出来——否则会存下一个永远选不了号的死账号。
     * [selection] 是 `providerId/uid` 集合，空集合表示导入文件里的全部账号。
     */
    fun importAccounts(
        json: String,
        overwrite: Boolean,
        selection: Set<String> = emptySet(),
    ): AccountImportResult {
        val envelope = AccountTransfer.decode(json)
        val picked = if (selection.isEmpty()) {
            envelope.accounts
        } else {
            envelope.accounts.filter { "${it.account.providerId}/${it.account.uid}" in selection }
        }
        // 导入即是创建 + 导入即是恢复。
        //
        // 文件里的自定义供应商定义是一份**快照**，按它决定本机怎么办：
        // 1. 本机没有 + 文件带定义 → 建出来（导入即创建），地址/模型/启用状态一并落地；
        // 2. 本机已有 + 文件带定义 → **按文件把状态盖回去**（导入即恢复）；
        // 3. 本机没有 + 文件没带（v1 老文件）→ 建一个「地址待填」的占位。
        //
        // 第 3 条是关键：用户的真实诉求就是「本机没创建，所以才要导入」。要是只在
        // 「文件带了定义」时才建，老文件就会变成「没创建 → 导不进去 → 永远创建不了」的死循环。
        val createdProviders = ArrayList<String>()
        val restoredProviders = ArrayList<String>()
        val placeholderProviders = ArrayList<String>()
        run {
            val existingConfigs = settingsRepository.loadCustomProviders()
            val byKey = existingConfigs.associateBy { it.key.lowercase() }
            val declaredByKey = envelope.providers.associateBy { it.key.lowercase() }

            // 只处理本次真正要导入的账号用到的标签：
            // 用户没勾的账号，不该顺手在他机器上多出一堆 / 改掉一堆供应商。
            val wantedKeys = picked
                .mapNotNull { snapshot ->
                    snapshot.account.providerId
                        .takeIf { it.startsWith(CUSTOM_PREFIX) }
                        ?.removePrefix(CUSTOM_PREFIX)
                }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }

            val merged = existingConfigs.toMutableList()
            for (key in wantedKeys) {
                val lower = key.lowercase()
                val declared = declaredByKey[lower]
                val existing = byKey[lower]
                // 地址空着的单独标出来：旧文件没存过地址，得让用户补一次
                fun markPlaceholder(provider: CustomProviderConfig) {
                    if (provider.baseUrl.isBlank()) placeholderProviders.add(provider.key)
                }
                when {
                    declared != null && existing == null -> {
                        merged.add(declared)
                        createdProviders.add(declared.key)
                        markPlaceholder(declared)
                    }

                    declared != null && existing != null -> {
                        // 按快照恢复。key 保持本机的写法（它已经绑在 providerId 上了）；
                        // apiKeys 是本机「待同步」的载体，文件里不带，原样留着。
                        val restored = existing.copy(
                            name = declared.name,
                            baseUrl = declared.baseUrl,
                            protocol = declared.protocol,
                            models = declared.models,
                            enabled = declared.enabled,
                        )
                        if (restored != existing) {
                            val index = merged.indexOfFirst { it.key.equals(existing.key, ignoreCase = true) }
                            if (index >= 0) merged[index] = restored
                            restoredProviders.add(restored.key)
                        }
                        markPlaceholder(restored)
                    }

                    existing == null -> {
                        // 文件里没带定义（v1 老文件）：只能按标签建占位，地址留给用户补。
                        // 不编造一个假地址——那样看着能用、实际必错。
                        val placeholder = CustomProviderConfig(
                            key = key,
                            name = key,
                            baseUrl = "",
                            apiKeys = emptyList(),
                            models = emptyList(),
                        )
                        merged.add(placeholder)
                        createdProviders.add(placeholder.key)
                        placeholderProviders.add(placeholder.key)
                    }

                    // 本机已有、文件里却没带定义：旧文件没这个信息，不动本机配置
                    else -> Unit
                }
            }

            if (createdProviders.isNotEmpty() || restoredProviders.isNotEmpty()) {
                settingsRepository.saveCustomProviders(merged)
                reloadCustomProviders()
            }
        }

        val added = ArrayList<String>()
        val updated = ArrayList<String>()
        val skipped = ArrayList<String>()
        val unknown = ArrayList<String>()
        for (snapshot in picked) {
            val account = snapshot.account
            val key = "${account.providerId}/${account.uid}"
            // 自定义供应商的 providerId 是 `custom:<key>`，而它是否已注册取决于
            // 这份配置有没有被 reloadCustomProviders() 加载过。
            // 导入前先按需补登一次，否则会把整批自定义账号误判成「未知供应商」。
            if (account.providerId.startsWith(CUSTOM_PREFIX) && registry.get(account.providerId) == null) {
                val configKey = account.providerId.removePrefix(CUSTOM_PREFIX)
                val config = settingsRepository.loadCustomProviders()
                    .firstOrNull { it.key.equals(configKey, ignoreCase = true) }
                if (config != null) {
                    registry.register(
                        dev.aigw.core.provider.custom.CustomProvider(config = config, nowMillis = nowMillis),
                    )
                }
            }
            if (registry.get(account.providerId) == null) {
                unknown.add(key)
                continue
            }
            val existing = pool.account(account.providerId, account.uid)
            when {
                existing == null -> {
                    pool.upsert(account)
                    restoreAccountState(snapshot)
                    added.add(key)
                }
                overwrite -> {
                    pool.upsert(account)
                    restoreAccountState(snapshot)
                    updated.add(key)
                }
                else -> skipped.add(key)
            }
        }
        if (added.isNotEmpty() || updated.isNotEmpty()) {
            onLog(
                "账号导入：新增 ${added.size}、覆盖 ${updated.size}、跳过 ${skipped.size}" +
                    if (createdProviders.isNotEmpty()) "、新建供应商 ${createdProviders.size}" else "" +
                    if (restoredProviders.isNotEmpty()) "、恢复供应商 ${restoredProviders.size}" else "",
            )
            // 自定义供应商的账号落池后，provider 实例里的账号快照必须重建，
            // 否则界面/转发拿到的还是旧实例（看不到刚导入的 Key）。
            if (picked.any { it.account.providerId.startsWith(CUSTOM_PREFIX) }) {
                reloadCustomProviders()
            }
        }
        return AccountImportResult(
            added = added,
            updated = updated,
            skipped = skipped,
            unknownProviders = unknown,
            createdProviders = createdProviders,
            placeholderProviders = placeholderProviders,
            restoredProviders = restoredProviders,
        )
    }

    /**
     * 把快照里的账号状态写回池里（手动启用开关 + 使用优先级）。
     *
     * 凭证由 [AccountPool.upsert] 落好，这里只补状态。不恢复**运行时探测**出的状态
     * （凭证失效的硬禁用、余额），那些导入后会重新探一遍——存下来只会是过期的假象。
     */
    private fun restoreAccountState(snapshot: AccountSnapshot) {
        val account = snapshot.account
        pool.setEnabled(
            account.providerId,
            account.uid,
            snapshot.enabled,
            reason = if (snapshot.enabled) "" else "手动停用",
        )
        pool.setPriority(account.providerId, account.uid, snapshot.priority)
    }

    /** 解析导出内容（只读、不落池），供导入前预览与勾选。 */
    fun parseAccountTransfer(json: String): List<ProviderAccount> =
        AccountTransfer.decode(json).accounts.map { it.account }

    /**
     * 修正历史遗留的账号标识（uid 口径变过时自愈）。启动后调用一次，幂等。
     *
     * 逐账号问 provider 是否需要迁移（[Provider.migrateAccount]）：uid 变了就按新 key 重新登记、
     * 删掉旧条目，凭证与账号状态（启用开关、额度）一并跟着走。provider 未实现迁移时直接跳过。
     *
     * @return 实际迁移的账号数。
     */
    fun migrateAccounts(): Int {
        var moved = 0
        for (account in pool.accounts()) {
            val provider = registry.get(account.providerId) ?: continue
            val migrated = try {
                provider.migrateAccount(account)
            } catch (e: Exception) {
                logWarn("账号标识迁移失败（${account.providerId}/${account.uid}）：${e.message}")
                null
            } ?: continue
            if (migrated.uid == account.uid) continue
            if (pool.renameAccount(account.providerId, account.uid, migrated)) {
                onLog("账号标识已迁移：${account.providerId}/${account.uid} → ${migrated.uid}")
                moved++
            }
        }
        return moved
    }

    /**
     * 取某账号的成长任务明细（会打上游，调用方自行控制频率）。
     *
     * 只读快照，供任务中心展示「哪些任务做了、哪些没做」；不支持任务中心的供应商返回 null。
     */
    fun taskList(providerId: String, uid: String): ProviderTaskListView? {
        val provider = registry.get(providerId) ?: return null
        val account = pool.account(providerId, uid) ?: return null
        return try {
            provider.taskList(freshAccount(provider, account))
        } catch (e: Exception) {
            logWarn("拉取任务列表失败（$providerId/$uid）：${e.message}")
            ProviderTaskListView(emptyList(), error = e.message ?: "拉取任务列表失败")
        }
    }

    /**
     * 取某账号的积分流水明细（会打上游，调用方自行控制频率）。
     *
     * 只读快照；不支持账本的供应商返回 null。
     */
    fun pointsBills(providerId: String, uid: String): PointsBillListView? {
        val provider = registry.get(providerId) ?: return null
        val account = pool.account(providerId, uid) ?: return null
        return try {
            provider.pointsBills(freshAccount(provider, account))
        } catch (e: Exception) {
            logWarn("拉取积分明细失败（$providerId/$uid）：${e.message}")
            PointsBillListView(emptyList(), error = e.message ?: "拉取积分明细失败")
        }
    }

    /** 执行供应商专属动作（签到、兑换码等）；成功后顺带刷新额度。 */
    fun performAction(
        providerId: String,
        uid: String,
        action: String,
        payload: JsonObject = JsonObject(),
    ): ProviderActionResult {
        val result = performActionRaw(providerId, uid, action, payload)
        // 单账号动作：成功后立刻刷一次该账号额度，用户点一下就能看到最新数字。
        // 批量路径不走这里（见 performActionAll），否则 N 个账号会多打 N 次额度接口。
        if (result.ok) refreshCredits(providerId, uid)
        return result
    }

    /** 无参数动作的便捷重载（调用方不必依赖 JsonObject）。 */
    fun performAction(providerId: String, uid: String, action: String): ProviderActionResult =
        performAction(providerId, uid, action, JsonObject())

    /**
     * 批量执行同一动作（签到 / 任务等），**不**在动作成功后逐个补刷额度。
     *
     * 与逐个调用 [performAction] 的区别：
     * - 有界并发（默认并发 [DEFAULT_BATCH_PARALLELISM]=3）：多账号串行太慢，齐发又会被风控；
     * - 单账号失败不影响其它账号，结果逐条结构化返回（调用方才能「成功 N / 失败 M + 逐条原因」地展示）；
     * - **动作阶段不刷额度**：原先每个账号动作成功后立刻补刷，等于每个账号都要先排队等它刷完
     *   （串行的两倍耗时）；改成动作全部完成后统一刷一轮（见下方 [refreshCreditsBounded]），
     *   并把这一轮也并发起来。上游 creditInfo 天然是每账号一次，所以能省下的是重复时延与
     *   「已签到的账号不重复请求」——签到成功才补刷，失败账号不再白打一次上游。
     *
     * [targets] 会按 (providerId, uid) 去重，保证每个账号最多执行一次动作。
     * [onProgress] 在每个账号完成后于后台线程回调（已完成数 / 刚完成的账号），供 UI 显示进度；
     * 回调里不要直接碰 UI，调用方自行切线程。
     */
    fun performActionAll(
        targets: List<BatchTarget>,
        action: String,
        payload: JsonObject = JsonObject(),
        parallelism: Int = DEFAULT_BATCH_PARALLELISM,
        onProgress: ((BatchProgress) -> Unit)? = null,
    ): List<BatchActionResult> {
        // 去重：同一账号最多执行一次动作，避免调用方传入重复目标时重复签到。
        val ordered = targets.distinctBy { it.providerId to it.uid }
        if (ordered.isEmpty()) return emptyList()

        val results = runBounded(ordered, parallelism, onDone = { done, index ->
            if (onProgress != null) {
                val target = ordered[index]
                val nickname = pool.account(target.providerId, target.uid)?.nickname ?: ""
                onProgress(BatchProgress(done, ordered.size, target.providerId, target.uid, nickname))
            }
        }) { target ->
            // 单个账号抛异常也不能拖垮整批：就地转成失败结果。
            val outcome = runCatching { performActionRaw(target.providerId, target.uid, action, payload) }
            val nickname = pool.account(target.providerId, target.uid)?.nickname ?: ""
            outcome.fold(
                { result ->
                    // 「该供应商不支持」不是执行失败，而是选目标时就应该排掉的情况：
                    // 计入失败面板会让用户看到一堆无意义的报错，所以单独标出来（ok = true），
                    // 不计入失败数也不进补刷额度。
                    if (result.unsupported) {
                        BatchActionResult(target.providerId, target.uid, nickname, true, "")
                    } else {
                        BatchActionResult(target.providerId, target.uid, nickname, result.ok, result.message)
                    }
                },
                { BatchActionResult(target.providerId, target.uid, nickname, false, it.message ?: "操作失败") },
            )
        }

        // 批量结束后统一刷一次额度，替掉原先「每个账号动作成功后立刻补刷」的逐账号额外请求。
        // 只刷动作成功、余额可能已变的账号：失败的账号余额没动，再打一次上游只会徒增噪音与风控。
        val succeeded = results.filter { it.ok }.map { BatchTarget(it.providerId, it.uid) }
        if (succeeded.isNotEmpty()) refreshCreditsBounded(succeeded, parallelism)
        return results
    }

    /**
     * 这个账号是否适用该批量动作（供应商自报，默认适用）。
     *
     * 供批量选目标时调用：区域型供应商的国际版、企业账号本就没有签到/成长中心，
     * 应在选目标时排掉，而不是批量跑完再逐条报错。
     */
    fun supportsAction(providerId: String, uid: String, action: String): Boolean {
        val provider = registry.get(providerId) ?: return false
        val account = pool.account(providerId, uid) ?: return false
        return provider.supportsAction(account, action)
    }

    /** 批量动作的底层执行：不做额度补刷，供 [performAction] 与 [performActionAll] 共用。 */
    private fun performActionRaw(
        providerId: String,
        uid: String,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult {
        val provider = registry.get(providerId) ?: return ProviderActionResult.failure("未知供应商")
        val stored = pool.account(providerId, uid) ?: return ProviderActionResult.failure("账号不存在")
        val account = freshAccount(provider, stored)
        return provider.performAction(account, action, payload)
    }

    /**
     * 非对话路径（动作 / 模型目录）的凭证保鲜：临期则刷新并回存账号池，返回实际该用的账号。
     *
     * 与对话路径（[GatewayHttpServer] 自带换号重试语义）不同：这里保鲜失败**不能**中断操作，
     * 刷新返回 null 或抛异常都静默降级为原账号，让动作照原凭证执行——把「临期」当「可用」的
     * 代价远小于因保鲜失败直接拒绝用户操作。
     */
    private fun freshAccount(provider: Provider, account: ProviderAccount): ProviderAccount {
        return try {
            val refreshed = provider.refreshAccount(account, settings.refreshSkewSeconds)
            if (refreshed != null) {
                pool.saveAccount(refreshed)
                refreshed
            } else {
                account
            }
        } catch (_: Exception) {
            account
        }
    }

    /** 有界并发刷新一批账号的额度；结果顺序与 [targets] 一致（与串行版可比）。 */
    private fun refreshCreditsBounded(
        targets: List<BatchTarget>,
        parallelism: Int,
        onProgress: ((BatchProgress) -> Unit)? = null,
    ): List<CreditRefreshResult> = runBounded(
        targets,
        parallelism,
        onDone = { done, index ->
            if (onProgress != null) {
                val target = targets[index]
                val nickname = pool.account(target.providerId, target.uid)?.nickname ?: ""
                onProgress(BatchProgress(done, targets.size, target.providerId, target.uid, nickname))
            }
        },
    ) { target ->
        refreshCredits(target.providerId, target.uid)
    }

    /**
     * 有界并发跑一批任务，结果按 [targets] 下标存放（返回顺序与入参一致）。
     *
     * 不复用 [ioPool]：它是 0~8 线程 + SynchronousQueue + CallerRunsPolicy，
     * 队列满时会把任务丢回调用线程执行，并发度既保证不了、上限也不可控；
     * 这里每次批量调用新建一个固定大小线程池，跑完即销毁，天然把并发钉在 [parallelism] 以内。
     * 批量动作是低频操作，线程创建开销可忽略。
     */
    private fun <R> runBounded(
        targets: List<BatchTarget>,
        parallelism: Int,
        onDone: ((done: Int, index: Int) -> Unit)? = null,
        task: (BatchTarget) -> R,
    ): List<R> {
        if (targets.isEmpty()) return emptyList()
        val poolSize = parallelism.coerceIn(1, MAX_BATCH_PARALLELISM)
        val results = AtomicReferenceArray<R>(targets.size)
        val finished = AtomicInteger(0)
        val executor = ThreadPoolExecutor(
            poolSize, poolSize, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(),
            { runnable -> Thread(runnable, "aigw-batch").apply { isDaemon = true } },
        )
        try {
            val futures = targets.mapIndexed { index, target ->
                executor.submit(Runnable {
                    try {
                        results.set(index, task(target))
                    } finally {
                        // 任务抛异常也要走完：否则进度少一拍、结果槽留 null，
                        // 下游拿到的 List<R> 会含 null（类型上不可见，运行时 NPE）。
                        onDone?.invoke(finished.incrementAndGet(), index)
                    }
                })
            }
            // 等全部完成；异常已被上面的 finally 消化（结果槽保持 null，下游需容忍）
            futures.forEach { runCatching { it.get() } }
        } finally {
            executor.shutdownNow()
        }
        return (0 until targets.size).map { index -> results.get(index) }
    }

    // ------------------------------------------------------------------ 登录

    /**
     * 打开系统浏览器完成登录，返回要打开的 URL。
     *
     * - **Trae**：临时监听 51120 接住 `/authorize` 回调；
     * - **Antigravity**：监听 51121（Google 客户端注册的 redirect_uri 固定为此端口）；
     * - **WorkBuddy**：没有本地回调，返回授权页 URL 并附上轮询用的 state。
     *
     * 用系统浏览器而不是内置 WebView，是为了绕开设备 WebView 的渲染异常；
     * 登录期间会临时占用一个本地端口，收到回调或超时后自动释放。
     */
    fun beginBrowserLogin(providerId: String, region: String = ""): BrowserLoginTicket {
        stopCallbackServer()
        val provider = registry.get(providerId) ?: throw IllegalStateException("未知供应商：$providerId")
        // 区域优先用调用方显式传入的：供应商设置是异步写入的，登录页与之后的轮询必须用同一区域，
        // 否则会在 A 站点创建 state、却去 B 站点轮询
        val loginRegion = region.ifEmpty { providerSettings(providerId).option("region", "") }
        return when {
            provider is WebLoginSupport && (providerId == TraeProvider.ID || providerId == RaccoonProvider.ID) -> {
                val (port, path) = if (providerId == RaccoonProvider.ID) {
                    RACCOON_CALLBACK_PORT to RACCOON_CALLBACK_PATH
                } else {
                    TRAE_CALLBACK_PORT to TRAE_CALLBACK_PATH
                }
                val callbackUrl = "http://127.0.0.1:$port$path"
                val ticket = provider.beginWebLogin(callbackUrl, loginRegion)
                startCallbackServer(port, path) { url ->
                    val outcome = completeWebLogin(providerId, url)
                    resultPage(outcome.ok, outcome.nickname.ifEmpty { outcome.uid }, outcome.error)
                }
                BrowserLoginTicket(ticket.loginUrl, "", loginRegion)
            }

            provider is LoopbackOAuthSupport -> {
                startCallbackServer(AntigravityProvider.CALLBACK_PORT, AntigravityProvider.CALLBACK_PATH) { url ->
                    val outcome = completeOAuth(providerId, url)
                    resultPage(outcome.ok, outcome.nickname.ifEmpty { outcome.uid }, outcome.error)
                }
                BrowserLoginTicket(provider.buildAuthUrl())
            }

            provider is DeviceCodeSupport -> {
                // 区域感知的登录端点（WorkBuddy：cn=copilot.tencent.com / global=workbuddy.ai）
                val ticket = provider.startDeviceAuth(loginRegion)
                BrowserLoginTicket(ticket.loginUrl, ticket.state, loginRegion)
            }

            else -> throw IllegalStateException("该供应商不支持浏览器登录")
        }
    }

    private fun startCallbackServer(port: Int, path: String, handler: (String) -> String) {
        val server = try {
            LoopbackCallbackServer(port, path) { url ->
                val html = handler(url)
                // 先让响应写完再关端口，否则浏览器会看到连接被重置
                stopCallbackServerSoon()
                html
            }.also { it.startServer() }
        } catch (e: Exception) {
            throw IllegalStateException("无法监听本地回调端口 $port：${e.message}")
        }
        callbackServer = server
        log("已监听本地回调 http://127.0.0.1:$port$path")
        // 用户长时间不完成登录时释放端口，避免一直占着
        Thread {
            runCatching { Thread.sleep(CALLBACK_TIMEOUT_MS) }
            if (callbackServer === server) {
                callbackServer = null
                runCatching { server.stop() }
                log("浏览器登录超时，已释放回调端口 $port")
            }
        }.start()
    }

    private fun stopCallbackServerSoon() {
        val server = callbackServer ?: return
        callbackServer = null
        Thread {
            runCatching { Thread.sleep(1_500) }
            runCatching { server.stop() }
        }.start()
    }

    /** 释放登录回调端口（登录完成或用户取消时调用）。 */
    fun stopCallbackServer() {
        val server = callbackServer ?: return
        callbackServer = null
        runCatching { server.stop() }
    }

    fun beginWebLogin(providerId: String, callbackUrl: String, region: String = ""): WebLoginTicket {
        val support = registry.get(providerId) as? WebLoginSupport
            ?: throw IllegalStateException("该供应商不支持网页登录")
        return support.beginWebLogin(callbackUrl, region)
    }

    fun completeWebLogin(providerId: String, callbackUrl: String): LoginOutcome {
        val support = registry.get(providerId) as? WebLoginSupport
            ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持网页登录")
        return try {
            val account = support.completeWebLogin(callbackUrl)
            adopt(providerId, account, "登录")
        } catch (e: Exception) {
            logWarn("登录失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "登录失败")
        }
    }

    /** [ccode] 为国家码（不带 `+`）；非大陆号码必须传对，否则上游短信通道会拒发。 */
    fun sendSmsCode(providerId: String, phone: String, ccode: String = SmsLoginSupport.DEFAULT_CCODE): String {
        val support = registry.get(providerId) as? SmsLoginSupport
            ?: throw IllegalStateException("该供应商不支持短信登录")
        return support.sendSmsCode(phone, ccode)
    }

    fun completeSmsLogin(
        providerId: String,
        phone: String,
        code: String,
        msgid: String,
        ccode: String = SmsLoginSupport.DEFAULT_CCODE,
    ): LoginOutcome {
        val support = registry.get(providerId) as? SmsLoginSupport
            ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持短信登录")
        return try {
            adopt(providerId, support.loginBySmsCode(phone, code, msgid, ccode), "登录")
        } catch (e: Exception) {
            logWarn("登录失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "登录失败")
        }
    }

    fun startDeviceAuth(providerId: String, region: String = ""): DeviceAuthTicket {
        val support = registry.get(providerId) as? DeviceCodeSupport
            ?: throw IllegalStateException("该供应商不支持设备授权登录")
        return support.startDeviceAuth(region)
    }

    fun pollDeviceAuth(providerId: String, state: String, region: String = ""): DeviceAuthPoll {
        val support = registry.get(providerId) as? DeviceCodeSupport
            ?: return DeviceAuthPoll.Failed("该供应商不支持设备授权登录")
        val poll = support.pollDeviceAuth(state, region)
        if (poll is DeviceAuthPoll.Success) {
            adopt(providerId, poll.account, "登录")
        } else if (poll is DeviceAuthPoll.Failed) {
            // 授权页那边成功了、这里却失败时，先看这条日志确认轮询打的是不是同一个区域
            logWarn("设备授权轮询失败（$providerId，region=${region.ifEmpty { "默认" }}）：${poll.error}")
        }
        return poll
    }

    fun completeOAuth(providerId: String, callbackUrl: String): LoginOutcome {
        val support = registry.get(providerId) as? LoopbackOAuthSupport
            ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持 OAuth 登录")
        val code = dev.aigw.core.provider.queryParam(callbackUrl, "code")
            ?: return LoginOutcome(LoginState.FAILED, error = "回调里没有 code 参数")
        return try {
            adopt(providerId, support.exchangeCode(code), "登录")
        } catch (e: Exception) {
            logWarn("OAuth 登录失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "登录失败")
        }
    }

    /** 粘贴 JSON 凭证导入（各供应商自行解析）。 */
    fun importAccountJson(providerId: String, raw: String): LoginOutcome {
        val provider = registry.get(providerId)
            ?: return LoginOutcome(LoginState.FAILED, error = "未知供应商")
        return try {
            val account = provider.importCredentials(raw)
                ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持粘贴导入")
            adopt(providerId, account, "导入")
        } catch (e: Exception) {
            logWarn("导入失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "导入失败")
        }
    }

    /** 直接把账号写入账号池（自定义供应商手动添加 key 时用）。 */
    fun addAccount(providerId: String, uid: String, nickname: String, secret: String): LoginOutcome {
        if (registry.get(providerId) == null) return LoginOutcome(LoginState.FAILED, error = "未知供应商")
        return adopt(providerId, ProviderAccount(providerId, uid, nickname, secret), "添加")
    }

    private fun adopt(providerId: String, account: ProviderAccount, verb: String): LoginOutcome {
        pool.upsert(account)
        refreshCredits(providerId, account.uid)
        log("$verb 成功：${account.nickname.ifEmpty { account.uid }}（$providerId）")
        onAccountsChanged?.invoke()
        claimDesktopLoginRewardSoon(providerId, account.uid)
        return LoginOutcome(LoginState.SUCCESS, account.uid, account.nickname)
    }

    /**
     * 账号加入后自动领取小浣熊的「桌面端登录奖励」。
     *
     * 该积分不是「点一下加积分」的签到，而是服务端对官方桌面客户端登录发放的奖励；
     * 因此领取请求必须带桌面客户端标识（见 RaccoonProvider 的 desktopHeaders），
     * 不主动触发就只能等用户自己去任务中心点。异步执行：失败只记日志，不影响登录结果。
     */
    private fun claimDesktopLoginRewardSoon(providerId: String, uid: String) {
        if (providerId != dev.aigw.core.provider.raccoon.RaccoonProvider.ID) return
        ioExecutor().execute {
            val nickname = pool.account(providerId, uid)?.nickname ?: uid
            runCatching { performAction(providerId, uid, ACTION_CHECKIN) }
                .onSuccess { result ->
                    if (result.ok) {
                        log("已自动领取桌面端登录奖励（$nickname）：${result.message}")
                    } else {
                        logWarn("桌面端登录奖励自动领取未成功（$nickname）：${result.message}")
                    }
                }
                .onFailure { logWarn("桌面端登录奖励自动领取异常（$nickname）：${it.message}") }
        }
    }

    // ------------------------------------------------------------------ 模型

    /**
     * 流式拉取全部启用供应商的模型目录：每个供应商一就绪立即回调 [onChunk]，
     * 不等最慢的供应商（整体耗时从「最慢者」降到「最快者」即可见）。
     *
     * 回调在 ioPool 后台线程发出（多个供应商并发、顺序不保证），回调里不要碰 UI，
     * 调用方自行保证线程安全。返回值在全部完成后给出聚合目录与每个失败供应商的原因。
     *
     * [models] 等价于本函数丢弃回调、只取最终聚合的阻塞版本。
     *
     * [forceRefresh] 为 true 时先丢弃各供应商的模型目录缓存（部分供应商带 TTL 缓存，
     * 否则刷新按钮会命中缓存、远端已下架的模型仍滞留），保证本次真正重打上游。
     */
    fun modelsStreaming(forceRefresh: Boolean = false, onChunk: (ProviderModelsChunk) -> Unit): ModelsCatalog {
        val enabledProviders = registry.all().filter { providerSettings(it.id).enabled }
        if (enabledProviders.isEmpty()) return ModelsCatalog(emptyList())

        // 刷新按钮要求忽略 TTL 缓存：在提交拉取任务前失效，保证任务里拿到的是新鲜上游结果。
        if (forceRefresh) {
            enabledProviders.forEach { it.invalidateModelCache() }
        }

        val futures = enabledProviders.map { provider ->
            provider.id to ioPool.submit<ProviderModelsChunk> {
                val chunk = fetchProviderModels(provider)
                onChunk(chunk)
                chunk
            }
        }

        val aggregated = ArrayList<RoutedModel>()
        val failures = LinkedHashMap<String, String>()
        for ((providerId, future) in futures) {
            val chunk = try {
                // 各 Provider 的 READ_TIMEOUT 长达 300s，/v1/models 阻塞版不能无限等，
                // 超时后取消该任务并按该供应商拉取失败处理。
                future.get(MODELS_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                future.cancel(true)
                logWarn("拉取模型目录超时（$providerId，${MODELS_FETCH_TIMEOUT_SECONDS}s）")
                failures[providerId] = "拉取模型目录超时"
                continue
            } catch (_: Exception) {
                continue
            }
            aggregated.addAll(chunk.models)
            chunk.error?.let { failures[chunk.providerId] = it }
        }
        return ModelsCatalog(aggregated, failures)
    }

    /** 阻塞聚合版：HTTP `/v1/models`、CLI 等需要一次性完整列表的场景使用。 */
    fun models(): List<RoutedModel> = modelsStreaming { }.models

    /**
     * 拉取单个供应商的模型目录（id 带 `provider/` 前缀；区域型供应商按区域拆成多组）。
     *
     * 区域型供应商（见 [dev.aigw.core.provider.RegionAwareSupport]）必须**按区域各拉一次**：
     * 国内与国际是两套不同的上游站点、两份不同的模型清单，只用一个账号拉一次再复制给所有区域
     * 会把国内清单冒充成「国际版模型」。该区域没有可用账号时不为它生成任何条目——没有账号支撑的分组
     * 点进去必然调用失败，列出来只会误导用户。
     */
    private fun fetchProviderModels(provider: Provider): ProviderModelsChunk {
        return try {
            val regionAware = provider as? dev.aigw.core.provider.RegionAwareSupport
                ?: return fetchWholeProviderModels(provider)
            fetchRegionalModels(provider, regionAware)
        } catch (e: Exception) {
            logWarn("拉取模型目录失败（${provider.id}）：${e.message}")
            ProviderModelsChunk(provider.id, emptyList(), e.message ?: "拉取失败")
        }
    }

    /**
     * 区域型供应商的目录拉取：每个区域各用本区域账号拉一次，**并行**执行。
     *
     * 串行会把两个区域的耗时相加，容易顶穿 [MODELS_FETCH_TIMEOUT_SECONDS] 的聚合超时
     * （目录响应带内嵌图标、可能上百 KB，慢的时候单区域就要十几秒）。
     * 某区域没有可用账号就不为该区域生成分组。
     */
    private fun fetchRegionalModels(
        provider: Provider,
        regionAware: dev.aigw.core.provider.RegionAwareSupport,
    ): ProviderModelsChunk {
        val views = ConcurrentHashMap<String, ProviderModelCatalogView>()
        val failures = ConcurrentHashMap<String, String>()
        val started = ArrayList<String>()
        val jobs = ArrayList<Thread>()

        for (region in regionAware.regions()) {
            val picked = pickAccount(provider.id, region = region) ?: continue
            started.add(region)
            jobs.add(
                Thread {
                    val startedAt = now()
                    try {
                        views[region] = fetchCatalog(provider, picked)
                    } catch (e: Exception) {
                        val message = e.message ?: "拉取失败"
                        failures[region] = message
                        logWarn("拉取模型目录失败（${provider.id}/$region，${now() - startedAt}ms）：$message")
                    }
                }.apply {
                    isDaemon = true
                    start()
                },
            )
        }

        // 各区域的 HTTP 请求自带读超时；这里再兜一层，避免个别实现把整轮拖过网关侧的聚合超时
        val deadline = now() + REGIONAL_FETCH_WAIT_MS
        for (job in jobs) {
            val remain = deadline - now()
            if (remain > 0) job.join(remain)
        }

        val models = ArrayList<RoutedModel>()
        var firstError: String? = null
        var anyFallback = false
        for (region in started) {
            val view = views[region]
            if (view == null) {
                firstError = firstError ?: failures[region] ?: "拉取超时"
                continue
            }
            val label = when (region) {
                "cn" -> "国内"
                "global" -> "国外"
                else -> region
            }
            if (view.fromFallback) anyFallback = true
            for (model in view.models) {
                if (settings.onlyUsableModels && provider.isInternalModel(model.id)) continue
                models.add(
                    RoutedModel(
                        provider.id,
                        "${provider.displayName}（$label）",
                        model,
                        routePrefix = chineseRoutePrefixOf(provider.id, region),
                        region = region,
                        cached = view.fromFallback,
                    ),
                )
            }
            if (view.error.isNotEmpty()) firstError = firstError ?: view.error
        }
        // 供应商自报错误只在拿不到任何模型、或这轮是回退的旧清单时才算失败；没有内置快照兜底后，
        // 无账号/拉取失败是否报红由各家 listModels 的 error 决定
        return ProviderModelsChunk(provider.id, models, if (models.isEmpty() || anyFallback) firstError else null)
    }

    /** 无区域概念的供应商：用池里挑出的那一个账号拉一份目录。 */
    private fun fetchWholeProviderModels(provider: Provider): ProviderModelsChunk {
        val view = fetchCatalog(provider, pool.pick(provider.id))
        val models = view.models
            .filterNot { settings.onlyUsableModels && provider.isInternalModel(it.id) }
            .map {
                RoutedModel(
                    provider.id,
                    provider.displayName,
                    it,
                    routePrefix = chineseRoutePrefixOf(provider.id, null),
                    cached = view.fromFallback,
                )
            }
        return ProviderModelsChunk(
            provider.id,
            models,
            if (models.isEmpty() || view.fromFallback) view.error.ifEmpty { null } else null,
        )
    }

    /** 用给定账号拉一份模型目录；凭证临期或已过期时先做一次安全的 refreshAccount 保鲜。 */
    private fun fetchCatalog(provider: Provider, picked: ProviderAccount?): ProviderModelCatalogView {
        val account = picked?.let { freshAccount(provider, it) }
        return provider.listModels(account)
    }

    /**
     * 把客户端传来的模型名解析成「哪个供应商 + 哪个模型」。
     *
     * 路由逻辑（按顺序，命中即返回）：
     * 1. 有前缀 → 查中文前缀表（腾讯国内 / 腾讯国外 / 字节国内 / 字节国外 / 谷歌 / 讯飞 / 商汤）；
     * 2. 没写标签 → 直接返回 null，由调用方报「模型必须带标签」。
     *
     * **这里没有任何猜测**：名称推断、目录归属查询、默认供应商、单供应商接管
     * 全部已删除。路由的唯一依据就是标签，标签唯一地绑定一家供应商。
     */
    fun resolveRoute(requested: String): Route? {
        var model = requested.trim()
        if (model.isEmpty()) return null

        // 剥离可能存在的 "models/" 前缀（Google/Gemini 客户端与 SDK 的常见标准命名）
        if (model.startsWith("models/", ignoreCase = true)) {
            model = model.substring(7).trim()
        }

        val slash = model.indexOf('/')
        // **必须带标签**。没有标签就不存在“猜一下该发给谁”这回事：
        // 网关不做目录归属查询、不做名称推断，直接判为无法路由。
        // 调用方据此报错，要求客户端把模型写成「标签/模型名」。
        if (slash <= 0) return null

        val rawLabel = model.substring(0, slash).trim()
        val subModel = model.substring(slash + 1).trim()
        if (subModel.isEmpty()) return null
        // 标签必须能解析到一个真实可用的供应商，否则直接作废。
        // 不允许“标签不认识就整串当模型名往下猜”——那会让一个写错的标签
        // 静悄悄跑到别家去，得到一个与真实原因无关的上游报错。
        return routeByLabel(rawLabel, subModel)
    }

    /**
     * 按「模型标签」路由：标签必须绑定到一个已启用、已登录的供应商。
     *
     * 校验顺序（任一不过就返回 null，由调用方报错）：
     * 1. 标签能在前缀表里找到对应供应商（内置中文标签 / 自定义供应商标签）；
     * 2. 该供应商已注册（`registry` 里存在）；
     * 3. 该供应商已启用且至少有一个账号——否则标签指向一个用不了的供应商，
     *    路由过去只会得到一个「没登录」的错，不如当场明确报出来。
     *
     * 标签只绑定自己那一家：`腾讯国内/xxx` 永远只可能去腾讯国内站，
     * 不会因为“这个模型不在腾讯”而跑到字节或者谷歌。这是主人明确要求的语义。
     */
    private fun routeByLabel(rawLabel: String, model: String): Route? {
        val providerId = normalizeProviderPrefix(rawLabel) ?: return null
        val provider = registry.get(providerId) ?: return null
        // 标签指向的供应商必须真的能用：未启用 / 没账号 → 标签作废
        if (!providerSettings(provider.id).enabled) return null
        if (pool.size(provider.id) <= 0) return null
        // 区域由标签后缀决定（腾讯国内 → cn）；非区域型供应商恒为 null
        return Route(provider.id, model, chineseRegionPrefix(rawLabel))
    }

    /**
     * 把路由前缀解析成区域约束。
     *
     * 两种形式都认：
     * - `codebuddy-cn` / `codebuddy-global`（模型页展示的区域后缀前缀）；
     * - `workbuddy-cn` / `workbuddy-global`。
     * 非区域型前缀（如 `trae`、`google`）返回 null。
     */
    /**
     * 在指定供应商中选号；[region] 非空时只选属于该区域且处于可用状态的账号中余额最高者。
     *
     * [skipEmptyCredits]（默认 false）：跳过「已知额度为 0」的账号。语义与
     * [AccountPool.pick] 完全一致：命中为空时自动退回不过滤的一轮，
     * 避免一份过时的 0 把真能用的账号永久挡在门外。
     */
    /**
     * 某供应商某区域的账号可用性快照（区分“手动停用”与“凭证失效”）。
     *
     * 供转发层在「选不出账号」时判断告警文案：
     * - 全部是手动停用 → 告诉用户去「供应商」页启动账号；
     * - 有凭证失效 → 告诉用户需要重新登录。
     */
    fun accountAvailability(providerId: String, region: String?): AccountPool.Availability {
        val regionAware = registry.get(providerId) as? dev.aigw.core.provider.RegionAwareSupport
        return pool.availability(
            providerId = providerId,
            region = region,
            regionOf = regionAware?.let { aware -> { account: ProviderAccount -> aware.regionOf(account) } },
        )
    }

    fun pickAccount(
        providerId: String,
        exclude: Set<String> = emptySet(),
        region: String? = null,
        skipEmptyCredits: Boolean = false,
    ): ProviderAccount? {
        if (region == null) return pool.pick(providerId, exclude, skipEmptyCredits)
        val regionAware = registry.get(providerId) as? dev.aigw.core.provider.RegionAwareSupport
            ?: return pool.pick(providerId, exclude, skipEmptyCredits)
        // 一次取状态快照（单次加锁），避免对每个账号各加一次读锁
        val statusByUid = pool.statuses(providerId).associateBy { it.uid }

        fun pickBest(ignoreCreditFilter: Boolean): ProviderAccount? = pool.accounts(providerId)
            .asSequence()
            .filter { account -> account.uid !in exclude }
            .filter { account -> statusByUid[account.uid]?.usable == true }
            .filter { account -> regionAware.regionOf(account) == region }
            .filter { account ->
                val st = statusByUid[account.uid]
                !(skipEmptyCredits && !ignoreCreditFilter &&
                    st != null && st.creditsKnown && st.credits <= 0L)
            }
            .maxByOrNull { account -> statusByUid[account.uid]?.credits ?: 0L }

        val filtered = pickBest(ignoreCreditFilter = false)
        if (filtered != null || !skipEmptyCredits) return filtered
        // 该区域全部账号都被「余额为 0」挡掉了：退回不过滤的一轮（余额可能是旧快照）
        return pickBest(ignoreCreditFilter = true)
    }

    /**
     * 解析请求模型的候选路由。
     *
     * **永远只返回一条**：路由由标签唯一决定，不存在跨供应商转移。
     * 保留这个函数只是为了兼容调用方签名（"候选列表"里只有首选）。
     */
    fun resolveCandidateRoutes(requested: String): List<Route> {
        val primary = resolveRoute(requested) ?: return emptyList()

        // ★ 跨供应商故障转移已**全部删除**（含用户显式配置的优先级表）。
        //
        // 主人要求「标签只绑定对应供应商」「选了哪家就路由到哪家」，
        // 那么任何会把请求改发到另一家的机制都与这条冲突：
        // - 自动找同款模型：写 `字节国内/xxx`，字节撞错后静静跑去谷歌；
        // - 用户配置的优先级表：同样会把请求从标签指定的那家偷走。
        //
        // 两种都已移除。现在候选永远只有首选一条，
        // 失败只能在**同一家**内换号重试（那层在转发层做）。
        return listOf(primary)
    }

    /**
     * 探测指定供应商模型的连通性与网络延迟（毫秒）。
     *
     * 发送极轻量的探针请求，耗时低于 5000ms 返回正整数，超时或连接失败返回 -1L。
     */
    fun probeModelLatency(providerId: String, modelId: String, region: String? = null): ProbeResult {
        val provider = registry.get(providerId)
            ?: return ProbeResult(-1L, "未知供应商 $providerId")
        val account = pickAccount(providerId, region = region)
            ?: return ProbeResult(-1L, "没有可用账号")
        val resolvedModel = provider.resolveModel(modelId)
        // 不指定 max_tokens：上游对 max_output_tokens 有下限（传 1 会被模型提供方
        // 以 11133 integer_below_min_value 拒绝），而探测只需要建连与首字节时间。
        val probeBody = JsonObject().apply {
            addProperty("model", resolvedModel)
            addProperty("stream", false)
            add("messages", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "ping")
                })
            })
        }.toString()

        val start = System.currentTimeMillis()
        val call = try {
            provider.openChat(account, probeBody)
        } catch (e: Exception) {
            val msg = e.message ?: "连接失败"
            logWarn("探测 $providerId/$resolvedModel 失败：$msg")
            return ProbeResult(-1L, msg)
        }

        try {
            if (call.status in 200..299 && call.failure == null) {
                return ProbeResult((System.currentTimeMillis() - start).coerceAtLeast(1L), "")
            }
            val reason = call.failure?.message ?: call.errorBody.ifEmpty { "HTTP ${call.status}" }
            logWarn("探测 $providerId/$resolvedModel 失败（HTTP ${call.status}）：${reason.take(300)}")
            return ProbeResult(-1L, "HTTP ${call.status}：${reason.take(300)}")
        } finally {
            call.close()
        }
    }

    /**
     * 路由前缀解析：**只认中文前缀**。
     *
     * 之前还接受 codebuddy/workbuddy/trae/google/loomy/raccoon 等英文别名，
     * 会和中文前缀并存出两套写法，容易「东一个英文、西一个中文」。
     * 现在统一成中文品牌名 + 中文区域后缀（腾讯国内 / 字节国外 …），
     * 英文前缀不再被识别——写了英文会落到「无前缀」路径由模型名/目录推断。
     *
     * 例外：`custom:` 前缀是自定义供应商的存储键，必须原样保留（它本身不是品牌名）。
     */
    private fun normalizeProviderPrefix(prefix: String): String? {
        // 只认品牌标签（腾讯国内 / 腾讯国外 / 字节国内 / 字节国外 / 谷歌 / 讯飞 / 商汤
        // + 自定义供应商自己的标签）。
        // ⚠️ 不能先查 registry.get()：它是按 provider id（英文）做的忽略大小写查找，
        // 一旦放在前面，`codebuddy/glm-5.2` 就会被当成合法前缀，英文前缀等于"偷偷还能用"。
        chineseRoutePrefix(prefix)?.let { return it }
        // 自定义供应商：标签就是用户起的那个名字，**不再加 `custom:` 前缀**。
        // 旧数据里的 `custom:<短名>` 写法仍然认，避免升级后老客户端的配置直接失效。
        val p = prefix.trim()
        val lower = p.lowercase()
        if (lower.startsWith(CUSTOM_PREFIX)) {
            return lower.removePrefix(CUSTOM_PREFIX).takeIf { it.isNotEmpty() }
        }
        // 不是内置标签：看它是不是某个自定义供应商的标签（大小写不敏感，取回真实 key）
        return customLabelOwner(p)
    }

    /**
     * 判断一个标签是不是某个自定义供应商的名字，是则返回它的 providerId（`custom:<key>`）。
     *
     * 自定义供应商的标签存在配置的 `key` 字段里（界面上叫「模型标签」）。
     * 比较时忽略大小写，返回真实的 key，保证 `MySite/xxx` 与 `mysite/xxx` 落到同一家。
     */
    private fun customLabelOwner(label: String): String? {
        val wanted = label.trim()
        if (wanted.isEmpty()) return null
        val config = settingsRepository.loadCustomProviders()
            .firstOrNull { it.key.equals(wanted, ignoreCase = true) } ?: return null
        return config.providerId
    }

    /**
     * 校验一个自定义供应商的标签能不能用。
     *
     * 规则（主人明确要求）：
     * 1. 不能为空，且不能带 `/`（标签本身就是前缀，带斜杠会直接搞乱解析）；
     * 2. **不能与内置标签同名**：`腾讯国内` / `字节国外` / `谷歌` 这些名字属于内置供应商，
     *    自定义供应商抢了同名标签，系统会真的把请求路由去内置的那家，然后发现没这个模型——
     *    这种「看似能用实际必错」的配置必须从源头禁掉；
     * 3. 不能与另一个自定义供应商重名（同标签绑定两家以上的供应商，路由结果会不确定）。
     *
     * 同标签只允许指向同一家供应商（这正是标签的意义），所以重名一律拒绝。
     *
     * @return null 表示通过；否则返回给用户看的中文原因。
     */
    fun validateCustomLabel(label: String, exceptKey: String? = null): String? {
        val key = label.trim()
        if (key.isEmpty()) return "模型标签不能为空"
        if (key.contains('/') || key.contains("\\")) return "模型标签不能包含斜杠（它就是前缀本身）"
        if (key.length > 32) return "模型标签最多 32 个字符"
        if (!key.matches(Regex("^[^\\s/\\\\]+$"))) return "模型标签不能包含空白字符"

        // 与内置供应商标签冲突
        if (chineseRoutePrefix(key) != null) {
            return "「$key」是内置供应商的标签，不能占用。请在「供应商」页直接登录该供应商"
        }
        // 与其它自定义供应商冲突
        val dup = settingsRepository.loadCustomProviders().firstOrNull {
            !it.key.equals(exceptKey.orEmpty(), ignoreCase = true) && it.key.equals(key, ignoreCase = true)
        }
        if (dup != null) return "「$key」已被另一个自定义供应商占用"
        return null
    }

    /**
     * 中文路由前缀 → 供应商 id。
     *
     * 区域型供应商（腾讯 / 字节）**只认带区域的写法**：上游模型列表里的标签本身就是
     * 「腾讯国内 / 腾讯国外 / 字节国内 / 字节国外」，不存在「腾讯」「字节」这种裸品牌标签，
     * 裸品牌前缀根本解析不出区域，属于无意义的路由，已删除。
     *
     * 小浣熊已全面改用「商汤」品牌名，`小浣熊` 别名一并删除，避免两套写法。
     *
     * 中文不做 lowercase（本身就是无大小写语言），但允许前后空白。
     */
    private fun chineseRoutePrefix(prefix: String): String? {
        val p = prefix.trim()
        if (p.isEmpty()) return null
        // **内置标签表就是唯一的权威表**：一个标签只对应一家供应商，不查、不猜、不回退。
        // 新增内置供应商时必须在这里加一条（这是故意的：标签是契约，不能隐式生成）。
        // 自定义供应商不走这里，它们走 customLabelOwner()。
        return when (p) {
            "腾讯国内" -> "codebuddy"
            "腾讯国外" -> "codebuddy"
            "字节国内" -> "trae"
            "字节国外" -> "trae"
            "谷歌" -> "antigravity"
            "讯飞" -> "loomy"
            "商汤" -> "raccoon"
            else -> null
        }
    }

    /**
     * 中文区域前缀对应的区域标识（cn/global）。
     *
     * 只处理中文写法的区域后缀；英文前缀（`trae-cn` 等）仍由 [regionPrefixOf] 判定。
     * 非中文区域前缀返回 null。
     */
    private fun chineseRegionPrefix(prefix: String): String? = when (prefix.trim()) {
        "腾讯国内", "字节国内" -> "cn"
        "腾讯国外", "字节国外" -> "global"
        else -> null
    }

    /**
     * 生成对外暴露的中文路由前缀（模型 id 的 `/` 前半部分）。
     *
     * `/v1/models` 与 App 模型页都从这里取前缀，所以界面与 API 看到的是同一套中文名。
     * 英文前缀在 [resolveRoute] 里继续作为别名可用，老客户端配置无需改动。
     *
     * [region] 非空时拼区域后缀（腾讯国内 / 腾讯国外），与 [chineseRegionPrefix] 互为逆运算。
     */
    /**
     * 对外暴露供应商的标签名（带区域后缀）——供告警/日志使用。
     *
     * 与 `/v1/models` 生成 routePrefix 用的是同一套逻辑，
     * 保证「界面显示的名字」与「客户端里要写的标签」永远一致。
     */
    fun routeLabel(providerId: String, region: String?): String = chineseRoutePrefixOf(providerId, region)

    private fun chineseRoutePrefixOf(providerId: String, region: String?): String {
        // 自定义供应商：对外标签就是用户起的那个名字（不再暴露 `custom:` 存储键）。
        // 找不到配置说明是脏数据（配置已删但模型缓存还在），退回存储键好让调用方看得出来。
        if (providerId.startsWith(CUSTOM_PREFIX)) {
            val key = providerId.removePrefix(CUSTOM_PREFIX)
            val label = settingsRepository.loadCustomProviders()
                .firstOrNull { it.key.equals(key, ignoreCase = true) }?.key
                ?: key
            return if (region == null) label else "$label-$region"
        }
        val brand = when (providerId) {
            "codebuddy" -> "腾讯"
            "trae" -> "字节"
            "antigravity" -> "谷歌"
            "loomy" -> "讯飞"
            "raccoon" -> "商汤"
            // 未知供应商沿用 id，不硬编中文
            else -> return if (region == null) providerId else "$providerId-$region"
        }
        if (region == null) return brand
        return brand + when (region) {
            "cn" -> "国内"
            "global" -> "国外"
            else -> region
        }
    }

    // ------------------------------------------------------------------ 数据管理

    /** 存储占用明细。 */
    fun storageReport(): StorageReport = StorageAudit.audit(store)

    /** 清理早于「保留天数」的记录；返回删除条数。 */
    fun purgeExpiredRecords(): Int {
        val cutoff = nowMillis() - settings.logRetentionDays.toLong() * DAY_MILLIS
        val removed = callLogStore.purgeOlderThan(cutoff)
        if (removed > 0) log("按保留 ${settings.logRetentionDays} 天清理了 $removed 条过期调用记录")
        return removed
    }

    /** 服务运行时调一次：跨天则按保留天数自动清理，保证长时间挂机不会无限增重。 */
    fun autoPurgeIfDue() {
        val today = nowMillis() / DAY_MILLIS
        if (lastAutoPurgeDay == today) return
        lastAutoPurgeDay = today
        purgeExpiredRecords()
    }

    // ------------------------------------------------------------------ 日志

    internal fun log(text: String) {
        requestLog.info(text)
        onLog(text)
    }

    internal fun logWarn(text: String) {
        requestLog.warn(text)
        onLog(text)
    }

    internal fun logError(text: String) {
        requestLog.error(text)
        onLog(text)
    }

    /** 详细日志开关（全链路请求/转发/发送/返回原文）。 */
    fun verboseLogging(): Boolean = settings.verboseLogging

    /** 详细日志：分多段写入请求日志，避免单条过长被环形缓冲截断。 */
    internal fun logVerbose(tag: String, text: String) {
        if (!settings.verboseLogging) return
        val limit = VERBOSE_LINE_LIMIT
        var start = 0
        var part = 0
        while (start < text.length) {
            val end = minOf(start + limit, text.length)
            requestLog.info("[${tag}] ${if (part == 0) "" else "(续${part}) "}" + text.substring(start, end))
            start = end
            part++
        }
        if (text.isEmpty()) requestLog.info("[$tag] （空）")
    }

    private companion object {
        const val DAY_MILLIS = 24L * 3600 * 1000

        /** 详细日志单条上限：环形日志每条都全量展示，太长会淹没其它日志。 */
        const val VERBOSE_LINE_LIMIT = 1500

        /** `/v1/models` 单个供应商拉取模型目录的超时（秒）：上游 READ_TIMEOUT 300s 不能无限等。 */
        const val MODELS_FETCH_TIMEOUT_SECONDS = 20L

        /** 区域型供应商：等待各区域目录拉取的预算（单个请求自身的读超时另算）。 */
        private const val REGIONAL_FETCH_WAIT_MS = 16_000L

        const val CUSTOM_PREFIX = "custom:"

        /** 自动补刷额度的同账号最小间隔：对话连发时不打爆上游积分接口。 */
        private const val AUTO_CREDIT_REFRESH_MIN_INTERVAL_MS = 10_000L

        /**
         * 批量动作的默认并发度：6 个账号同时打上游，是「吞吐较 3 明显提升」又「不齐发触发风控」
         * 的折中。上游风控与账号稳定是第一优先级，故不再往上抬；爱快的人可用显式 parallelism
         * 提到 [MAX_BATCH_PARALLELISM]。
         */
        const val DEFAULT_BATCH_PARALLELISM = 6

        /**
         * 并发度上限：给「想更快」留出的天花板（默认仍保守取 [DEFAULT_BATCH_PARALLELISM]）。
         * 再大就和「一次打全部」没区别，只会放大风控。
         */
        private const val MAX_BATCH_PARALLELISM = 12

        /** Trae 登录回调用的本地端口（避开 Antigravity 的 51121）。 */
        const val TRAE_CALLBACK_PORT = 51120
        const val TRAE_CALLBACK_PATH = "/authorize"

        /** 小浣熊（商汤）登录回调用的本地端口。 */
        const val RACCOON_CALLBACK_PORT = 51122
        const val RACCOON_CALLBACK_PATH = "/callback"

        /** 登录回调监听的存活上限。 */
        const val CALLBACK_TIMEOUT_MS = 5L * 60 * 1000
    }
}

/** 登录回调结果页（在系统浏览器里展示）。 */
private fun resultPage(ok: Boolean, who: String, error: String): String {
    val title = if (ok) "登录成功" else "登录失败"
    val message = if (ok) "账号 $who 已添加，可以关闭本页并返回应用。" else error.ifEmpty { "请回到应用重试" }
    return """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$title</title>
        <style>
          body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
               font-family:system-ui,-apple-system,"PingFang SC",sans-serif;background:#f6f8fb;color:#1b1c1e}
          .card{max-width:22rem;padding:2rem;border-radius:1.75rem;background:#fff;text-align:center;
                box-shadow:0 8px 30px rgba(0,0,0,.06)}
          h1{font-size:1.25rem;margin:0 0 .75rem}
          p{font-size:.95rem;line-height:1.6;color:#5a5f66;margin:0}
        </style></head>
        <body><div class="card"><h1>$title</h1><p>$message</p></div></body></html>
    """.trimIndent()
}

/** 默认用网络接口枚举局域网地址；Android 侧注入系统实现更准。 */
object DefaultLanAddressProvider : LanAddressProvider {
    override fun lanAddresses(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }
}
