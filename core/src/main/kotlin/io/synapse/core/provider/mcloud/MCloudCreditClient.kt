package io.synapse.core.provider.mcloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.util.str

/**
 * 移动云盘的额度查询（AI tokens 权益）。
 *
 * ## 数据从哪来（2026-10-31 抓包实测，非猜测）
 *
 * 上游有三类额度接口，分工不同：
 *
 * | 接口 | 返回 | 用途 |
 * |---|---|---|
 * | `queryAvailableBenefitV2` | 总量 + 已用 + 权益说明 | **主数据源**，算剩余额度 |
 * | `queryMonthlyTokenConsumption` | 本月累计 tokens | 当月用量 |
 * | `getAiTokenConsumptionRecords` | 逐条消耗记录 | 明细列表（模型、系数、时间） |
 *
 * ## 认证
 *
 * 全部用**会员中心的 Basic 凭证**（不是 apikey）：
 * `Authorization: Basic base64("mobile:<手机号>:<authToken>")`，与 `apikey/get` 同一套。
 * 另外 `queryAvailableBenefitV2` 实测需要 `hcy-cool-flag: 1` 与 `x-yun-uni`（= userDomainId）。
 *
 * ## 单位
 *
 * `benefitValue` / `benefitUsed` / `totalTokens` 都是 **tokens**，不是次数。
 * 扣减按模型系数（`tokenRatio`）换算，例如 qwen3.8-Flash 系数 0.18
 * 表示实际消耗 1 token 扣 0.18。
 */
