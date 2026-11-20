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

/**
 * [GatewayEngine] 的模型探测与标签规范化能力。
 *
 * 从 GatewayEngine 拆出的扩展函数：行为不变，仅物理分文件。
 * `probeModelLatency` 也供后续「一键测速」复用。
 */

    /**
     * 探测指定供应商模型的连通性与网络延迟（毫秒）。
     *
     * 发送极轻量的探针请求，耗时低于 5000ms 返回正整数，超时或连接失败返回 -1L。
     */
fun GatewayEngine.probeModelLatency(providerId: String, modelId: String, region: String? = null): ProbeResult {
        val provider = registry.get(providerId)
            ?: return ProbeResult(-1L, "未知供应商 $providerId")
        val account = pickAccount(providerId, region = region)
            ?: return ProbeResult(-1L, "没有可用账号")
        val resolvedModel = provider.resolveModel(modelId)

        // 端点由模型自己决定，不写死：生图/生视频/向量/重排/语音都不认 chat/completions，
        // 拿对话请求去探必然被上游拒（「messages 不能为空」「invalid_request」），
        // 看着像模型挂了，其实是打错了地方。
        //
        // 先按 id 判断（零成本）：绝大多数模型名字里就带 seedream / embed / rerank 这些词根。
        // 名字看不出来时才查一次目录——目录里有上游给的能力字段，比名字准。
        val kind = detectEndpointKind(provider, account, modelId, resolvedModel)

        val start = System.currentTimeMillis()
        val call = try {
            when (kind) {
                io.synapse.core.provider.ModelEndpointKind.CHAT ->
                    provider.openChat(account, chatProbeBody(resolvedModel))

                io.synapse.core.provider.ModelEndpointKind.IMAGE ->
                    provider.passthrough(account, "POST", "/v1/images/generations", jsonProbe(resolvedModel, "prompt"))

                io.synapse.core.provider.ModelEndpointKind.VIDEO ->
                    provider.passthrough(account, "POST", "/v1/videos/generations", jsonProbe(resolvedModel, "prompt"))

                io.synapse.core.provider.ModelEndpointKind.EMBEDDING ->
                    provider.passthrough(account, "POST", "/v1/embeddings", embeddingProbeBody(resolvedModel))

                io.synapse.core.provider.ModelEndpointKind.RERANK ->
                    provider.passthrough(account, "POST", "/v1/rerank", rerankProbeBody(resolvedModel))

                // 语音转写要 multipart（音频文件），探针没法构造出「合法但极轻」的请求：
                // 发个空 multipart 上游会回 400，那与「模型不可用」是两回事。
                // 直接告诉用户这条测不了，别给一个假的失败。
                io.synapse.core.provider.ModelEndpointKind.TRANSCRIPTION ->
                    return ProbeResult(-1L, "语音转写需要上传音频，无法用轻量探针测试")

                io.synapse.core.provider.ModelEndpointKind.SPEECH ->
                    provider.passthrough(account, "POST", "/v1/audio/speech", speechProbeBody(resolvedModel))
            }
        } catch (e: Exception) {
            val msg = e.message ?: "连接失败"
            logWarn("探测 $providerId/$resolvedModel 失败：$msg")
            return ProbeResult(-1L, msg)
        }

        // passthrough 返回 null = 这家没声明这个端点（不是网络失败）。
        // 如实说清楚，别让用户以为模型挂了。
        if (call == null) {
            return ProbeResult(-1L, "该供应商不支持「${kind.label}」端点，无法测试这个模型")
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
     * 判断模型该走哪条端点。
     *
     * 先按 id 猜（零成本），猜不出来再查一次目录（目录里有上游给的能力字段，
     * 比名字准）。查目录失败不能当成「是对话模型」——那会把生图模型又打回
     * chat/completions，所以失败时退回 id 的判断结果。
     */
fun GatewayEngine.detectEndpointKind(
        provider: Provider,
        account: ProviderAccount,
        modelId: String,
        resolvedModel: String,
    ): io.synapse.core.provider.ModelEndpointKind {
        val byId = runCatching {
            io.synapse.core.provider.ProviderModel(modelId, modelId).endpointKind
        }.getOrDefault(io.synapse.core.provider.ModelEndpointKind.CHAT)
        if (byId != io.synapse.core.provider.ModelEndpointKind.CHAT) return byId

        return runCatching {
            provider.listModels(account).models
                .firstOrNull { it.id == modelId || it.id == resolvedModel }
                ?.endpointKind
        }.getOrNull() ?: byId
    }

    /**
     * 对话探针：不指定 max_tokens。
     *
     * 上游对 max_output_tokens 有下限（传 1 会被以 11133 integer_below_min_value 拒绝），
     * 而探测只需要建连与首字节时间。
     */
fun GatewayEngine.chatProbeBody(model: String): String = JsonObject().apply {
        addProperty("model", model)
        addProperty("stream", false)
        add("messages", com.google.gson.JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", "ping")
            })
        })
    }.toString()

    /** 生图 / 生视频探针：只带最小必需项，不关心生成结果。 */
