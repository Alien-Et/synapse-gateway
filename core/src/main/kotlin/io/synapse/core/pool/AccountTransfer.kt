package io.synapse.core.pool

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.gateway.CustomProviderConfig
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.util.intOrNull
import io.synapse.core.util.stringOrNull

/** 账号导出文件的格式标识：只在「导入」时用来挡住不相干的 JSON。 */
const val ACCOUNT_TRANSFER_FORMAT = "aigw-accounts"

/** 一个账号的完整快照：凭证 + 账号池里的可迁移状态。 */
data class AccountSnapshot(
    val account: ProviderAccount,
    /**
     * 用户手动开关（账号列表里那个开关）。
     *
     * 必须导出：一个被用户主动停用的号，换机器后不该自己变成启用——那等于
     * 悄悄改了他的策略。注意**运行时探测**出来的状态（凭证失效 disabled、
     * 余额 credits）不在这里，那些导入后会重新探一遍，存下来只会过期。
     */
    val enabled: Boolean = true,
    /** 使用优先级（列表里 ↑/↓ 调过的顺序）。 */
    val priority: Int = 0,
    /**
     * 用户给这个账号起的显示名；空串表示跟随上游昵称。
     *
     * 必须导出：这是用户手动起的名字，换台设备后不该退回一串 uid。
     * 它与凭证里的 nickname 是两回事——后者来自上游，重新登录会被覆盖。
     */
    val customName: String = "",
)

/** 解析出来的一份账号导出内容。 */
data class AccountTransferEnvelope(
    val version: Int,
    val exportedAt: Long,
    val accounts: List<AccountSnapshot>,
    /**
     * 随行导出的自定义供应商定义。
     *
     * 为什么必须带上：自定义供应商的标签、接口地址、模型列表存在
     * `settings/custom.json` 里，**不在账号池里**。只导账号的话，
     * 换到新设备后这些账号所属的供应商根本不存在，会被当成
     * 「本机未创建」整批跳过——账号就白导了。
     */
    val providers: List<CustomProviderConfig> = emptyList(),
)

/**
 * 账号导入导出的信封编解码。
 *
 * 只做「一段 JSON ↔ 一批账号 + 自定义供应商定义」的纯转换，
 * 不认识账号池，也不碰存储；粒度由调用方先选好再传进来。
 *
 * 带上自定义供应商定义是必须的：它们的标签/地址/模型不在账号池里，
 * 不导出的话换机器后账号会因为「供应商不存在」而全部被跳过。
 *
 * ⚠️ 这一层处理的是**明文**。写入文件/读出文件请用 [AccountTransferCodec]，
 * 它负责把这段 JSON 包成二进制并按需加密（AES-256-GCM）。
 */
object AccountTransfer {

    /**
     * 当前版本。
     *
     * v1：只有账号。
     * v2：账号 + 自定义供应商定义（providers）。
     * v3：再带上**可迁移的状态**——账号的手动启用开关与优先级、供应商的 protocol。
     *     导入按快照恢复，导出什么状态就还原什么状态。
     * v4：账号再带上**用户自定义的显示名**（customName）。
     *
     * ## 为什么只放行 v3 与 v4
     *
     * 导入是「把快照原样恢复」，缺字段只能靠猜默认值补——猜出来的东西跟你导出时
     * 不是同一份，这种「看起来成功了但其实不一样」比直接报错难查得多。所以 v1/v2
     * 一律拒绝（它们缺启用开关与优先级，补默认值会悄悄改掉用户的策略）。
     *
     * v3 是唯一的例外：它只缺 [AccountSnapshot.customName]，而这个字段缺失的含义
     * 完全确定——「没起过自定义名，跟随上游昵称」，不存在第二种解释。放行它，
     * 用户手上的 v3 备份就不会因为一次升级全部作废。
     */
    const val VERSION = 4

    /** 还能读的旧版本（见 [VERSION] 的说明）。 */
    private val READABLE_VERSIONS = setOf(3, VERSION)