class MCloudCreditClient(
    private val benefitBase: String = MCloudConstants.BENEFIT_BASE,
    private val vipBase: String = MCloudConstants.VIP_BASE,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** 机型（core 是纯 JVM 模块，不能引用 android.os.Build，由上层注入）。 */
    private val deviceModel: String = "M610BB",
    /** 系统版本，如 `16`。 */
    private val deviceOsVersion: String = "",
) {

    /** 最近一次上游原始响应摘要（诊断用，失败时带进 trace 给用户看）。 */
    private var lastRawTrace: String = ""

    /** 最近一次会员身份查询的诊断。 */
    private var lastIdentityTrace: String = ""

    /**
     * 查账号的 AI tokens 额度。
     *
     * 返回 null 表示这次查不到（网络异常、上游改协议、凭证过期），
     * 调用方应回退到「未知」而不是显示 0——0 会让用户以为额度用完了。
     */
    fun fetchCredit(account: MCloudAccount): MCloudCredit? = fetchCreditDetailed(account).credit

    /**
     * 带诊断信息的额度查询。
     *
     * 每次上游调用都把结果写进 [MCloudCreditProbe.trace]，
     * 界面显示「查询失败」时这份 trace 就是唯一线索——之前没有日志，
     * 失败只能看到一句「查询失败，可下拉刷新重试」，完全没法定位。
     */
    fun fetchCreditDetailed(account: MCloudAccount): MCloudCreditProbe {
        val trace = mutableListOf<String>()
        if (account.basicCredential.isEmpty()) {
            trace += "Basic 凭证为空，未发请求"
            return MCloudCreditProbe(null, trace)
        }

        val benefitProbe = runCatching { queryAvailableBenefit(account) }
        val benefit = benefitProbe.getOrNull()
        benefitProbe.exceptionOrNull()?.let { trace += "权益接口异常：${it.message}" }
        benefit?.let { trace += "权益接口：总量=${it.total} 已用=${it.used} 状态=${it.status}" }
            ?: trace.add(lastRawTrace.ifEmpty { "权益接口无可用数据" })

        val monthProbe = runCatching { queryMonthlyConsumption(account) }
        val monthly = monthProbe.getOrNull()
        monthProbe.exceptionOrNull()?.let { trace += "月度接口异常：${it.message}" }

        val identityProbe = runCatching { fetchIdentity(account) }
        val identity = identityProbe.getOrNull()
        identityProbe.exceptionOrNull()?.let { trace += "会员身份接口异常：${it.message}" }
        trace += lastIdentityTrace.ifEmpty { "会员身份未查询" }

        // 「残缺」与「真是 0」必须区分开（同 antigravity 配额的两个坑）：
        // 权益接口偶发会回**残缺的成功响应**（HTTP 200 但 userAvailableBenefitList 为空/字段缺失），
        // 这时 total 算出来是 0。若直接把 total=0 当成结果，界面会显示「额度 0」——
        // 用户会以为额度用完了（真实剩余其实还有几百万），比显示「查询失败」更误导。
        // 判据用 balanceKnown（权益接口真的给了 benefitValue），而不是 total > 0。
        if (benefit == null && monthly == null) {
            trace += "两个接口都没拿到数据"
            return MCloudCreditProbe(null, trace)
        }
        if (benefit == null) trace += "权益接口没拿到额度，只有本月用量"

        val total = benefit?.total ?: 0L
        val used = benefit?.used ?: (monthly ?: 0L)
        val remain = if (benefit != null) (benefit.total - benefit.used).coerceAtLeast(0L) else 0L

        return MCloudCreditProbe(
            MCloudCredit(
                totalTokens = total,
                usedTokens = used,
                remainTokens = remain,
                balanceKnown = benefit != null,
                monthlyTokens = monthly ?: 0L,
                monthlyKnown = monthly != null,
                monthLabel = currentMonthLabel(),
                benefitDesc = benefit?.desc.orEmpty(),
                benefitStatus = benefit?.status.orEmpty(),
                memberLevel = identity?.name.orEmpty(),
                fetchedAtMillis = nowMillis(),
            ),
            trace,
        )
    }

    /** 查消耗明细（最近的 N 条）。返回空列表表示查不到，不抛异常。 */
    fun fetchRecords(account: MCloudAccount, pageSize: Int = 20): List<MCloudConsumptionRecord> {
        if (account.basicCredential.isEmpty()) return emptyList()
        val body = JsonObject().apply {
            addProperty("pageNum", 1)
            addProperty("pageSize", pageSize)
            add("action", com.google.gson.JsonArray().apply {
                add("1") // 1=消耗；4=到期清理，不算使用，这里只要消耗
            })
        }
        val (status, resp) = runCatching {
            MCloudHttp.postJson(
                url = vipBase + MCloudConstants.PATH_TOKEN_RECORDS,
                body = body.toString(),
                headers = vipHeaders(account),
            )
        }.getOrNull() ?: return emptyList()
        if (status !in 200..299) return emptyList()

        val root = runCatching { JsonParser.parseString(resp).asJsonObject }.getOrNull() ?: return emptyList()
        val arr = root.getAsJsonObject("data")
            ?.getAsJsonArray("aiTokenConsumptionRecordList")
            ?: return emptyList()
        return arr.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                MCloudConsumptionRecord(
                    modelName = o.str("modelName").ifEmpty { o.str("modelCode") },
                    tokens = o.str("benefitNum").toLongOrNull() ?: 0L,
                    coefficient = o.str("coefficient"),
                    timeText = o.str("recordTime").ifEmpty { o.str("displayRecordTime") },
                    comments = o.str("comments"),
                )
            }.getOrNull()
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 查可用权益。
     *
     * `benefitStatus` 实测 `"2"` 表示权益生效中；其它值表示未开通/已过期，
     * 这时仍返回数据（让界面显示「未生效」），但剩余量意义不大。
     */
    private fun queryAvailableBenefit(account: MCloudAccount): AvailableBenefit? {
        val body = JsonObject().apply {
            add("account", JsonObject().apply { addProperty("userDomainId", account.uid) })
            addProperty("benefitNo", MCloudConstants.BENEFIT_NO_AI_TOKENS)
        }
        val (status, resp) = MCloudHttp.postJson(
            url = benefitBase + MCloudConstants.PATH_AVAILABLE_BENEFIT,
            body = body.toString(),
            headers = vipHeaders(account),
        )
        lastRawTrace = "权益接口 HTTP $status 响应=${resp.take(400)}"
        if (status !in 200..299) return null

        // 兜底：万一下面服务端返回加密体（base64，非 JSON 开头），用 KEY_BIZ 解一层。
        // 实测该接口**确实会返回密文**（App 的 H5 页面自己解），所以这一步是主路径而非兜底。
        val plainResp = decodeIfEncrypted(resp)
        val root = runCatching { JsonParser.parseString(plainResp).asJsonObject }.getOrNull() ?: run {
            lastRawTrace = "响应既非明文 JSON 也解不开密文：${resp.take(160)}"
            return null
        }
        val list = root.getAsJsonArray("userAvailableBenefitList")
        if (list == null) {
            // 常见：resultCode 不是 0（凭证过期/无权益），把上游原话带出去
            val code = root.str("resultCode").ifEmpty { root.str("code") }
            val desc = root.str("resultDesc").ifEmpty { root.str("message") }
            lastRawTrace = "权益接口无 userAvailableBenefitList，code=$code desc=$desc"
            return null
        }
        val item = list.firstOrNull { it.isJsonObject }?.asJsonObject ?: run {
            lastRawTrace = "权益列表为空"
            return null
        }

        val total = item.str("benefitValue").toLongOrNull() ?: run {
            lastRawTrace = "benefitValue 解析失败：${item.str("benefitValue")}"
            return null
        }
        val used = item.str("benefitUsed").toLongOrNull() ?: 0L
        return AvailableBenefit(
            total = total,
            used = used,
            status = item.str("benefitStatus"),
            desc = item.str("reserve3"),
        )
    }

    /**
     * 查账号的会员身份（`vip.yun.139.com/m4c/userInfo/userIdentity`）。
     *
     * 实测响应：
     * ```
     * {"data":[{"type":"1001","typeName":"白银会员",
     *   "iconUrl":".../icon_normal_vip_silver.png"}]}
     * ```
     * 返回 null 表示查不到（未登录/凭证过期），界面不显示标签而不是显示「非会员」——
     * 拿不准的时候不该给用户错误结论。
     */
    fun fetchIdentity(account: MCloudAccount): MCloudIdentity? {
        if (account.basicCredential.isEmpty()) {
            lastIdentityTrace = "Basic 凭证为空"
            return null
        }
        val result = runCatching {
            MCloudHttp.postJson(
                url = vipBase + MCloudConstants.PATH_USER_IDENTITY,
                body = "{}",
                headers = vipHeaders(account),
            )
        }
        val pair = result.getOrNull()
        if (pair == null) {
            lastIdentityTrace = "会员身份请求异常：${result.exceptionOrNull()?.message}"
            return null
        }
        val (status, resp) = pair
        if (status !in 200..299) {
            lastIdentityTrace = "会员身份 HTTP $status"
            return null
        }
        val root = runCatching { JsonParser.parseString(decodeIfEncrypted(resp)).asJsonObject }.getOrNull()
        if (root == null) {
            lastIdentityTrace = "会员身份响应解析失败：${resp.take(120)}"
            return null
        }
        val arr = root.getAsJsonArray("data")
        if (arr == null) {
            val code = root.str("resultCode").ifEmpty { root.str("code") }
            val desc = root.str("resultDesc").ifEmpty { root.str("message") }
            lastIdentityTrace = "会员身份无 data：code=$code desc=$desc"
            return null
        }
        // 可能有多个身份（如同时是会员和家庭组管理员），取第一个有名有姓的
        val item = arr.firstOrNull { it.isJsonObject }?.asJsonObject
        if (item == null) {
            lastIdentityTrace = "会员身份列表为空"
            return null
        }
        // typeName 由服务器下发（如「白银会员」「黄金会员」），绝不硬编码
        val name = item.str("typeName")
        if (name.isEmpty()) {
            lastIdentityTrace = "typeName 为空"
            return null
        }
        lastIdentityTrace = "会员身份=$name"
        return MCloudIdentity(type = item.str("type"), name = name, iconUrl = item.str("iconUrl"))
    }

    /** 查本月累计消耗（实测是明文 JSON，不需要解密）。 */
    private fun queryMonthlyConsumption(account: MCloudAccount): Long? {
        val body = JsonObject().apply {
            add("account", JsonObject().apply { addProperty("userDomainId", account.uid) })
        }
        val (status, resp) = MCloudHttp.postJson(
            url = benefitBase + MCloudConstants.PATH_MONTHLY_CONSUMPTION,
            body = body.toString(),
            headers = vipHeaders(account),
        )
        if (status !in 200..299) return null
        val root = runCatching { JsonParser.parseString(resp).asJsonObject }.getOrNull() ?: return null
        return root.str("totalTokens").toLongOrNull()
    }

    /**
     * 会员中心系请求头。
     *
     * ⚠️ **绝不能带 `hcy-cool-flag`**：抓包里 `queryAvailableBenefitV2` 的请求没有这个头，
     * 一旦带上，服务端会把响应加密成一段 base64 密文（实测就是「解不出数据」的真因）。
     * 这与 thirdlogin 那条链路是同一个规律——别给不需要加密的接口发加密开关。
     *
     * `x-DeviceInfo` 也用 H5 形态（`||36|13.2.5||机型|uuid||android 16|||||`）：
     * 这个接口在 App 里是 WebView 发的，原生形态的指纹会被当成不同客户端。
     */
    private fun vipHeaders(account: MCloudAccount): Map<String, String> = MCloudHttp.vipHeaders(
        mapOf(
            "Authorization" to "Basic ${account.basicCredential}",
            "x-DeviceInfo" to h5DeviceInfo(),
        ),
    )

    /** H5 形态设备指纹：抓包实测 `||36|13.2.5||PJE110|<uuid>||android 16|||||`。 */
    private fun h5DeviceInfo(): String =
        "||36|13.2.5||${deviceModel}|$stableUuid||android ${deviceOsVersion}|||||"

    /** 进程内稳定的 UUID（抓包里是固定的一个，避免每次请求变来变去触发风控）。 */
    private val stableUuid: String by lazy {
        java.util.UUID.nameUUIDFromBytes("synapse-gateway-mcloud".toByteArray()).toString()
    }

    /**
     * 响应若是密文则解一层（`Base64(IV‖AES-192-CBC(KEY_BIZ))`）。
     *
     * 只在响应不是 JSON 开头时才尝试——明文响应原样返回，绝不做多余处理。
     */
    private fun decodeIfEncrypted(resp: String): String {
        val t = resp.trim().removeSurrounding("\"")
        if (t.startsWith("{") || t.startsWith("[")) return resp
        // 两种 base64 解码都要试：MIME decoder 会放过非法字符，
        // 但某些 base64（无换行的纯串）在标准 decoder 下才稳定。实测过 MIME 解不出的情况。
        val attempts = listOf<() -> String>(
            { MCloudCrypto.decryptAesIv(t, MCloudConstants.KEY_BIZ) },
            {
                val raw = java.util.Base64.getDecoder().decode(t)
                val iv = raw.copyOfRange(0, 16)
                val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(
                    javax.crypto.Cipher.DECRYPT_MODE,
                    javax.crypto.spec.SecretKeySpec(
                        MCloudConstants.KEY_BIZ.toByteArray(Charsets.US_ASCII), "AES",
                    ),
                    javax.crypto.spec.IvParameterSpec(iv),
                )
                String(cipher.doFinal(raw.copyOfRange(16, raw.size)), Charsets.UTF_8)
            },
        )
        for (a in attempts) {
            val out = runCatching(a).getOrNull() ?: continue
            if (out.trimStart().startsWith("{")) return out
        }
        lastRawTrace = "$lastRawTrace；密文解密失败"
        return resp
    }

    private fun currentMonthLabel(): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis() }
        val month = cal.get(java.util.Calendar.MONTH) + 1
        return "${cal.get(java.util.Calendar.YEAR)}-${if (month < 10) "0$month" else "$month"}"
    }

    private data class AvailableBenefit(
        val total: Long,
        val used: Long,
        val status: String,
        val desc: String,
    )
}

