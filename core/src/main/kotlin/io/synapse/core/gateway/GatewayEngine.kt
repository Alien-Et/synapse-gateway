package io.synapse.core.gateway

import com.google.gson.JsonObject
import io.synapse.core.pool.AccountImportResult
import io.synapse.core.pool.AccountPool
import io.synapse.core.pool.AccountSnapshot
import io.synapse.core.pool.AccountStatus
import io.synapse.core.pool.AccountTransfer
import io.synapse.core.pool.PoolSummary
import io.synapse.core.provider.ACTION_CHECKIN
import io.synapse.core.provider.ACTION_COMPLETE_TASKS
import io.synapse.core.provider.AuthKind
import io.synapse.core.provider.CredentialVerdict
import io.synapse.core.provider.CreditInfo
import io.synapse.core.provider.DeviceAuthPoll
import io.synapse.core.provider.DeviceAuthTicket
import io.synapse.core.provider.DeviceCodeSupport
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.LoopbackOAuthSupport
import io.synapse.core.provider.Provider
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderActionResult
import io.synapse.core.provider.ProviderTaskListView
import io.synapse.core.provider.PointsBill
import io.synapse.core.provider.PointsBillListView
import io.synapse.core.provider.ProviderCapability
import io.synapse.core.provider.ProviderHooks
import io.synapse.core.provider.ProviderModelCatalogView
import io.synapse.core.provider.ProviderRegistry
import io.synapse.core.provider.QuotaPack
import io.synapse.core.provider.RoutedModel
import io.synapse.core.provider.HumanCheckChallenge
import io.synapse.core.provider.HumanCheckSupport
import io.synapse.core.provider.SmsLoginSupport
import io.synapse.core.provider.WebLoginSupport
import io.synapse.core.provider.WebLoginTicket
import io.synapse.core.provider.antigravity.AntigravityProvider
import io.synapse.core.provider.custom.CustomProvider
import io.synapse.core.provider.raccoon.RaccoonProvider
import io.synapse.core.provider.trae.TraeProvider
import io.synapse.core.store.KeyValueStore
import io.synapse.core.usage.CallLogStore
import io.synapse.core.usage.CreditReport
import io.synapse.core.usage.RequestLog
import io.synapse.core.usage.StorageAudit
import io.synapse.core.usage.StorageReport
import io.synapse.core.usage.UsageStats
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

/** 英文区域后缀 → 区域标识（键小写，匹配时 lowercase）。 */
internal val EN_REGION_PREFIXES = mapOf(
    "codebuddy-cn" to "cn",
    "workbuddy-cn" to "cn",
    "trae-cn" to "cn",
    "codebuddy-global" to "global",
    "workbuddy-global" to "global",
    "trae-global" to "global",
)


/** 中文区域后缀 → 区域标识。 */
internal val CN_REGION_PREFIXES = mapOf(
    "腾讯国内" to "cn",
    "字节国内" to "cn",
    "腾讯国外" to "global",
    "字节国外" to "global",
)


/**
 * 内置标签表（英文别名）。键必须是小写，匹配时对输入做 `lowercase()`。
 *
 * 英文只是**输入别名**：`/v1/models` 与模型页的前缀仍然只出中文，
 * 所以不存在「同一家有两个官方名字」的问题。
 *
 * ⚠️ 区域型供应商（腾讯 / 字节）**不收裸名**：`codebuddy` / `trae` 解析不出区域，
 * 与中文的 `腾讯/`、`字节/` 一样属于无意义路由。只收带区域的四种写法。
 */
internal val EN_ROUTE_PREFIXES = mapOf(
    "codebuddy-cn" to "codebuddy",
    "codebuddy-global" to "codebuddy",
    "workbuddy-cn" to "codebuddy",
    "workbuddy-global" to "codebuddy",
    "trae-cn" to "trae",
    "trae-global" to "trae",
    "antigravity" to "antigravity",
    "google" to "antigravity",
    "gemini" to "antigravity",
    "loomy" to "loomy",
    "iflytek" to "loomy",
    "spark" to "loomy",
    "raccoon" to "raccoon",
    "sensetime" to "raccoon",
    "mcloud" to "mcloud",
    "lingxi" to "mcloud",
    "chatgpt" to "chatgpt",
    "gpt" to "chatgpt",
    "openai" to "chatgpt",
    "cline" to "cline",
    "doubao" to "doubao",
    "nova" to "doubao",
)


/**
 * 内置标签表（中文正名）。
 *
 * 键是客户端写在 `标签/模型名` 里的标签，值是 provider id。
 * 这张表与 [EN_ROUTE_PREFIXES] 合起来构成**唯一权威表**（见 [chineseRoutePrefix]）。
 */
internal val CN_ROUTE_PREFIXES = mapOf(
    "腾讯国内" to "codebuddy",
    "腾讯国外" to "codebuddy",
    "字节国内" to "trae",
    "字节国外" to "trae",
    "谷歌" to "antigravity",
    "讯飞" to "loomy",
    "商汤" to "raccoon",
    // 灵犀（中国移动的 AI 通道）。**只保留这一个标签**：
    // 「移动云盘」「139云」这类旧名已按需求删除——它们指的是同一家，
    // 多留几个别名只会让人在模型页看到一串重复的名字，不知道该写哪个。
    "灵犀" to "mcloud",
    "ChatGPT" to "chatgpt",
    "GPT" to "chatgpt",
    // Cline：免费池 / 订阅池挂在同一个账号上，池由模型 id 的前缀区分
    // （`cline-free/...` / `cline-pass/...`），所以这里只有一个标签。
    "Cline" to "cline",
    // 豆包：无区域概念（store-region 只是存储节点），所以只有一个标签。
    "豆包" to "doubao",
)


/** 引擎共享常量（原 companion，因跨文件扩展函数需要而提到顶层）。 */
internal const val CUSTOM_PREFIX = "custom:"
internal const val DEFAULT_BATCH_PARALLELISM = 6
internal const val MAX_BATCH_PARALLELISM = 12



/**
 * 网关引擎：把账号池、各供应商、HTTP 服务、日志与设置串起来。
 * UI 直接调用这里的方法，不需要走 HTTP。
 */
