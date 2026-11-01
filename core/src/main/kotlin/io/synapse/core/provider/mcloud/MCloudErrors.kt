package io.synapse.core.provider.mcloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.util.objOrNull
import io.synapse.core.util.str

/** 上游错误的初分类，供账号层决定「重登 / 换号 / 退避 / 放弃」。 */
enum class MCloudErrorKind {
    /** Basic 凭证或 apikey 失效：需要重新登录/换 Key。 */
    SESSION_DEAD,
    /** 额度或次数用完。 */
    QUOTA,
    /** 限流，可退避。 */
    SOFT_RATE,
    /** 请求参数不合法（多为本地拼接错误）。 */
    CLIENT,
    /** 上游 5xx。 */
    SERVER,
    /** 网络层失败。 */
    NETWORK,
}

/**
 * 上游的一次业务失败。
 *
 * [message] 一律是**中文人话**：优先用上游自带的中文文案（`resultDesc` / `third.thirdDesc`），
 * 否则按分类兜底。原始报文只进 [debugSummary]，绝不出现在给用户看的文字里。
 */
class MCloudApiException(
    val status: Int,
    val kind: MCloudErrorKind,
    val body: String,
    val upstreamCode: String = "",
) : Exception(userMessage(status, kind, body, upstreamCode)) {

    fun debugSummary(): String =
        "upstream http=$status kind=$kind" +
            (if (upstreamCode.isNotEmpty()) " code=$upstreamCode" else "") +
            " body=${body.take(400)}"

    private companion object {
        fun userMessage(status: Int, kind: MCloudErrorKind, body: String, code: String): String {
            // 上游自带的中文文案最准确（「验证码不正确」「无效的ApiKey」等），优先直接用
            MCloudErrors.extractMessage(body)?.let { return it }
            return when (code) {
                MCloudConstants.CODE_APIKEY_MISSING -> "这个账号还没有生成调用密钥，请重新登录"
                MCloudConstants.CODE_APIKEY_INVALID -> "调用密钥已失效，正在尝试自动续期"
                else -> when (kind) {
                    MCloudErrorKind.SESSION_DEAD -> "登录已失效，请重新登录"
                    MCloudErrorKind.QUOTA -> "额度已用完"
                    MCloudErrorKind.SOFT_RATE -> "请求过于频繁，请稍后再试"
                    MCloudErrorKind.CLIENT -> "上游拒绝了本次请求"
                    MCloudErrorKind.SERVER -> "上游服务异常"
                    MCloudErrorKind.NETWORK -> "网络连接失败，请检查网络后重试"
                }
            }
        }
    }
}

object MCloudErrors {

    /**
     * 从各种形态的响应体里抽出「给人看的」那句文案。
     *
     * 上游有三套报错形态，都得认：
     * - 会员中心：`{resultCode, resultDesc}`
     * - 会员中心（二级）：`{resultCode, resultDesc, third:{thirdDesc}}` —— 「验证码不正确」在这里
     * - 对话：`{error:{code, message}, code, message}`
     */
    fun extractMessage(body: String): String? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        // 1) 对话接口的 error 对象
        obj.objOrNull("error")?.let { err ->
            val msg = err.str("message")
            if (isReadable(msg)) return msg
        }
        // 2) 会员中心的二级错误（验证码错误等业务失败都在这）
        obj.objOrNull("third")?.let { third ->
            val msg = third.str("thirdDesc")
            if (isReadable(msg)) return msg
        }
        // 3) 顶层 message / resultDesc / msg
        for (key in listOf("resultDesc", "message", "msg")) {
            val v = obj.str(key)
            if (isReadable(v)) return v
        }
        return null
    }

    /** 非空、长度合理、且不是报文片段时才适合展示。 */
    private fun isReadable(text: String): Boolean {
        if (text.isEmpty() || text.length > 120) return false
        if (text.startsWith("{") || text.startsWith("[") || text.startsWith("<")) return false
        return true
    }

    /**
     * 业务码；取不到返回空串。
     *
     * 两个接口用**不同的字段和不同的成功值**，这是上游的历史包袱，必须都认：
     * - 会员中心（登录/发码/拼图）：`resultCode`，成功是 `"0"`
     * - AI 服务（apikey/get）：`code`，成功是 `"0000"`（同时带 `success: true`）
     *
     * 只认一种会让另一条链路把「请求成功」当成失败——早前就是这样把登录判死的。
     */
    fun resultCodeOf(body: String): String {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        return obj.str("resultCode").ifEmpty { obj.str("code") }
    }

    /** 业务是否成功：`resultCode == "0"` 或 `code == "0000"` 都算。 */
    fun isOk(body: String): Boolean {
        val code = resultCodeOf(body)
        return code == MCloudConstants.CODE_OK || code == MCloudConstants.CODE_OK_AI
    }

    /** 从响应体里读出 `data` 对象。 */
    fun dataOf(body: String): JsonObject? =
        runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()?.objOrNull("data")

    /**
     * 把一次会员中心调用统一成「成功返回 data / 失败抛异常」。
     *
     * [action] 只用于日志与兜底文案（如「发送验证码」），不参与业务判断。
     */
    fun requireData(status: Int, body: String, action: String): JsonObject {
        if (status !in 200..299) {
            throw MCloudApiException(status, classifyStatus(status, body), body, resultCodeOf(body))
        }
        val code = resultCodeOf(body)
        if (isOk(body)) return dataOf(body) ?: JsonObject()
        throw MCloudApiException(status, classifyCode(code, body), body, code)
    }

    private fun classifyStatus(status: Int, body: String): MCloudErrorKind = when {
        status == 401 || status == 403 -> MCloudErrorKind.SESSION_DEAD
        status == 429 -> MCloudErrorKind.SOFT_RATE
        status >= 500 -> MCloudErrorKind.SERVER
        status == 0 -> MCloudErrorKind.NETWORK
        else -> MCloudErrorKind.CLIENT
    }

    private fun classifyCode(code: String, body: String): MCloudErrorKind {
        // 业务码是数字串，用区间粗判就够了：01xxx 鉴权类，5xx4xx 参数/限流类
        if (code == MCloudConstants.CODE_APIKEY_INVALID || code == MCloudConstants.CODE_UNAUTHORIZED) {
            return MCloudErrorKind.SESSION_DEAD
        }
        if (code.startsWith("0100")) return MCloudErrorKind.SESSION_DEAD
        val quotaMarkers = listOf("余额不足", "额度不足", "次数已用完", "token不足", "已超限")
        if (quotaMarkers.any { body.contains(it, ignoreCase = true) }) return MCloudErrorKind.QUOTA
        val rateMarkers = listOf("频繁", "稍后再试", "too many")
        if (rateMarkers.any { body.contains(it, ignoreCase = true) }) return MCloudErrorKind.SOFT_RATE
        return MCloudErrorKind.CLIENT
    }
}