    fun encode(
        accounts: List<AccountSnapshot>,
        exportedAt: Long,
        providers: List<CustomProviderConfig> = emptyList(),
    ): String {
        val array = JsonArray()
        for (snapshot in accounts) {
            val account = snapshot.account
            array.add(JsonObject().apply {
                addProperty("providerId", account.providerId)
                addProperty("uid", account.uid)
                addProperty("nickname", account.nickname)
                addProperty("secret", account.secret)
                // 池里的可迁移状态：导入要原样恢复，不然「停用的号自己启用」
                addProperty("enabled", snapshot.enabled)
                addProperty("priority", snapshot.priority)
                // 只有起过名才写：没起名的账号保持文件精简，导入时按空串处理
                if (snapshot.customName.isNotEmpty()) addProperty("customName", snapshot.customName)
            })
        }
        // 自定义供应商定义：只存「怎么连 + 什么状态」，不存账号凭证（凭证在 accounts 里）
        val providerArray = JsonArray()
        for (p in providers) {
            providerArray.add(JsonObject().apply {
                addProperty("key", p.key)
                addProperty("name", p.name)
                addProperty("baseUrl", p.baseUrl)
                addProperty("protocol", p.protocol)
                // 流式行为也要跟着走：换台设备导入后，上游只吃流式的站点不该又变回非流式
                p.forceStreaming?.let { addProperty("forceStreaming", it) }
                addProperty("enabled", p.enabled)
                add("models", JsonArray().apply { p.models.forEach { add(it) } })
                // 请求头与余额配置一起带走：换机导入后能直接用，不用再手抄一遍
                if (p.headers.isNotEmpty()) {
                    add("headers", JsonArray().apply { p.headers.forEach { add(it.toJson()) } })
                }
                p.balanceQuery?.let { add("balanceQuery", it.toJson()) }
            })
        }
        return JsonObject().apply {
            addProperty("format", ACCOUNT_TRANSFER_FORMAT)
            addProperty("version", VERSION)
            addProperty("exportedAt", exportedAt)
            add("accounts", array)
            if (providerArray.size() > 0) add("providers", providerArray)
        }.toString()
    }

