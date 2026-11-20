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

/** [GatewayEngine] 的账号导入导出与批量动作能力（扩展函数，行为不变）。 */


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
fun GatewayEngine.exportAccounts(selection: Set<String> = emptySet()): String {
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
                    // 用户起的名字要跟着走：换台设备导入回来不该退回一串 uid
                    customName = status?.customName.orEmpty(),
                )
            }
        // 带上「与导出账号相关」的自定义供应商，以及**全选导出时**所有已配置的
        // 自定义供应商。
        //
        // 后者很重要：一家刚建好、只填了协议/地址/余额配置但还没加账号的自定义供应商，
        // 按账号过滤会被排除，导入后那套配置就凭空消失了。全选（selection 为空）时
        // 用户意图是「整个快照」，这些配置必须一起走。
        val ownerProviderIds = picked.map { it.providerId }.filter { it.startsWith(CUSTOM_PREFIX) }.toSet()
        val allCustom = settingsRepository.loadCustomProviders()
        val providers = if (selection.isEmpty()) {
            allCustom
        } else {
            allCustom.filter { it.providerId in ownerProviderIds }
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
fun GatewayEngine.importAccounts(
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
                            // 流式行为按文件恢复：上游只吃流式的站点，导回来不该又变回非流式
                            forceStreaming = declared.forceStreaming,
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
                        io.synapse.core.provider.custom.CustomProvider(config = config, nowMillis = nowMillis),
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
fun GatewayEngine.restoreAccountState(snapshot: AccountSnapshot) {
        val account = snapshot.account
        pool.setEnabled(
            account.providerId,
            account.uid,
            snapshot.enabled,
            reason = if (snapshot.enabled) "" else "手动停用",
        )
        pool.setPriority(account.providerId, account.uid, snapshot.priority)
        // 自定义名同理：只恢复用户起的名字，不碰凭证里的上游昵称
        pool.setCustomName(account.providerId, account.uid, snapshot.customName)
    }

    /** 解析导出内容（只读、不落池），供导入前预览与勾选。 */
fun GatewayEngine.parseAccountTransfer(json: String): List<ProviderAccount> =
        AccountTransfer.decode(json).accounts.map { it.account }

    /**
     * 修正历史遗留的账号标识（uid 口径变过时自愈）。启动后调用一次，幂等。
     *
     * 逐账号问 provider 是否需要迁移（[Provider.migrateAccount]）：uid 变了就按新 key 重新登记、
     * 删掉旧条目，凭证与账号状态（启用开关、额度）一并跟着走。provider 未实现迁移时直接跳过。
     *
     * @return 实际迁移的账号数。
     */
fun GatewayEngine.migrateAccounts(): Int {
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
fun GatewayEngine.taskList(providerId: String, uid: String): ProviderTaskListView? {
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
fun GatewayEngine.pointsBills(providerId: String, uid: String): PointsBillListView? {
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
fun GatewayEngine.performAction(
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
fun GatewayEngine.performAction(providerId: String, uid: String, action: String): ProviderActionResult =
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
fun GatewayEngine.performActionAll(
        targets: List<BatchTarget>,
        action: String,
        payload: JsonObject = JsonObject(),
        parallelism: Int = DEFAULT_BATCH_PARALLELISM,
        onProgress: ((BatchProgress) -> Unit)? = null,
    ): List<BatchActionResult> {
        // 去重：同一账号最多执行一次动作，避免调用方传入重复目标时重复签到。
        val ordered = targets.distinctBy { it.providerId to it.uid }
        if (ordered.isEmpty()) return emptyList()

        val results = runBounded(
            ordered,
            parallelism,
            onDone = { done, index ->
                if (onProgress != null) {
                    val target = ordered[index]
                    val nickname = pool.displayNameOf(target.providerId, target.uid)
                    onProgress(
                        BatchProgress(
                            done, ordered.size, target.providerId, target.uid, nickname, BatchStage.ACTION,
                        ),
                    )
                }
            },
            onStart = { done, index ->
                if (onProgress != null) {
                    val target = ordered[index]
                    val nickname = pool.displayNameOf(target.providerId, target.uid)
                    // done 传当前已完成数（而不是 index）：并发下 index 乱序，用它会让进度条倒退
                    onProgress(
                        BatchProgress(
                            done, ordered.size, target.providerId, target.uid, nickname,
                            BatchStage.ACTION, started = true,
                        ),
                    )
                }
            },
        ) { target ->
            // 单个账号抛异常也不能拖垮整批：就地转成失败结果。
            val outcome = runCatching { performActionRaw(target.providerId, target.uid, action, payload) }
            val nickname = pool.displayNameOf(target.providerId, target.uid)
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
        if (succeeded.isNotEmpty()) {
            // 刷新阶段也回推进度：否则界面停在「已完成 N/N」不动，用户以为卡死了
            refreshCreditsBounded(succeeded, parallelism, onProgress = onProgress)
        }
        return results
    }

    /**
     * 这个账号是否适用该批量动作（供应商自报，默认适用）。
     *
     * 供批量选目标时调用：区域型供应商的国际版、企业账号本就没有签到/成长中心，
     * 应在选目标时排掉，而不是批量跑完再逐条报错。
     */
fun GatewayEngine.supportsAction(providerId: String, uid: String, action: String): Boolean {
        val provider = registry.get(providerId) ?: return false
        val account = pool.account(providerId, uid) ?: return false
        return provider.supportsAction(account, action)
    }

    /** 批量动作的底层执行：不做额度补刷，供 [performAction] 与 [performActionAll] 共用。 */
fun GatewayEngine.performActionRaw(
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
fun GatewayEngine.freshAccount(provider: Provider, account: ProviderAccount): ProviderAccount {
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
fun GatewayEngine.refreshCreditsBounded(
        targets: List<BatchTarget>,
        parallelism: Int,
        onProgress: ((BatchProgress) -> Unit)? = null,
    ): List<CreditRefreshResult> = runBounded(
        targets,
        parallelism,
        onDone = { done, index ->
            if (onProgress != null) {
                val target = targets[index]
                val nickname = pool.displayNameOf(target.providerId, target.uid)
                onProgress(
                    BatchProgress(done, targets.size, target.providerId, target.uid, nickname, BatchStage.REFRESH),
                )
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
fun <R> GatewayEngine.runBounded(
        targets: List<BatchTarget>,
        parallelism: Int,
        onDone: ((done: Int, index: Int) -> Unit)? = null,
        onStart: ((done: Int, index: Int) -> Unit)? = null,
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
                        // 先报「这个账号开始处理了」：并发跑时四个线程几乎同一时刻启动，
                        // 只报完成事件的话，界面在最初几秒只能显示「准备中」。
                        runCatching { onStart?.invoke(finished.get(), index) }
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