class GatewayEngine(
    private val store: KeyValueStore,
    internal val nowMillis: () -> Long = System::currentTimeMillis,
    private val lanAddressProvider: LanAddressProvider = DefaultLanAddressProvider,
    internal val onLog: (String) -> Unit = {},
    /**
     * 调用记录是否在构造时同步载入。一次性 CLI 工具需要同步（命令立即读数据）；
     * App 传 false，预载与过期清理改在后台线程执行，冷启动不再阻塞主线程。
     */
    preloadCallLogs: Boolean = true,
    /** 完整原文溢出目录：加密存储只留每字段 8K 摘要，超出部分写这里供详情页回读；null 时仅摘要。 */
    spillDir: java.io.File? = null,
) {
    val settingsRepository = SettingsRepository(store)
    // requestLog 必须先建：账号池的落盘失败要写进运行日志（见 AccountPool.log 的说明）
    val requestLog = RequestLog(store, nowMillis = nowMillis)
    val pool = AccountPool(store, nowMillis) { requestLog.warn(it) }
    val callLogStore = CallLogStore(store, preloadOnConstruction = preloadCallLogs, spillDir = spillDir)
    val registry = ProviderRegistry()

    val sanitizer = io.synapse.core.security.RequestSanitizer(nowMillis)
    val rateLimiter = io.synapse.core.security.AccountRateLimiter(nowMillis)

    /** 在途请求表（仅供状态展示：通知/界面）；不参与调度，见 [ActiveRequests]。 */
    val activeRequests = ActiveRequests(nowMillis)

    /**
     * 额度告警总线：某个账号/整个供应商没额度时向外发事件，由 App 层做震动+通知。
     *
     * 只负责「告知」，**不参与调度**：告警没人监听或监听器抛异常，转发照跑。
     */
    val quotaAlerts = QuotaAlerts()

    /**
     * 系统吐司事件总线：告诉用户「系统刚刚自动做了什么」（已冷却/已禁用/已转移）。
     * 与 [quotaAlerts] 分工：那边是横幅+震动+常驻通知的强提醒，这里是一闪而过的轻提示。
     */
    val noticeEvents = NoticeEvents(nowMillis)

    @Volatile
    private var failoverSettings: io.synapse.core.failover.ModelFailoverSettings =
        io.synapse.core.failover.ModelFailoverSettings.fromJson(store.read(io.synapse.core.failover.ModelFailoverSettings.STORE_KEY))

    private val circuitBreakers = ConcurrentHashMap<String, io.synapse.core.failover.ProviderCircuitBreaker>()
    private val providerMetrics = ConcurrentHashMap<String, io.synapse.core.failover.ProviderMetricsTracker>()

    /** 模型级冷却表：该 (供应商, 模型) 暂时不可用时直接跳过，换号无意义。仅内存。 */
    private val modelCooldown = io.synapse.core.failover.ModelCooldownTracker(nowMillis)

    /**
     * 模型禁用表：**跨账号**禁用某个模型 N 小时。
     *
     * 与冷却的分工见 [io.synapse.core.failover.ModelBanTracker] 的类注释：
     * 冷却是「这个号用这个模型不行」（10 分钟、仅内存），
     * 禁用是「这个模型这阵子别用了」（24 小时、落盘）。
     *
     * 落盘是必须的：24 小时的窗口远长于一次 App 启动周期，
     * 不落盘的话用户重启一次 App 就白禁了。
     */
    private val modelBans = io.synapse.core.failover.ModelBanTracker(store, nowMillis)

    /** 上游请求并发闸门：配置随 failoverSettings 动态生效。 */
    val concurrencyGate = io.synapse.core.failover.ConcurrencyGate { failoverSettings.concurrency }

    @Volatile
    private var securitySettings: io.synapse.core.security.SecuritySettings =
        io.synapse.core.security.SecuritySettings.fromJson(store.read(io.synapse.core.security.SecuritySettings.STORE_KEY))

    @Volatile
    private var autoCheckinSettings: AutoCheckinSettings =
        AutoCheckinSettings.fromJson(store.read(AutoCheckinSettings.STORE_KEY).orEmpty())

    /**
     * 自动签到的当天去重锁。
     *
     * 「先落盘再执行」是这套逻辑的核心：`store.write` 在 App 端是**同步**的
     * （加密存储必须同步，见 EncryptedKeyValueStore 的说明），所以一旦落盘，
     * 当天就被标记为已跑，并发的两个入口（启动 + 定时）里只会有一个真的执行。
     */
    private val autoCheckinLock = Any()

    /** 已排程的定时签到任务；改设置或服务停止时取消它。 */
    @Volatile
    private var autoCheckinTimer: java.util.concurrent.ScheduledFuture<*>? = null

    @Volatile
    internal var settings: GatewaySettings = settingsRepository.load()

    private val providerSettingsCache = ConcurrentHashMap<String, ProviderSettings>()

    /**
     * 供应商上游域名表的缓存：`providerHosts()` 被 [GatewayProxySelector] 在每次建连时读取
     * （仅当配置了 `excludedProviderIds`），原来每次新建一个 Map 并遍历全部 provider。
     * providers 注册变更（[reloadCustomProviders]）时失效即可，其余时间复用。
     */
    @Volatile
    private var providerHostsCache: Map<String, List<String>>? = null

    /**
     * 最近一次加载成功的模型目录快照（全供应商聚合）。
     *
     * 专供「按模型名自动跨供应商路由」（[io.synapse.core.failover.SameModelAutoRouter]）
     * 零网络读取：`models()` 会真打每家接口（单家读超时 300s），在失败重试路径上现拉
     * 等于把一次超时放大成几十秒卡死。快照为空 = 该能力不参与，行为退化成原样。
     *
     * 写入时机只有一处：[modelsStreaming] 聚合完成时。不落盘 —— 它是「最近一次观察到
     * 的目录」，重启后由下一次加载重新填充。
     */
    @Volatile
    var modelCatalogSnapshot: List<RoutedModel> = emptyList()
        private set

    /**
     * 模型目录与测速结果的持久化层（借鉴 Magpie 的 last-known-good 思路）：
     * 拉取成功后覆盖落盘，失败绝不覆盖；拉取失败时用本机副本兜底，
     * 避免一次上游抖动让用户的模型列表突然清空。
     */
    private val catalogStore: io.synapse.core.store.ModelCatalogStore by lazy {
        io.synapse.core.store.ModelCatalogStore(store())
    }

    /**
     * 共享 I/O 线程池：模型目录、额度查询等会阻塞在网络上的并行任务都走这里。
     * 之前每次请求都 new 一个线程池再 shutdown，短连接 + 线程反复创建销毁纯属浪费；
     * 这里常驻复用，空闲线程 60 秒自行回收，不常占资源。
     */
    internal val ioPool = ThreadPoolExecutor(
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

    internal var server: GatewayHttpServer? = null

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
            log("账号凭证已自动持久化更新（${account.providerId}/${pool.displayNameOf(account.providerId, account.uid)}）")
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

    /** 本机屏蔽的模型。独立于 [settings] 存储与保存入口，屏蔽模型不触发 provider 重载。 */
    private var modelBlocklist: ModelBlocklist = ModelBlocklist.load(store)

    /** 当前正在监听的登录回调服务（同一时间只允许一个登录流程）。 */
    internal var callbackServer: LoopbackCallbackServer? = null

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

    fun securitySettings(): io.synapse.core.security.SecuritySettings = securitySettings

    fun updateSecuritySettings(updated: io.synapse.core.security.SecuritySettings) {
        securitySettings = updated
        store.write(io.synapse.core.security.SecuritySettings.STORE_KEY, updated.toJson().toString())
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

    fun failoverSettings(): io.synapse.core.failover.ModelFailoverSettings = failoverSettings

    /**
     * 某供应商当前**已缓存**的可用额度（该家所有账号的余额之和）。
     *
     * 只读缓存，**绝不触发上游请求**：这个值只用于「按额度」策略挑跨供应商候选，
     * 挑选发生在一个请求的失败路径上，绝不能为了排序再打一轮额度接口。
     * 未知时返回 null（不是 0）——「不知道」不该被当成「没有额度」。
     */
    fun cachedCreditsOf(providerId: String): Long? {
        val statuses = pool.statuses(providerId)
        val known = statuses.filter { it.creditsKnown }
        if (known.isEmpty()) return null
        return known.sumOf { it.credits }
    }

    fun updateFailoverSettings(updated: io.synapse.core.failover.ModelFailoverSettings) {
        failoverSettings = updated
        store.write(io.synapse.core.failover.ModelFailoverSettings.STORE_KEY, updated.toJson().toString())
        log(
            "故障转移配置已更新（启用=${updated.enabled}，单供应商最大重试=${updated.retry.maxAttempts}，" +
                "熔断阈值=${updated.circuitBreaker.failureThreshold}，并发：单账号=${updated.concurrency.maxPerAccount}，" +
                "全局=${updated.concurrency.maxGlobal}，0 表示不限；模型级冷却=${updated.modelCooldown.cooldownMs}ms）",
        )
    }

    fun circuitBreakerOf(providerId: String): io.synapse.core.failover.ProviderCircuitBreaker =
        circuitBreakers.computeIfAbsent(providerId) {
            io.synapse.core.failover.ProviderCircuitBreaker(providerId, config = { failoverSettings.circuitBreaker }, nowMillis = nowMillis)
        }

    fun metricsOf(providerId: String): io.synapse.core.failover.ProviderMetricsTracker =
        providerMetrics.computeIfAbsent(providerId) { io.synapse.core.failover.ProviderMetricsTracker() }

    fun modelCooldownOf(): io.synapse.core.failover.ModelCooldownTracker = modelCooldown

    fun modelBansOf(): io.synapse.core.failover.ModelBanTracker = modelBans

    /**
     * 该供应商的该模型是否已被禁用。
     *
     * 供路由层在选号前判一次：禁用中的模型**直接跳过**，连账号都不必挑。
     */
    fun isModelBanned(providerId: String, model: String): Boolean =
        modelBans.isBanned(providerId, model)

    // ------------------------------------------------------------------ 模型黑名单

    fun modelBlocklist(): ModelBlocklist = modelBlocklist

    /**
     * 批量屏蔽模型（幂等）。返回真正新增的条数。
     *
     * 屏蔽名单变化时同步裁掉「同名自动路由」用的目录快照：否则被屏蔽的模型仍会被当成
     * 跨供应商候选，故障转移时又把它捡回来 —— 用户明明屏蔽了，请求却还是打到了它。
     */
    fun blockModels(fullIds: Collection<String>): Int {
        // 先算新增集合再落盘：updateModelBlocklist 会替换字段，
        // 若在替换之后再比较新旧大小，差值永远是 0（曾经这样写，UI 一直提示「已屏蔽 0 个」）。
        val added = fullIds.filterNot { modelBlocklist.blocks(it) }.toSet()
        if (added.isEmpty()) return 0
        updateModelBlocklist(modelBlocklist.withAll(added))
        return added.size
    }

    /** 取消屏蔽一个模型。 */
    fun unblockModel(fullId: String) {
        val updated = modelBlocklist.without(fullId)
        if (updated != modelBlocklist) updateModelBlocklist(updated)
    }

    fun isModelBlocked(fullId: String): Boolean = modelBlocklist.blocks(fullId)

    /**
     * 跨供应商候选（`providerId` + 模型名）是否被本机屏蔽。
     *
     * 屏蔽名单存的是 **fullId**（`中文标签/模型名`，如 `字节国内/glm-5.2`），而候选只记
     * `providerId` + `model`（如 `trae` + `glm-5.2`）—— 两者不能直接比。
     *
     * 反过来拆：取名单里每个条目的前缀，经 [normalizeProviderPrefix] 还原成 providerId，
     * 再与候选的 providerId 比较。这样区域型供应商（腾讯/字节：一个 providerId 对应
     * 「国内」「国外」两个标签）无论屏蔽的是哪个区域，都能正确命中。
     *
     * 为什么不能只看界面过滤：候选配置是落盘的，用户先勾了候选、之后在模型页屏蔽了
     * 那个模型，配置里那条还在 —— 故障转移时仍会打到它。
     */
    fun isCandidateBlocked(providerId: String, model: String): Boolean {
        if (modelBlocklist.blocked.isEmpty()) return false
        val suffix = "/$model"
        return modelBlocklist.blocked.any { fullId ->
            if (fullId.length <= suffix.length || !fullId.endsWith(suffix)) return@any false
            val prefix = fullId.substringBefore('/')
            // 已知标签（腾讯国内→codebuddy）要还原成 providerId 才能与候选比较；
            // 未知供应商的前缀本身就是 providerId（`alpha/xxx`），normalize 返回 null 时直接用它。
            val owner = normalizeProviderPrefix(prefix) ?: prefix
            owner == providerId
        }
    }

    /** 更新屏蔽名单并落盘；独立入口，不调用 reconfigureProviders。 */
    fun updateModelBlocklist(updated: ModelBlocklist) {
        modelBlocklist = updated
        store.write(ModelBlocklist.STORE_KEY, updated.toJson().toString())
        modelCatalogSnapshot = modelCatalogSnapshot.filterNot { updated.blocks(it.fullId) }
        log("模型屏蔽名单已更新（已屏蔽=${updated.blocked.size}）")
    }

    /**
     * 对外暴露的模型目录：模型页 UI 用 `modelsStreaming`（不过滤，展示全量，供屏蔽/取消屏蔽）；
     * HTTP `/v1/models` 用本方法（减掉本机屏蔽的模型）。
     */
    fun exposedModels(): List<RoutedModel> = models().filterNot { modelBlocklist.blocks(it.fullId) }

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
        // 绑定地址与端口只在 start() 那一刻读取，所以这两项变更必须重启服务才生效。
        // 否则用户打开「同时监听局域网」后，界面显示已开启、也给出了局域网地址，
        // 实际 socket 仍只绑在 127.0.0.1 上，别的设备根本连不进来。
        val bindingChanged = updated.exposeLan != settings.exposeLan || updated.port != settings.port
        val previous = settings
        settings = updated
        settingsRepository.save(updated)
        if (updated.requestLogCapacity != oldCapacity) requestLog.resize(updated.requestLogCapacity)
        reconfigureProviders()
        if (bindingChanged) restartIfRunning(previous)
    }

    /**
     * 绑定参数（端口 / 是否监听局域网）变化后重启服务，让新配置真正生效。
     *
     * 只在服务确实运行时重启：没启动时什么都不做，避免用户改个端口就把网关拉起来。
     * 重启失败（如新端口被占用）不吞错，向上抛给调用方提示用户。
     */
    private fun restartIfRunning(previous: GatewaySettings) {
        synchronized(lifecycleLock) {
            if (server == null) return

            // 端口没变（只切了「监听局域网」）时，新实例要绑的正是旧实例占着的那个端口，
            // 「先起新再停旧」在这里必然 EADDRINUSE —— 所以只能先停后起。
            // 端口变了则反过来：先在新端口起好，再停旧的，避免中间一段谁都连不上。
            val samePort = previous.port == settings.port
            if (samePort) {
                server?.stop()
                server = null
            }

            val created = GatewayHttpServer(this, bindHost(), settings.port)
            try {
                created.start(0, false)
            } catch (e: Exception) {
                // 起不来：配置回滚到实际生效的旧值，避免界面显示 A、实际监听 B。
                settings = previous
                settingsRepository.save(previous)
                if (samePort) {
                    // 旧实例已经被停掉了，尽力把它按旧设置拉回来，别让网关就此消失
                    runCatching {
                        val restored = GatewayHttpServer(this, bindHost(), previous.port)
                        restored.start(0, false)
                        server = restored
                    }
                }
                throw IllegalStateException("端口 ${previous.port} 不可用，已保持原配置：${e.message}", e)
            }

            if (!samePort) server?.stop()
            server = created
            log("网关已重启，监听 ${created.listeningAddress()}")
        }
    }

    /** 当前设置对应的绑定地址：null 表示监听全部网卡。 */
    private fun bindHost(): String? = if (settings.exposeLan) null else "127.0.0.1"

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
            val created = GatewayHttpServer(this, bindHost(), settings.port)
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

    /**
     * 局域网接入地址。
     *
     * 只有真的监听全部网卡（exposeLan=true）时才给出地址：否则服务只绑 127.0.0.1，
     * 把 192.168.x.x 这种地址显示出来只会误导用户 —— 复制到别的设备上必然连不通。
     */
    fun lanUrls(): List<String> =
        if (!settings.exposeLan) emptyList()
        else lanAddressProvider.lanAddresses().map { "http://$it:${settings.port}/v1" }

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
        creditReport = buildCreditReport(),
    )

    /**
     * 用最近的日统计算出积分↔token 的实测换算率，并把池里余额按量纲分开。
     *
     * 样本窗口取最近 30 天（[CallLogStore.dailyStats] 的保留窗）：
     * 只看今天常常样本不足；窗口太长又会把汇率变化、供应商调整系数之前的数据混进来。
     *
     * 怎么判断一个账号的余额是 token 还是积分：看 provider 给的 detail。
     * 灵犀那条固定拼的是「剩余 … tokens …」，带 `tokens` 字样；其余家都是「积分/权益包」。
     * 这不是靠 providerId 硬编，换一家以后只要 detail 里写了 tokens 也自动归到 token 侧。
     */
    private fun buildCreditReport(): CreditReport {
        // 井水不犯河水：token / 积分 各算各的，绝不换算。
        // 两次余额混在一起相加没有意义，换算又缺基准单价做不了。
        var tokens = 0L
        var points = 0L
        for (status in pool.statuses()) {
            if (!status.creditsKnown) continue
            // 按 detail 里的单位标记分流：写了 tokens 的进 token 侧，否则进积分侧。
            // 用文案而不是 providerId 硬编：以后接进来 a 写清单位的供应商会自动归位。
            if (status.detail.contains(TOKEN_UNIT_MARK, ignoreCase = true)) {
                tokens += status.credits
            } else {
                points += status.credits
            }
        }
        return CreditReport(tokensBalance = tokens, pointsBalance = points)
    }

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
                io.synapse.core.provider.custom.CustomProvider(
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

    /**
     * 手动开关账号。
     *
     * 打开一个**凭证失效**的账号时，先做一次鉴权探测：凭证确实可用了才解除硬禁用。
     * 不能凭一次点击就把「上游明确说这份凭证不行」的状态抹掉 —— 那会让坏账号重新进入
     * 选号池，用户紧接着又收到一次 401。反过来，鉴权通过就说明上次的判定已经过时
     * （或只是一次瞬时故障），此时应当恢复它，而不是逼用户重新登录。
     */
    fun setAccountEnabled(providerId: String, uid: String, enabled: Boolean): AccountToggleResult {
        val status = pool.status(providerId, uid) ?: return AccountToggleResult.NotFound
        if (!enabled) {
            pool.setEnabled(providerId, uid, false, reason = "手动停用")
            log("停用账号：$providerId/$uid")
            return AccountToggleResult.Disabled
        }
        if (status.disabled) {
            return when (verifyAccount(providerId, uid)) {
                CredentialVerdict.VALID -> {
                    pool.clearDisabled(providerId, uid)
                    pool.setEnabled(providerId, uid, true)
                    log("账号凭证已恢复可用：$providerId/$uid")
                    AccountToggleResult.Enabled
                }
                CredentialVerdict.INVALID -> {
                    val reason = pool.status(providerId, uid)?.reason.orEmpty().ifEmpty { "凭证无效" }
                    log("启用被拒（凭证仍然失效）：$providerId/$uid")
                    AccountToggleResult.StillInvalid(reason)
                }
                CredentialVerdict.UNSUPPORTED, CredentialVerdict.UNKNOWN -> {
                    log("启用被拒（无法确认凭证状态）：$providerId/$uid")
                    AccountToggleResult.Unverified("无法确认凭证状态（网络异常或该供应商不支持校验）")
                }
            }
        }
        pool.setEnabled(providerId, uid, true)
        log("启用账号：$providerId/$uid")
        return AccountToggleResult.Enabled
    }

    /**
     * 鉴权探测一份凭证当前还能不能用。
     *
     * 临期凭证先做一次安全刷新（[freshAccount]），否则拿一个已经过期几分钟的 token 去探，
     * 只会得到「失效」的假结论。
     */
    fun verifyAccount(providerId: String, uid: String): CredentialVerdict {
        val provider = registry.get(providerId) ?: return CredentialVerdict.UNKNOWN
        val stored = pool.account(providerId, uid) ?: return CredentialVerdict.UNKNOWN
        val account = freshAccount(provider, stored)
        return runCatching { provider.verifyCredential(account) }
            .getOrDefault(CredentialVerdict.UNKNOWN)
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
                ?: return CreditRefreshResult(providerId, uid, pool.displayNameOf(providerId, uid), 0, false, "该供应商不支持额度刷新")
        } catch (e: Exception) {
            // 与「provider 自己报 known=false」保持一致：刷新失败不能把旧值继续当最新值展示
            logWarn("额度刷新异常（$providerId/${pool.displayNameOf(providerId, uid)}）：${e.message}")
            val reason = e.message ?: "刷新额度失败"
            pool.updateCredits(providerId, uid, 0, false, reason)
            return CreditRefreshResult(providerId, uid, pool.displayNameOf(providerId, uid), 0, false, reason)
        }
        pool.updateCredits(
            providerId, uid, info.balance, info.known, info.detail, info.actionHint, info.actionUrl,
            memberLevel = info.memberLevel,
        )
        if (info.known) {
            log("额度刷新 $providerId/${pool.displayNameOf(providerId, uid)}：${info.balance}（${info.detail}）")
        } else {
            logWarn("额度刷新失败（$providerId/${pool.displayNameOf(providerId, uid)}）：${info.detail}")
        }
        return CreditRefreshResult(
            providerId, uid, pool.displayNameOf(providerId, uid), info.balance, info.known,
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

    // ------------------------------------------------------------------ 自动签到

    /**
     * 当天自动签到的去重键（**本地日期** `yyyy-MM-dd`）。
     *
     * 必须落盘：只放内存的话，用户重启一次 App 就重新签一遍，
     * 「当天只跑一次」这条约束等于不存在。
     *
     * 用本地日期而不是 UTC 天数，理由见 [AutoCheckinPlan] 的类注释
     * （东八区 00:05 签到，UTC 那边还没翻篇）。
     */
    private fun autoCheckinLastDay(): String = store.read(AUTO_CHECKIN_LAST_DAY_KEY).orEmpty()

    fun autoCheckinSettings(): AutoCheckinSettings = autoCheckinSettings

    fun updateAutoCheckinSettings(updated: AutoCheckinSettings) {
        autoCheckinSettings = updated
        store.write(AutoCheckinSettings.STORE_KEY, updated.toJson().toString())
        log(
            "自动签到设置已更新（启动时=${if (updated.onLaunchEnabled) "开" else "关"}，" +
                "定时=${if (updated.scheduledEnabled) "开 ${updated.timeLabel()}" else "关"}" +
                "，抖动=${updated.jitterMinutes} 分钟，" +
                "登录后自动做任务=${if (updated.autoCompleteTasksOnLogin) "开" else "关"}）",
        )
        refreshAutoCheckinSchedule()
    }

    /**
     * 重排定时签到（幂等：先取消旧的，再按当前设置排下一个目标时刻）。
     *
     * 三个调用时机：设置被改、定时触发后重排下一天、网关服务启停。
     *
     * 复用共享 [scheduler]，不另起线程池 —— 这只是一个每天醒一次的任务。
     */
    fun refreshAutoCheckinSchedule() {
        autoCheckinTimer?.cancel(false)
        autoCheckinTimer = null
        val cfg = autoCheckinSettings
        if (!cfg.scheduledEnabled) return
        val delay = AutoCheckinPlan.millisUntilNextTarget(
            nowMillis(), cfg.scheduledMinuteOfDay, cfg.jitterMinutes,
        )
        autoCheckinTimer = scheduler.schedule(
            {
                // 无论成败都重排下一天：失败后不再排 = 从此再也不签到；
                // 「失败立刻重试」则可能变成打上游的死循环，两者都不可取。
                runCatching { runAutoCheckin("定时") }
                runCatching { refreshAutoCheckinSchedule() }
            },
            delay,
            TimeUnit.MILLISECONDS,
        )
        log("自动签到已排程：${(delay + 59_999) / 60_000} 分钟后（每天 ${cfg.timeLabel()}，抖动 ${cfg.jitterMinutes} 分钟）")
    }

    /** 取消排程（网关服务停止时调，避免服务都停了还在后台签到）。 */
    fun stopAutoCheckinSchedule() {
        autoCheckinTimer?.cancel(false)
        autoCheckinTimer = null
    }

    /**
     * 「当天第一次打开 App」这条路径的入口。
     *
     * 只在开关打开时才跑；「今天已经跑过」由 [runAutoCheckin] 统一判断，
     * 所以它和定时那条路径天然不会重复（两条路共用一个日期键）。
     */
    fun maybeRunAutoCheckinOnLaunch(): Boolean {
        if (!autoCheckinSettings.onLaunchEnabled) return false
        return runCatching { runAutoCheckin("启动") }.getOrDefault(false)
    }

    /**
     * 跑一次自动签到（两条触发路径共用）。
     *
     * 先落盘再执行：落盘那一刻起今天就算跑过了，重入与并发都会被挡住。
     *
     * @return true 表示这次真的执行了；false 表示今天已跑过、或不值得跑（没有目标）。
     */
    private fun runAutoCheckin(trigger: String): Boolean {
        val today = AutoCheckinPlan.localDate(nowMillis())
        synchronized(autoCheckinLock) {
            if (autoCheckinLastDay() == today) return false
            store.write(AUTO_CHECKIN_LAST_DAY_KEY, today)
        }
        val targets = autoCheckinTargets()
        if (targets.isEmpty()) {
            log("自动签到（$trigger）：没有支持签到的可用账号，跳过")
            return false
        }
        log("自动签到（$trigger）开始：${targets.size} 个账号")
        // performActionAll 会在动作成功后统一补刷一次额度 —— 正好满足「签到完刷新额度」，
        // 不需要再单独打一轮刷新接口。
        val results = runCatching { performActionAll(targets, ACTION_CHECKIN) }
            .getOrElse { e ->
                logWarn("自动签到（$trigger）异常：${e.message}")
                return false
            }
        val ok = results.count { it.ok }
        val failed = results.filterNot { it.ok }
        if (failed.isEmpty()) {
            log("自动签到（$trigger）完成：$ok/${results.size} 个账号成功，额度已刷新")
            noticeEvents.emit(NoticeKind.INFO, "自动签到完成：$ok 个账号，额度已刷新", "auto-checkin|done")
        } else {
            val detail = failed.joinToString("；") { "${it.nickname}：${it.message}" }
            logWarn("自动签到（$trigger）部分失败：成功 $ok、失败 ${failed.size} —— $detail")
            noticeEvents.emit(
                NoticeKind.INFO,
                "自动签到：成功 $ok、失败 ${failed.size}（$detail）",
                "auto-checkin|partial",
            )
        }
        return true
    }

    /**
     * 自动签到的目标。
     *
     * 三道过滤缺一不可：
     * 1. 供应商**声明了签到能力**（不然打过去只会拿到「不支持」）；
     * 2. 账号 `usable`（未被手动停用、凭证未失效）—— 用户手动停用的号必须排除，
     *    绕过去替他签到等于把他的开关当摆设；
     * 3. `supportsAction`（区域/企业账号本就没有签到入口，选目标时就该排掉）。
     */
    private fun autoCheckinTargets(): List<BatchTarget> =
        registry.all()
            .filter { ProviderCapability.CHECKIN in it.capabilities }
            .flatMap { provider ->
                pool.statuses(provider.id)
                    .filter { it.usable }
                    .filter { supportsAction(provider.id, it.uid, ACTION_CHECKIN) }
                    .map { BatchTarget(provider.id, it.uid) }
            }

    /**
     * 新账号加入后检查它当天的签到与任务状态。
     *
     * 与 [claimDesktopLoginRewardSoon] 同一纪律：**异步执行、失败只记日志**，
     * 绝不因为「自动检查」失败而让登录本身报错。
     *
     * 做三件事：
     * 1. **签到**：直接打一次（上游的签到接口是幂等的，它自己会说「今日已签到」）；
     * 2. **任务**：只**读**一遍，有没做完的就提示用户 —— 不自动做；
     * 3. **自动做任务**：只有设置里那个开关打开、且供应商支持时才做，默认关。
     *
     * 第 2/3 步分开是刻意的：自动做任务要模拟真实对话埋点，风控等级比签到高一个量级
     * （见 docs/08「成长任务的风控」），所以默认只提示。
     */
    internal fun checkNewAccountDailyStateSoon(providerId: String, uid: String) {
        ioPool.execute {
            val nickname = pool.displayNameOf(providerId, uid)
            val provider = registry.get(providerId) ?: return@execute
            val caps = provider.capabilities

            if (ProviderCapability.CHECKIN in caps && supportsAction(providerId, uid, ACTION_CHECKIN)) {
                runCatching { performAction(providerId, uid, ACTION_CHECKIN) }
                    .onSuccess { result ->
                        if (result.ok) log("登录后自动签到（$nickname）：${result.message}")
                        else logWarn("登录后自动签到未成功（$nickname）：${result.message}")
                    }
                    .onFailure { logWarn("登录后自动签到异常（$nickname）：${it.message}") }
            }

            if (ProviderCapability.TASKS !in caps) return@execute
            val view = runCatching { taskList(providerId, uid) }.getOrNull() ?: return@execute
            // locked 的做不了，completed 的不用做
            val pending = view.tasks.count { !it.completed && !it.locked }
            if (pending <= 0) return@execute

            val canAutoComplete = autoCheckinSettings.autoCompleteTasksOnLogin &&
                ProviderCapability.TASK_AUTO_COMPLETE in caps
            if (canAutoComplete) {
                runCatching { performAction(providerId, uid, ACTION_COMPLETE_TASKS) }
                    .onSuccess { log("登录后自动完成任务（$nickname）：${it.message}") }
                    .onFailure { logWarn("登录后自动完成任务异常（$nickname）：${it.message}") }
            } else {
                log("登录后检查任务（$nickname）：还有 $pending 个未完成，已提示用户")
                noticeEvents.emit(
                    NoticeKind.INFO,
                    "$nickname 还有 $pending 个任务没做，可去「任务中心」一键处理",
                    "tasks-pending|$providerId|$uid",
                )
            }
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
                var chunk = fetchProviderModels(provider)
                // 拉取失败但有账号：顶上「上一次成功目录」的持久化副本（Magpie 的
                // last-known-good 思路）。没有这一层，一次上游抖动/超时就会让用户列表
                // 突然清空，表现为「配好的模型全没了」。错误原因仍保留：界面显示
                // 「拉取失败 + 缓存列表（缓存徽标）」，而不是假装一切都好。
                if (chunk.error != null && pool.accounts(provider.id).isNotEmpty()) {
                    val cached = catalogStore.loadCatalog(provider.id)
                    if (!cached.isNullOrEmpty()) {
                        logWarn("拉取模型目录失败，回退本机上次成功副本（${provider.id}，${cached.size} 条）")
                        chunk = ProviderModelsChunk(chunk.providerId, cached, chunk.error)
                    }
                }
                // 本轮真实拉取成功才覆盖落盘；失败绝不覆盖，保住上次成功副本
                if (chunk.error == null) {
                    // 成功返回空目录也覆盖旧副本，避免缓存里残留已清空的模型。
                    catalogStore.saveCatalog(chunk.providerId, chunk.models)
                }
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
            } catch (e: Exception) {
                // 「没有账号」不是失败：登录前本就不该有模型。抛异常也按同一条约定兜住，
                // 否则未登录的供应商会在模型页报红，看着像故障，其实只是还没登录。
                if (pool.accounts(providerId).isEmpty()) continue
                // 有账号却抛异常：这是真的拉取失败（网络/协议/解析），必须报给用户
                failures[providerId] = e.message ?: "拉取失败"
                continue
            }
            aggregated.addAll(chunk.models)
            // 「没有账号」不是失败：登录前本就不该有模型，供应商自报的 error 也不该报红。
            // 这条约定原本只靠各 Provider 自觉（历史上 ChatGPT 就违反了），在这里统一兜住：
            // 新加一个供应商还没登录时，模型页不该显示「拉取模型目录时有报错：xxx: 未登录」，
            // 那看着像故障，其实只是还没登录。
            if (chunk.error != null && pool.accounts(providerId).isEmpty()) continue
            chunk.error?.let { failures[chunk.providerId] = it }
        }
        // 供「按模型名自动跨供应商路由」零网络读取：故障转移路径不能现拉上游。
        // 屏蔽的模型必须在这里就排掉，否则同名匹配会把它当成可用落点。
        if (aggregated.isNotEmpty()) {
            modelCatalogSnapshot = aggregated.filterNot { modelBlocklist.blocks(it.fullId) }
        }
        if (failures.isEmpty()) {
            // 聚合没有真实失败（无账号不算失败）才刷新成功时间；部分失败保留旧时间，
            // 让 UI 能在短退避结束后重试，而不会把一次失败伪装成新鲜目录。
            catalogStore.setLastFetchedAt(System.currentTimeMillis())
        }
        return ModelsCatalog(aggregated, failures)
    }

    /** 阻塞聚合版：HTTP `/v1/models`、CLI 等需要一次性完整列表的场景使用。 */
    fun models(): List<RoutedModel> = modelsStreaming { }.models

    /**
     * 拉取单个供应商的模型目录（id 带 `provider/` 前缀；区域型供应商按区域拆成多组）。
     *
     * 区域型供应商（见 [io.synapse.core.provider.RegionAwareSupport]）必须**按区域各拉一次**：
     * 国内与国际是两套不同的上游站点、两份不同的模型清单，只用一个账号拉一次再复制给所有区域
     * 会把国内清单冒充成「国际版模型」。该区域没有可用账号时不为它生成任何条目——没有账号支撑的分组
     * 点进去必然调用失败，列出来只会误导用户。
     */
    private fun fetchProviderModels(provider: Provider): ProviderModelsChunk {
        return try {
            val regionAware = provider as? io.synapse.core.provider.RegionAwareSupport
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
        regionAware: io.synapse.core.provider.RegionAwareSupport,
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
        val region = chineseRegionPrefix(rawLabel)
        // ★ 区域标签还要求「该区域至少有一个账号」——但**只对区域型供应商生效**。
        //
        // 腾讯/字节的国内与国外共用同一个 providerId，只检查整家有没有账号是不够的：
        // 只登录了国外号却写「腾讯国内/x」，之前会路由成功，然后在选号时挑不到人，
        // 最后报「没有可用账号」——用户看着有账号，完全不知道问题出在区域上。
        // 这里提前判掉，让报错直接指向真正的原因。
        //
        // 非区域型供应商（谷歌/讯飞/商汤）没有区域概念，不参与这项校验。
        if (region != null) {
            val regionAware = provider as? io.synapse.core.provider.RegionAwareSupport
            if (regionAware != null) {
                val hasRegionAccount = pool.accounts(providerId).any { regionAware.regionOf(it) == region }
                if (!hasRegionAccount) return null
            }
        }
        // 本机屏蔽的模型直接判为不可路由。放在最后一步：只有标签、供应商、区域都成立时
        // 才谈得上「这个模型被屏蔽了」，否则用户会收到一条与真实原因无关的屏蔽提示。
        val route = Route(provider.id, model, region)
        if (modelBlocklist.blocks("${routeLabel(route.providerId, route.region)}/${route.model}")) return null
        return route
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
        val regionAware = registry.get(providerId) as? io.synapse.core.provider.RegionAwareSupport
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
        model: String? = null,
        allowCrossRegion: Boolean = false,
    ): ProviderAccount? {
        // 模型级冷却（按账号）：被冷却的号不要再挑出来，直接换同家的其它号。
        //
        // 这是主人要求的「出现熔断/冷却时立即在同供应商下换号继续」的核心一步：
        // 之前冷却键是 `供应商|模型`，一旦记上就整家的该模型都进冷却，
        // 换号这一步根本做不到。现在冷却按账号记，选号时自然跳过被冷却的号。
        val cooling = if (model.isNullOrEmpty()) emptySet() else modelCooldown.coolingAccounts(providerId, model)
        val excluded = if (cooling.isEmpty()) exclude else exclude + cooling

        // ★ 区域约束：
        // 「腾讯国内/x」和「腾讯国外/x」共用同一个 providerId（codebuddy），字节同理。
        // 默认**区域是硬约束**，绝不跨区——否则国内号没额度时会静默跑到国外站点。
        //
        // 例外：开启「跨区域」（[allowCrossRegion] 且设置里的 crossRegionFailover 为真）时，
        // **先在本区域内找**，找不到才顺延到另一个区域。绝不能直接整池挑——
        // 那会让国内请求长期跑在国外号上，违背用户选区域的本意。
        val crossRegion = allowCrossRegion && failoverSettings.crossRegionFailover
        if (region == null) return pool.pick(providerId, excluded, skipEmptyCredits)
        // 供应商没声明区域能力（非区域型供应商，如谷歌/讯飞/商汤）：
        // 它本来就没有区域概念，region 对它无意义，走整池选号即为正确语义。
        val regionAware = registry.get(providerId) as? io.synapse.core.provider.RegionAwareSupport
            ?: return pool.pick(providerId, excluded, skipEmptyCredits)

        // 一次取状态快照（单次加锁），避免对每个账号各加一次读锁
        val statusByUid = pool.statuses(providerId).associateBy { it.uid }

        // [sameRegionOnly] = false 时不看区域（跨区兜底那一轮用）。
        // 区域过滤写在 pickBest 内部，两轮（含「余额全空」的降级轮）都经过它，
        // 所以降级只会在**同一范围内**降级，不会意外扩大范围。
        fun pickBest(ignoreCreditFilter: Boolean, sameRegionOnly: Boolean): ProviderAccount? =
            pool.accounts(providerId)
                .asSequence()
                .filter { account -> account.uid !in excluded }
                .filter { account -> statusByUid[account.uid]?.usable == true }
                .filter { account -> !sameRegionOnly || regionAware.regionOf(account) == region }
                .filter { account ->
                    val st = statusByUid[account.uid]
                    !(skipEmptyCredits && !ignoreCreditFilter &&
                        st != null && st.creditsKnown && st.credits <= 0L)
                }
                // 同区域内优先额度高的；跨区兜底时同样按额度挑，不做区域加权——
                // 走到这一步说明本区域真的一个能用的号都没有了。
                .maxByOrNull { account -> statusByUid[account.uid]?.credits ?: 0L }

        // 第 1 轮：本区域 + 跳过空额度
        pickBest(ignoreCreditFilter = false, sameRegionOnly = true)?.let { return it }
        // 第 2 轮：本区域 + 不跳过空额度（余额快照可能过时，让上游给真实答案）
        if (skipEmptyCredits) {
            pickBest(ignoreCreditFilter = true, sameRegionOnly = true)?.let { return it }
        }
        // 第 3 轮（仅在开启跨区域时）：跨区域兜底。
        // 走到这里说明本区域一个能用的号都没有了——要么都停用/凭证失效，
        // 要么（skipEmptyCredits 时）额度都是 0。
        if (!crossRegion) return null
        pickBest(ignoreCreditFilter = false, sameRegionOnly = false)?.let { return it }
        return pickBest(ignoreCreditFilter = true, sameRegionOnly = false)
    }

    /**
     * 该供应商（可限定区域）里「模型 [model] 还能用」的账号数量。
     *
     * 只统计真正可用的号（未停用、未凭证失效），并且**扣掉该模型已冷却的号**。
     * 转发层用它判断「同家还有没有号可换」——返回 0 时就没必要再换号了，
     * 应该直接提示用户换供应商或等待冷却结束。
     */
    fun usableAccountCount(providerId: String, model: String, region: String? = null): Int {
        val regionAware = if (region == null) null
        else registry.get(providerId) as? io.synapse.core.provider.RegionAwareSupport
        val statusByUid = pool.statuses(providerId).associateBy { it.uid }
        val cooling = if (model.isEmpty()) emptySet() else modelCooldown.coolingAccounts(providerId, model)
        return pool.accounts(providerId).count { account ->
            val st = statusByUid[account.uid]
            st?.usable == true &&
                account.uid !in cooling &&
                (regionAware == null || regionAware.regionOf(account) == region)
        }
    }

    /**
     * 该供应商的该模型是否**所有可用账号都在冷却**。
     *
     * 用于把「冷却」和「没账号」区分开：前者只是等一会儿，后者要用户动手。
     */
    fun isModelFullyCooling(providerId: String, model: String, region: String? = null): Boolean {
        val regionAware = if (region == null) null
        else registry.get(providerId) as? io.synapse.core.provider.RegionAwareSupport
        val statusByUid = pool.statuses(providerId).associateBy { it.uid }
        val usableUids = pool.accounts(providerId)
            .filter { account ->
                statusByUid[account.uid]?.usable == true &&
                    (regionAware == null || regionAware.regionOf(account) == region)
            }
            .map { it.uid }
        return modelCooldown.allCooling(providerId, model, usableUids)
    }

    /** 该供应商该模型下最早结束的冷却剩余毫秒；没有冷却时返回 0。 */
    fun cooldownRemainingMs(providerId: String, model: String): Long =
        modelCooldown.snapshot()
            .filterKeys { k -> k.startsWith("$providerId|") && k.endsWith("|$model") }
            .values
            .minOrNull()
            ?: 0L

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

    /**
     * 额外日志出口（App 层接 logcat 用）。
     *
     * 排查上游问题时，UI 的「记录」页只能看到窗口期内容、还要来回切页面；
     * 接上这里后 `adb logcat -s SynapseGateway` 就能看到包括上游原始响应在内的全部日志。
     */
    @Volatile
    var logSink: ((String) -> Unit)? = null

    internal fun log(text: String) {
        requestLog.info(text)
        onLog(text)
        logSink?.invoke(text)
    }

    /**
     * 记一条**用户可见的普通信息**到运行日志（App 层用）。
     *
     * 为什么不让 App 直接调 [log]：它是 `internal`，而把它改成 public 会让整个模块
     * 都从任意位置写日志，日志来源就再也理不清了。这里开一个语义明确的窄口子：
     * 「App 层有一件用户该知道的事，请记进运行日志」，仅此而已。
     */
    fun noteForUser(text: String) = log(text)

    internal fun logWarn(text: String) {
        requestLog.warn(text)
        onLog(text)
        logSink?.invoke(text)
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
        // 详细日志也走 logSink（logcat），这样排查时能直接 `logcat -s SynapseGateway` 取，
        // 不必让用户截 UI 里的请求日志面板。
        logSink?.invoke("[$tag] ${text.take(4000)}")
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

    internal companion object {
        const val DAY_MILLIS = 24L * 3600 * 1000

        /** 额度换算的样本窗口（天）：与 CallLogStore.dailyStats 的保留窗对齐。 */
        const val CREDIT_SAMPLE_DAYS = 30

        /**
         * detail 里出现这个词就说明该账号的余额本来就是 token（灵犀这类）。
         *
         * 用文案而不是 providerId 判定：以后接进来的供应商只要 detail 写清单位，
         * 就自动落到 token 侧，不需要回来改这里。
         */
        const val TOKEN_UNIT_MARK = "tokens"

        /** 详细日志单条上限：环形日志每条都全量展示，太长会淹没其它日志。 */
        const val VERBOSE_LINE_LIMIT = 1500

        /** `/v1/models` 单个供应商拉取模型目录的超时（秒）：上游 READ_TIMEOUT 300s 不能无限等。 */
        const val MODELS_FETCH_TIMEOUT_SECONDS = 20L

        /** 区域型供应商：等待各区域目录拉取的预算（单个请求自身的读超时另算）。 */
        private const val REGIONAL_FETCH_WAIT_MS = 16_000L

        /**
         * 当天自动签到的去重日期（本地日期字符串）。
         *
         * 存在设置域而不是账号池域：它是「这台设备今天跑过没」，
         * 与具体某个账号的状态无关。
         */
        private const val AUTO_CHECKIN_LAST_DAY_KEY = "auto/checkin_last_day"

        /** 自动补刷额度的同账号最小间隔：对话连发时不打爆上游积分接口。 */
        private const val AUTO_CREDIT_REFRESH_MIN_INTERVAL_MS = 10_000L

        /**
         * 批量动作的默认并发度：6 个账号同时打上游，是「吞吐较 3 明显提升」又「不齐发触发风控」
         * 的折中。上游风控与账号稳定是第一优先级，故不再往上抬；爱快的人可用显式 parallelism
         * 提到 [MAX_BATCH_PARALLELISM]。
         */

        /**
         * 并发度上限：给「想更快」留出的天花板（默认仍保守取 [DEFAULT_BATCH_PARALLELISM]）。
         * 再大就和「一次打全部」没区别，只会放大风控。
         */

        /** Trae 登录回调用的本地端口（避开 Antigravity 的 51121）。 */

        /** 小浣熊（商汤）登录回调用的本地端口。 */

        /** 登录回调监听的存活上限。 */

        

        

        

        
    }
}

/** 成功结果页里的约定标记：只有带它的页面才允许关闭回调端口。 */
internal const val SUCCESS_PAGE_MARKER = "data-login-result=\"ok\""

/** 登录回调结果页（在系统浏览器里展示）。 */
internal fun resultPage(ok: Boolean, who: String, error: String): String {
    val title = if (ok) "登录成功" else "登录失败"
    val message = if (ok) "账号 $who 已添加，可以关闭本页并返回应用。" else error.ifEmpty { "请回到应用重试" }
    // 成功页带约定标记，回调服务据此决定「可以关端口了」；失败页不带，端口继续留着让用户重试
    val marker = if (ok) " $SUCCESS_PAGE_MARKER" else ""
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
        <body><div class="card"$marker><h1>$title</h1><p>$message</p></div></body></html>
    """.trimIndent()
}

/** 手动切换账号开关的结果。 */
sealed interface AccountToggleResult {
    /** 已启用：鉴权通过，或该账号本来就不是「凭证失效」。 */
    data object Enabled : AccountToggleResult

    /** 已停用（用户手动关闭）。 */
    data object Disabled : AccountToggleResult

    /** 鉴权确认凭证**仍然失效**：保持停用，只能重新登录。 */
    data class StillInvalid(val reason: String) : AccountToggleResult

    /** 无法确认凭证状态（网络异常 / 该供应商不支持校验）：保持停用。 */
    data class Unverified(val reason: String) : AccountToggleResult

    /** 账号不存在（可能刚被删除）。 */
    data object NotFound : AccountToggleResult
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