/** 一次额度快照。 */
data class MCloudCredit(
    /** 权益总量（tokens）。 */
    val totalTokens: Long,
    /**
     * 额度是否真的查到了（`queryAvailableBenefitV2` 给了 `benefitValue`）。
     *
     * 不能用 `totalTokens > 0` 代替：真实性未知与「确实用光」是两件事，
     * 前者要显示「未知」、后者才该显示 0。残缺的成功响应（200 但缺字段）走前者。
     */
    val balanceKnown: Boolean = true,
    /** 已使用（tokens）。 */
    val usedTokens: Long,
    /** 剩余（tokens），= 总量 - 已用。 */
    val remainTokens: Long,
    /** 本月累计消耗（tokens）。 */
    val monthlyTokens: Long,
    /** false 表示本月累计没查到（与 [usedTokens] 口径可能不同）。 */
    val monthlyKnown: Boolean,
    /** 月份标签，如 `2026-11`。 */
    val monthLabel: String,
    /** 权益说明，如 `[1000万 tokens/月]Token`。 */
    val benefitDesc: String,
    /** 权益状态码，实测 `2` = 生效中。 */
    val benefitStatus: String,
    /** 会员身份名（如「白银会员」）；空串表示查不到，界面不显示标签。 */
    val memberLevel: String = "",
    val fetchedAtMillis: Long,
) {
    val isActive: Boolean get() = benefitStatus == "2"

    /** 剩余百分比（0..100），总量为 0 时返回 0。 */
    val remainPercent: Int
        get() = if (totalTokens <= 0) 0 else ((remainTokens * 100) / totalTokens).toInt().coerceIn(0, 100)
}

/** 一条消耗明细。 */
data class MCloudConsumptionRecord(
    val modelName: String,
    val tokens: Long,
    val coefficient: String,
    val timeText: String,
    val comments: String,
)

/** 会员身份（如「白银会员」）。 */
data class MCloudIdentity(
    /** 上游类型码，实测 `1001` = 白银会员。 */
    val type: String,
    /** 展示名，如「白银会员」。 */
    val name: String,
    /** 身份图标 URL（可能为空）。 */
    val iconUrl: String = "",
)

/**
 * 带诊断信息的额度查询结果。
 *
 * [trace] 是排查用的旁路信息：界面显示「额度查询失败」时，
 * 这份 trace 会被写进网关日志，否则失败只能看到一句提示、毫无线索。
 */
data class MCloudCreditProbe(
    val credit: MCloudCredit?,
    val trace: List<String>,
)