    /**
     * 解析导入内容。
     *
     * 抛出的 [IllegalArgumentException] 消息是给用户看的原话，界面可以直接显示。
     */
    fun decode(json: String): AccountTransferEnvelope {
        val text = json.trim()
        if (text.isEmpty()) throw IllegalArgumentException("内容为空，先粘贴导出内容或选择一个文件")
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalArgumentException("不是合法的 JSON")
        if (obj.stringOrNull("format") != ACCOUNT_TRANSFER_FORMAT) {
            throw IllegalArgumentException("不是本应用导出的账号文件")
        }
        val version = obj.intOrNull("version") ?: 0
        // 只认当前版本，不做向后兼容。
        //
        // 导入的语义是「把快照原样恢复」，老格式里那些没被记录的状态（启用开关、
        // 优先级、protocol）只能靠猜默认值补——恢复出来的东西跟你导出时并不是一份，
        // 这种"看起来成功了但其实不一样"比直接报错难查得多。老文件请重新导出一份。
        if (version !in READABLE_VERSIONS) {
            throw IllegalArgumentException(
                "文件是 v$version 格式，当前只支持 v${READABLE_VERSIONS.sorted().joinToString("/")}——" +
                    "请用本应用重新导出一份（老格式缺状态字段，无法完整恢复）",
            )
        }
        val array = obj.get("accounts")
        if (array == null || !array.isJsonArray) throw IllegalArgumentException("文件里没有账号列表")

        val accounts = ArrayList<AccountSnapshot>(array.asJsonArray.size())
        for (element in array.asJsonArray) {
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val providerId = item.stringOrNull("providerId").orEmpty()
            val uid = item.stringOrNull("uid").orEmpty()
            // 凭证缺失的条目直接丢掉，不要用空串造出一个「能登录但必然失败」的账号
            val secret = item.stringOrNull("secret").orEmpty()
            if (providerId.isEmpty() || uid.isEmpty() || secret.isEmpty()) continue
            accounts.add(
                AccountSnapshot(
                    account = ProviderAccount(
                        providerId = providerId,
                        uid = uid,
                        nickname = item.stringOrNull("nickname").orEmpty(),
                        secret = secret,
                    ),
                    // v1/v2 文件没有这两项：默认「启用 + 无优先级」，与账号池的默认值一致
                    enabled = item.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                    priority = item.intOrNull("priority") ?: 0,
                    customName = item.stringOrNull("customName").orEmpty(),
                ),
            )
        }
        if (accounts.isEmpty()) throw IllegalArgumentException("文件里没有可用的账号（凭证为空或字段缺失）")

        // 自定义供应商定义（v1 没有这一段，解析结果为空列表）
        val providers = ArrayList<CustomProviderConfig>()
        obj.get("providers")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val providerKey = item.stringOrNull("key").orEmpty()
            // 只卡 key：导出时本机就可能存在「只建了标签、地址还没补」的供应商，
            // 那种定义带着空地址在文件里往返时必须活下来，否则导入后又被当成不存在。
            if (providerKey.isEmpty()) return@forEach
            providers.add(
                CustomProviderConfig(
                    key = providerKey,
                    name = item.stringOrNull("name").orEmpty().ifEmpty { providerKey },
                    baseUrl = item.stringOrNull("baseUrl").orEmpty(),
                    apiKeys = emptyList(),
                    protocol = item.stringOrNull("protocol").orEmpty().ifEmpty { CustomProviderConfig.PROTOCOL_OPENAI },
                    forceStreaming = item.get("forceStreaming")?.takeIf { it.isJsonPrimitive }?.asBoolean,
                    models = item.get("models")?.takeIf { it.isJsonArray }
                        ?.asJsonArray?.mapNotNull { m -> runCatching { m.asString }.getOrNull() }
                        .orEmpty(),
                    enabled = item.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                    // 自定义请求头与余额配置也要跟着走：不然换台设备导入回来，
                    // 能登录但调不通（缺头），也不知道该去哪查余额。
                    headers = item.get("headers")?.takeIf { it.isJsonArray }
                        ?.asJsonArray?.mapNotNull { h ->
                            runCatching { io.synapse.core.gateway.HttpHeader.fromJson(h.asJsonObject) }.getOrNull()
                        }
                        .orEmpty(),
                    balanceQuery = item.get("balanceQuery")?.takeIf { it.isJsonObject }
                        ?.asJsonObject?.let { io.synapse.core.gateway.BalanceQueryConfig.fromJson(it) },
                ),
            )
        }

        return AccountTransferEnvelope(
            version = version,
            exportedAt = obj.get("exportedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            accounts = accounts,
            providers = providers,
        )
    }
}

/** 一次导入的逐条结果，供界面汇总展示。 */
data class AccountImportResult(
    val added: List<String>,
    val updated: List<String>,
    val skipped: List<String>,
    /** 本机没有、也无法从文件里补建的自定义供应商。 */
    val unknownProviders: List<String>,
    /** 本次随文件新建出来的自定义供应商标签（换机器时常见）。 */
    val createdProviders: List<String> = emptyList(),
    /**
     * [createdProviders] 里「只有标签、接口地址还空着」的那部分。
     *
     * 旧文件（v1 导出）没存地址，只能先建个占位让账号落得下去，地址要用户自己补一次；
     * 带完整定义的文件不会出现在这里。
     */
    val placeholderProviders: List<String> = emptyList(),
    /**
     * 本机已有、但被文件里的快照**改回去了**的自定义供应商。
     *
     * 导出是快照，导入就是恢复：文件里写的地址/模型/启用状态会盖到本机同名配置上。
     */
    val restoredProviders: List<String> = emptyList(),
) {
    val total: Int get() = added.size + updated.size + skipped.size + unknownProviders.size
}