fun GatewayEngine.jsonProbe(model: String, promptKey: String): String = JsonObject().apply {
        addProperty("model", model)
        addProperty(promptKey, "ping")
    }.toString()

    /** 向量探针：一句话足够看出端点通不通。 */
fun GatewayEngine.embeddingProbeBody(model: String): String = JsonObject().apply {
        addProperty("model", model)
        add("input", com.google.gson.JsonArray().apply { add("ping") })
    }.toString()

    /** 重排探针：一条 query + 一条文档。 */
fun GatewayEngine.rerankProbeBody(model: String): String = JsonObject().apply {
        addProperty("model", model)
        addProperty("query", "ping")
        add("documents", com.google.gson.JsonArray().apply { add("ping") })
    }.toString()

    /** 语音合成探针：只要几个字，返回音频就说明端点通。 */
fun GatewayEngine.speechProbeBody(model: String): String = JsonObject().apply {
        addProperty("model", model)
        addProperty("input", "ping")
        addProperty("voice", "alloy")
    }.toString()

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
fun GatewayEngine.normalizeProviderPrefix(prefix: String): String? {
        // 内置标签：中文正名 + 英文别名都认（见 [chineseRoutePrefix]）。
        // ⚠️ 不能改成「先查 registry.get()」：那是按 provider id 做的忽略大小写查找，
        // 会让 `codebuddy/glm-5.2`、`trae/qwen-max` 这类**不带区域**的裸名也变成合法前缀，
        // 而它们解析不出区域，等于静默替用户挑了一个站。英文别名表只收带区域的写法。
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
fun GatewayEngine.customLabelOwner(label: String): String? {
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
fun GatewayEngine.validateCustomLabel(label: String, exceptKey: String? = null): String? {
        val key = label.trim()
        if (key.isEmpty()) return "模型标签不能为空"
        if (key.contains('/') || key.contains("\\")) return "模型标签不能包含斜杠（它就是前缀本身）"
        if (key.length > 32) return "模型标签最多 32 个字符"
        if (!key.matches(Regex("^[^\\s/\\\\]+$"))) return "模型标签不能包含空白字符"

        // 与内置供应商标签冲突（中英两种写法都算占用，否则用户建个叫 antigravity 的自定义供应商，
        // 就会和内置的「谷歌」抢同一串前缀，路由结果取决于查表顺序 —— 那是不可接受的不确定）
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
     * 内置标签表：**一张表，中文是正名，英文是别名**。
     *
     * 这是唯一的权威表 —— 一个标签只对应一家供应商，不查、不猜、不回退。
     * 新增内置供应商时必须在这里加一条（这是故意的：标签是契约，不能隐式生成）。
     * 自定义供应商不走这里，它们走 [customLabelOwner]。
     *
     * ## 中英并存，但输出以中文为准
     *
     * 输入方向两者都认（客户端写 `谷歌/x` 或 `antigravity/x` 都能路由），
     * 输出方向（`/v1/models` 的 id、模型页显示的前缀）**只出中文**，见 [chineseRoutePrefixOf]。
     * 这样界面与 API 只有一套"官方写法"，英文只作为兼容别名存在，不会出现两套并行的命名体系。
     *
     * ## 区域型供应商（腾讯 / 字节）的英文别名必须带区域
     *
     * 它们分国内站与国际站两个上游，`codebuddy` 或 `trae` 裸名解析不出区域 ——
     * 跟中文的 `腾讯/`、`字节/` 一样属于无意义的路由，**不收录**。
     * 只收 `codebuddy-cn` / `codebuddy-global` / `trae-cn` / `trae-global` 四种带区域写法。
     *
     * ## 匹配大小写
     *
     * 中文本身无大小写；英文别名一律**忽略大小写**匹配（`ChatGPT` / `chatgpt` / `CHATGPT` 等价），
     * 这是客户端配置里最常见的写法差异，按大小写区分只会制造「同样一家公司两个标签」的困惑。
     */
fun GatewayEngine.chineseRoutePrefix(prefix: String): String? {
        val p = prefix.trim()
        if (p.isEmpty()) return null
        // 中文正名：逐字精确匹配（中文没有大小写，不做 lowercase 以免误伤）
        CN_ROUTE_PREFIXES[p]?.let { return it }
        // 英文别名：忽略大小写
        return EN_ROUTE_PREFIXES[p.lowercase()]
    }

    /**
     * 中文区域前缀对应的区域标识（cn/global）。
     *
     * 中英两种写法都认（`腾讯国内` / `codebuddy-cn` 等价），与 [chineseRoutePrefix] 同一套语义。
     * 非区域前缀返回 null。
     */
fun GatewayEngine.chineseRegionPrefix(prefix: String): String? {
        val p = prefix.trim()
        CN_REGION_PREFIXES[p]?.let { return it }
        return EN_REGION_PREFIXES[p.lowercase()]
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
fun GatewayEngine.routeLabel(providerId: String, region: String?): String = chineseRoutePrefixOf(providerId, region)

fun GatewayEngine.chineseRoutePrefixOf(providerId: String, region: String?): String {
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
            "mcloud" -> "灵犀"
            "chatgpt" -> "ChatGPT"
            "cline" -> "Cline"
            "doubao" -> "豆包"
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

