package io.synapse.core.provider.doubao

import com.google.gson.JsonObject
import io.synapse.core.util.NetworkErrorText
import io.synapse.core.util.objOrNull
import io.synapse.core.util.stringOrNull
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.util.UUID

/**
 * 豆包登录客户端：手机号 + 短信验证码。
 *
 * ## 协议来源（全部实测，见 docs/17）
 *
 * - `mix_mode=1` 的混淆 = **每字节 XOR 0x05**（手机号/验证码/type 都验证过）
 * - `mobile` 必须带 `+86` 前缀再混淆
 * - `sms_code_key` 也要混淆；`mobile_ticket` / `sec_uid` 原样
 * - 登录链路**不需要 X-SS-STUB**（只在开发者平台出现）
 *
 * ## 多账号的两种形态
 *
 * 实测 `one_login` 有两次调用：
 *
 * 1. **第一次**（校验验证码）：不带 `mobile_ticket`。响应可能给
 *    `error_code=1454` + `mobile_ticket` + `sub_account[]`（多账号），
 *    或直接成功（单账号）。
 * 2. **第二次**（多账号选号）：带 `mobile_ticket` + `sec_uid` + `sms_code_key`，**不带 code**。
 *
 * 网关侧用 `msgid` 通道承载「选中账号的 sec_uid」（前缀 `sel:`），
 * 与 [io.synapse.core.provider.SmsLoginSupport] 的 msgid 语义兼容。
 */
