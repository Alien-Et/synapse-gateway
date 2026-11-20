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

/** [GatewayEngine] 的模型路由与选号能力（扩展函数，行为不变）。 */

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
fun GatewayEngine.resolveRoute(requested: String): Route? {
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
fun GatewayEngine.routeByLabel(rawLabel: String, model: String): Route? {
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
fun GatewayEngine.accountAvailability(providerId: String, region: String?): AccountPool.Availability {
        val regionAware = registry.get(providerId) as? io.synapse.core.provider.RegionAwareSupport
        return pool.availability(
            providerId = providerId,
            region = region,
            regionOf = regionAware?.let { aware -> { account: ProviderAccount -> aware.regionOf(account) } },
        )
    }

fun GatewayEngine.pickAccount(
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
fun GatewayEngine.usableAccountCount(providerId: String, model: String, region: String? = null): Int {
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
fun GatewayEngine.isModelFullyCooling(providerId: String, model: String, region: String? = null): Boolean {
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
fun GatewayEngine.cooldownRemainingMs(providerId: String, model: String): Long =
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
fun GatewayEngine.resolveCandidateRoutes(requested: String): List<Route> {
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