class DoubaoAuthClient(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /** 「选中账号」的 msgid 前缀：`sel:<sec_uid>`。 */
    companion object {
        const val SEL_PREFIX = "sel:"
        private const val BASE = DoubaoProvider.BASE_URL
        private const val XOR_KEY = 0x05
        private const val APP_NAME = "nova_ai"
        private const val PASSPORT_SUPPORT_FLOW = "captcha,passport_user_ban,verify"
        /** 实测请求头里的 csrf 是登录前会话的固定值；服务器侧无法复现，先用固定占位。 */
        private const val CSRF = "a94476b1e02f2fd3c0d4137f86f48562"
        /** 设备 cdid（与公共 query 一致，成对保存防风控）。 */
        private const val DEVICE_CDID = "8d39bab5-a4ac-41b2-b9b2-53fccbb889df"

        /** 每字节 XOR 0x05，再转 hex（小写）。空串返回空串。 */
        fun xorEncode(s: String): String =
            s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xFF xor XOR_KEY) }

        /** [xorEncode] 的逆操作。 */
        fun xorDecode(hex: String): String {
            val bytes = ByteArray(hex.length / 2)
            for (i in bytes.indices) {
                bytes[i] = ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    /** [io.synapse.core.provider.SmsLoginSupport.sendSmsCode] 的返回：拿到的中间凭证。 */
    data class SendResult(
        /** 混淆后的手机号（hex），登录时原样回传。 */
        val encodedMobile: String,
        /** 上游会话参数，登录时回传；单账号时可能为空。 */
        val smsCodeKey: String = "",
    )

    /** 多账号候选。 */
    data class SubAccount(
        val secUid: String,
        val name: String,
        val avatar: String,
        val tenantName: String,
        val isLoggedIn: Boolean,
    )

    /** `one_login` 的结构化结果。 */
    sealed class LoginOutcome {
        /** 单账号直接成功。 */
        data class Success(val cookie: String, val credential: DoubaoCredential) : LoginOutcome()

        /** 多账号：需要用户选一个再用 [loginSelect] 继续。 */
        data class MultiAccount(
            val mobileTicket: String,
            val smsCodeKey: String,
            val subAccounts: List<SubAccount>,
        ) : LoginOutcome()
    }

    // ------------------------------------------------------------------ 发码

    /**
     * 发送短信验证码。
     *
     * [phone] 为用户输入的原始号码（可带或不带 +86，内部归一）。
     * 返回 [SendResult]，其中的 `encodedMobile` 就是登录时的 `msgid` 载体。
     */
    fun sendSmsCode(phone: String, ccode: String = "86"): SendResult {
        val normalized = normalizePhone(phone, ccode)
        val encodedMobile = xorEncode(normalized)
        val body = form(
            "terminal_type" to "3",
            "app_name" to APP_NAME,
            "auto_read" to "0",
            "account_sdk_source" to "app",
            "passport_support_flow" to PASSPORT_SUPPORT_FLOW,
            "unbind_exist" to "35",
            "mix_mode" to "1",
            "os_type" to "4",
            "mobile" to encodedMobile,
            "is6Digits" to "1",
            "multi_login" to "1",
            "type" to xorEncode("24"),
        )
        val resp = post("passport/mobile/send_code/v1/", body, anonymousHeaders())
        val data = resp.data
        // 发码成功判据：response code 0（实测响应结构未抓到，宽松处理）
        if (resp.status !in 200..299) {
            throw DoubaoLoginException("发码失败：HTTP ${resp.status} ${data.take(200)}")
        }
        return SendResult(encodedMobile = encodedMobile)
    }

    // ------------------------------------------------------------------ 登录

    /**
     * 验证码登录。
     *
     * [msgid] 来自 [sendSmsCode] 的调用方保存（实为 encodedMobile 的载体说明，见下），
     * 或 `sel:<sec_uid>` 表示多账号选号。
     *
     * 实现约定：[msgid] 直接传 [SendResult.encodedMobile]；
     * 多账号时传 `sel:<sec_uid>` 并把 `mobileTicket`/`smsCodeKey` 一起带上。
     */
    fun loginBySmsCode(
        phone: String,
        code: String,
        msgid: String,
        ccode: String = "86",
        mobileTicket: String = "",
        smsCodeKey: String = "",
    ): LoginOutcome {
        val normalized = normalizePhone(phone, ccode)
        val encodedMobile = xorEncode(normalized)
        val selecting = msgid.startsWith(SEL_PREFIX)
        val body = buildList {
            add("terminal_type" to "3")
            add("auth_opposite" to "1")
            add("app_name" to APP_NAME)
            if (selecting) {
                add("mobile_ticket" to mobileTicket)
                add("sec_uid" to msgid.removePrefix(SEL_PREFIX))
                if (smsCodeKey.isNotEmpty()) add("sms_code_key" to xorEncode(smsCodeKey))
            } else {
                add("code" to xorEncode(code))
            }
            add("account_sdk_source" to "app")
            add("passport_support_flow" to PASSPORT_SUPPORT_FLOW)
            add("mix_mode" to "1")
            add("os_type" to "4")
            add("mobile" to encodedMobile)
            add("multi_login" to "1")
        }
        val resp = post("passport/auth/one_login/", form(*body.toTypedArray()), anonymousHeaders())

        if (resp.status !in 200..299) {
            throw DoubaoLoginException("登录失败：HTTP ${resp.status} ${resp.data.take(200)}")
        }

        val data = parse(resp.data) ?: throw DoubaoLoginException("登录响应不是 JSON：${resp.data.take(200)}")
        val errorCode = data.get("error_code")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: data.objOrNull("data")?.get("error_code")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: 0

        return when {
            errorCode == 0 || errorCode == null -> {
                val setCookie = resp.setCookie
                if (setCookie.isEmpty()) {
                    throw DoubaoLoginException("登录成功但没有下发 Cookie（HTTP ${resp.status}）")
                }
                val cookie = mergeCookies(setCookie, existingCookie = "")
                val uid = DoubaoCredential.parse(cookie)?.uid.orEmpty()
                val cdid = DEVICE_CDID
                LoginOutcome.Success(
                    cookie = cookie,
                    credential = DoubaoCredential(
                        cookie = cookie,
                        uid = uid,
                        cdid = cdid,
                        deviceId = "",
                    ),
                )
            }
            errorCode == 1454 -> {
                val d = data.objOrNull("data") ?: data
                val ticket = d.stringOrNull("mobile_ticket").orEmpty()
                val key = d.stringOrNull("sms_code_key").orEmpty()
                val subs = ArrayList<SubAccount>()
                val arr = d.get("sub_account")?.takeIf { it.isJsonArray }?.asJsonArray
                arr?.forEach { el ->
                    val o = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    subs += SubAccount(
                        secUid = o.stringOrNull("sec_uid").orEmpty(),
                        name = o.stringOrNull("name").orEmpty(),
                        avatar = o.stringOrNull("avatar").orEmpty(),
                        tenantName = o.stringOrNull("tenant_name").orEmpty()
                            .ifEmpty { o.stringOrNull("tenantName").orEmpty() },
                        isLoggedIn = o.get("is_logged_in")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                    )
                }
                if (ticket.isEmpty() || subs.isEmpty()) {
                    throw DoubaoLoginException("多账号响应缺少 mobile_ticket 或 sub_account：${d}")
                }
                LoginOutcome.MultiAccount(ticket, key, subs)
            }
            else -> {
                val desc = data.objOrNull("data")?.stringOrNull("description")
                    ?: data.stringOrNull("description").orEmpty()
                throw DoubaoLoginException("登录失败（error_code=$errorCode）：${desc.ifEmpty { data.toString().take(200) }}")
            }
        }
    }

    /** 多账号选号：用 mobile_ticket + sec_uid 完成第二次 one_login。 */
    fun loginSelect(
        phone: String,
        ccode: String,
        msgid: String,
        mobileTicket: String,
        smsCodeKey: String,
    ): LoginOutcome = loginBySmsCode(phone, code = "", msgid = msgid, ccode = ccode,
        mobileTicket = mobileTicket, smsCodeKey = smsCodeKey)

    // ------------------------------------------------------------------ HTTP

    private data class Resp(val status: Int, val data: String, val setCookie: List<String>)

    private fun post(path: String, body: String, headers: Map<String, String>): Resp {
        val conn = try {
            io.synapse.core.util.HttpSupport.open(
                url = "$BASE/$path?${commonQuery()}",
                method = "POST",
                headers = headers,
                body = body,
                connectTimeoutMs = 15_000,
                readTimeoutMs = 20_000,
            )
        } catch (e: Exception) {
            throw DoubaoLoginException("网络异常：${NetworkErrorText.describe(e, HOST)}")
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            throw DoubaoLoginException("网络异常：${NetworkErrorText.describe(e, HOST)}")
        }
        val bodyText = runCatching { io.synapse.core.util.HttpSupport.readBody(conn) }.getOrDefault("")
        val setCookie = conn.headerFields?.get("set-cookie")
            ?.map { it.toString() }
            ?: conn.headerFields?.get("Set-Cookie")?.map { it.toString() }
            ?: emptyList()
        conn.disconnect()
        return Resp(status, bodyText, setCookie)
    }

    /** 登录时的公共 query（实测：user_is_login=0 & uid=0）。 */
    private fun commonQuery(): String {
        val params = linkedMapOf(
            "passport-sdk-version" to "60594",
            "use_new_token_expire_rule" to "true",
            "request_from_account_sdk" to "1",
            "is_from_ttaccountsdk" to "1",
            "device_platform" to "android",
            "os" to "android",
            "ssmix" to "a",
            "_rticket" to nowMillis().toString(),
            "cdid" to "8d39bab5-a4ac-41b2-b9b2-53fccbb889df",
            "channel" to "oppo_482431_64",
            "aid" to "482431",
            "app_name" to APP_NAME,
            "version_code" to "15070001",
            "version_name" to "15.7.0",
            "manifest_version_code" to "15070040",
            "update_version_code" to "15070040",
            "resolution" to "1080*2208",
            "dpi" to "444",
            "device_type" to "PJE110",
            "device_brand" to "OnePlus",
            "language" to "zh",
            "os_api" to "36",
            "os_version" to "16",
            "ac" to "wifi",
            "dora_status" to "0",
            "app_language" to "zh",
            "carrier_region" to "cn",
            "flow_app_variant" to "doubao",
            "user_is_login" to "0",
            "uid" to "0",
            "is_new_user" to "0",
            "region" to "CN",
            "lang" to "zh",
            "dora_bond_count" to "0",
            "has_dora_device" to "0",
            "pkg_type" to "release_version",
            "iid" to "393003648174026",
            "device_id" to "1923490807555162",
            "doubao_account_sdk_version" to "1.0.3-alpha.0",
        )
        return params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    /** 登录请求头（实测：无 X-SS-STUB；csrf 用固定占位即可过）。 */
    private fun anonymousHeaders(): Map<String, String> = mapOf(
        "Content-Type" to "application/x-www-form-urlencoded",
        "User-Agent" to "com.larus.nova/15070040 (Linux; U; Android 16; zh_CN)",
        "x-tt-request-tag" to "t=0;n=0",
        "x-tt-passport-csrf-token" to CSRF,
        "X-SS-REQ-TICKET" to nowMillis().toString(),
        "x-vc-bdturing-sdk-version" to "4.1.1.cn",
        "agent-version" to "old",
        "bd-ticket-guard-version" to "3",
    )

    private fun mergeCookies(setCookie: List<String>, existingCookie: String): String {
        val map = LinkedHashMap<String, String>()
        existingCookie.split(';').forEach { part ->
            val t = part.trim(); val eq = t.indexOf('=')
            if (eq > 0) map[t.substring(0, eq).trim()] = t.substring(eq + 1).trim()
        }
        for (raw in setCookie) {
            val first = raw.split(';').firstOrNull()?.trim() ?: continue
            val eq = first.indexOf('=')
            if (eq > 0) map[first.substring(0, eq).trim()] = first.substring(eq + 1).trim()
        }
        return map.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private fun parse(s: String): JsonObject? =
        runCatching { com.google.gson.JsonParser.parseString(s).asJsonObject }.getOrNull()

    /** 手机号归一：去掉空格与连字符；无 +86 时按 ccode 补。 */
    private fun normalizePhone(phone: String, ccode: String): String {
        val digits = phone.replace(" ", "").replace("-", "")
        return when {
            digits.startsWith("+") -> digits
            digits.startsWith(ccode) && digits.length > ccode.length + 5 -> "+$digits"
            else -> "+$ccode$digits"
        }
    }

    private val HOST get() = "api-normal.doubao.com"

}

/** 登录失败异常：message 直接可展示给用户。 */
class DoubaoLoginException(message: String) : Exception(message)

/**
 * 该手机号绑定了多个账号（`error_code=1454`）。
 *
 * 不是错误，是**需要用户做选择**的正常分支。UI 端捕获后展示
 * [accounts] 让用户挑一个，再以 [hintMsgid]（`sel:<sec_uid>`）重新调
 * [io.synapse.core.provider.SmsLoginSupport.loginBySmsCode] 完成登录。
 */
class DoubaoMultiAccountException(
    val accounts: List<DoubaoAuthClient.SubAccount>,
    val mobileTicket: String,
    val smsCodeKey: String,
    /** 建议用户选第一个账号时用的 msgid；UI 可按用户实际选择构造。 */
    val hintMsgid: String,
) : Exception(MESSAGE) {
    companion object {
        const val MESSAGE = "MULTI_ACCOUNT"
    }
}
