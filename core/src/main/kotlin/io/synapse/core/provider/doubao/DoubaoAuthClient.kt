package io.synapse.core.provider.doubao

import com.google.gson.JsonObject
import io.synapse.core.util.NetworkErrorText
import io.synapse.core.util.objOrNull
import io.synapse.core.util.stringOrNull
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 设备票据提供者（发码设备注册链的可扩展接入口）。
 *
 * ## 为什么不直接把六件套硬编码进来
 *
 * 真机实测：`bd-ticket-guard-client-cert` / `-ree-public-key` / `-server-cert-sn`
 * / `-static-sign` / `-static-ts-sign` / `-sec-ts` **每次登录由设备注册链动态下发**，
 * 且 `static-sign` 是 TEE（硬件安全芯片）ECDSA 签名，纯服务端凭空复刻不出来。
 * 写死一组过期值只会让 `Bd-Ticket-Guard-Result` 裁决位更差（实测 1103/1104/3）。
 *
 * 所以这里**默认返回空**：没有真值时宁可不带 guard 头，也不发假头骗自己。
 * 真机注册链打通后，把「实时取到的六件套」通过 [headers] 注入即可，
 * 发码逻辑一行都不用改。这是「能静态落地、不依赖硬件」的那一部分。
 */
fun interface DeviceGuardHeaders {
    /** 返回本次发码要带的 guard 头；没有真值就返回空 map。 */
    fun headers(): Map<String, String>
}

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
 * 网关侧「选中账号」token 走 `msgid` 或 `code` 两个通道之一（见 [extractSelToken]），
 * 与 [io.synapse.core.provider.SmsLoginSupport] 的入参形态保持兼容。
 */
class DoubaoAuthClient(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    /** 设备票据注入口；默认空实现 = 不带 guard 头（见 [DeviceGuardHeaders]）。 */
    private val guardHeaders: DeviceGuardHeaders = DeviceGuardHeaders { emptyMap() },
) {

    /** 「选中账号」的 token 前缀：`sel:<sec_uid>`。 */
    companion object {
        const val SEL_PREFIX = "sel:"
        private const val BASE = DoubaoProvider.BASE_URL
        private const val XOR_KEY = 0x05
        private const val APP_NAME = "nova_ai"
        private const val PASSPORT_SUPPORT_FLOW = "captcha,passport_user_ban,verify"

        /**
         * 发码请求**不带 csrf**。
         *
         * 真机埋点实测 `request_csrf_token=0`（发码请求不带 csrf）、
         * `response_csrf_token=1`（csrf 是响应才下发的，存进 CsrfTokenManager_sp.xml）。
         * 早期这里写死一个 hex 值，等于「带了不该带的头」，与抓包相反 —— 已被实测推翻，
         * 改成空串（空串时整个头省略，服务端按未登录会话处理，才是正确起点）。
         */
        private const val CSRF = ""

        /**
         * 固定设备身份三件套。
         *
         * ⚠️ 实测这三项**每次登录由设备注册链动态下发**（不同会话 cdid 不同），
         * 写死会被判成「复用旧设备」而触发风控。但独立网关 App 没有豆包的设备注册链
         * （`get_ticket_no_server_cert` → `get_client_cert` → TEE 静态签名），拿不到动态值；
         * Python 对照实验里「换全新随机指纹」直接 403（guard 注册链缺失），比固定值更糟。
         * 所以这里**保持固定、如实标注边界**，不假装能复刻，也不添加填假值的 guard 头
         * （假头只会让服务端裁决位更差）。
         */
        private const val DEVICE_CDID = "8d39bab5-a4ac-41b2-b9b2-53fccbb889df"
        private const val DEVICE_IID = "393003648174026"
        private const val DEVICE_ID = "1923490807555162"

        /**
         * 从 [io.synapse.core.provider.SmsLoginSupport.loginBySmsCode] 的两个入参通道
         * 提取「选中账号」token；都没有返回空串（= 普通验证码登录）。
         *
         * ## 为什么要两个通道都认
         *
         * 多账号对话框调的是 `onSmsLogin(id, phone, selMsgid, ccode)` ——
         * 按 `ProviderUiActions` 的签名，第 3 个参数是 **code**，不是 msgid。
         * 所以 `sel:<sec_uid>` 实际落在 `code` 上；而 `msgid` 里存的是发码时
         * 的 encodedMobile（hex）。旧实现只判 msgid ⇒ 选号永远失败，且会把
         * `sel:xxx` 当成验证码 XOR 后发给上游。抽成纯函数后，这个判据可单测锁死。
         *
         * 两个通道都带 sel 时以 msgid 为准（标准通道优先）。
         */
        fun extractSelToken(msgid: String, code: String): String = when {
            msgid.startsWith(SEL_PREFIX) -> msgid
            code.startsWith(SEL_PREFIX) -> code
            else -> ""
        }

        /** 每字节 XOR 0x05，再转 hex（小写）。空串返回空串。 */
        fun xorEncode(s: String): String =
            s.toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xFF xor XOR_KEY) }

        /**
         * [xorEncode] 的逆操作。
         *
         * 注意：hex → 字节之后**还要再 XOR 一次**（XOR 是自逆运算）。
         * 只做 hex 解码不做异或，得到的是密文的可读乱码 —— 看起来像
         * 「能读的 ASCII」，最容易骗过肉眼 review。
         */
        fun xorDecode(hex: String): String {
            if (hex.length % 2 != 0) return ""
            val bytes = ByteArray(hex.length / 2)
            for (i in bytes.indices) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) return ""
                bytes[i] = (((hi shl 4) or lo) xor XOR_KEY).toByte()
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
        // X-SS-STUB = MD5(body).upper()。抓包逐字节验证过（不是随机值）：
        //   body(235B) → D1E29E3416F5007C8002FAD64452DCA4 == 抓包的 STUB。
        // 之前注释写「实测不需要 STUB」是被坏抓包脚本骗了，已推翻。
        val headers = buildLoginHeaders() + ("X-SS-STUB" to md5Upper(body))
        val resp = post("passport/mobile/send_code/v1/", body, headers)
        val data = resp.data
        if (resp.status !in 200..299) {
            throw DoubaoLoginException("发码失败：HTTP ${resp.status} ${data.take(200)}")
        }
        // HTTP 200 不代表发码成功：上游用 body 里的 error_code 报业务错误
        // （实测 error_code=7「访问太频繁」、error_code!=0 都回 200）。只判状态码
        // 会把「拒绝发码」当成「已发送」，用户点完没收到短信，看起来就是「没反应」。
        // 注意：7 的字面文案是"频繁"，但真机对照（退出重登豆包即可恢复）表明它实际
        // 受会话 / 设备票据状态支配，不是单纯的 IP 或时间窗频控；排障见 docs/08。
        val json = parse(data)
        val bizCode = json?.let { root ->
            root.get("error_code")?.takeIf { it.isJsonPrimitive }?.asInt
                ?: root.objOrNull("data")?.get("error_code")?.takeIf { it.isJsonPrimitive }?.asInt
        }
        if (bizCode != null && bizCode != 0) {
            val desc = json?.objOrNull("data")?.stringOrNull("description")
                ?: json?.stringOrNull("description").orEmpty()
            throw DoubaoLoginException("发码失败（error_code=$bizCode）：${desc.ifEmpty { "上游拒绝发送" }}")
        }
        return SendResult(encodedMobile = encodedMobile)
    }

    // ------------------------------------------------------------------ 登录

    /**
     * 验证码登录。
     *
     * [msgid] 直接传 [SendResult.encodedMobile]；多账号选号时把 `sel:<sec_uid>`
     * 放在 [msgid]（[loginSelect] 就是这么调的），`mobileTicket` / `smsCodeKey` 一起带上。
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
        val resp = post("passport/auth/one_login/", form(*body.toTypedArray()), buildLoginHeaders())

        if (resp.status !in 200..299) {
            throw DoubaoLoginException("登录失败：HTTP ${resp.status} ${resp.data.take(200)}")
        }

        val data = parse(resp.data) ?: throw DoubaoLoginException("登录响应不是 JSON：${resp.data.take(200)}")
        val errorCode = data.get("error_code")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: data.objOrNull("data")?.get("error_code")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: 0

        return when {
            errorCode == 0 -> {
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
            "cdid" to DEVICE_CDID,
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
            "sys_region" to "CN",
            "tz_name" to "Asia/Shanghai",
            "system_language_detail" to "zh-CN",
            "is_new_user" to "0",
            "region" to "CN",
            "lang" to "zh",
            "dora_bond_count" to "0",
            "has_dora_device" to "0",
            "pkg_type" to "release_version",
            "iid" to DEVICE_IID,
            "device_id" to DEVICE_ID,
            "doubao_account_sdk_version" to "1.0.3-alpha.0",
        )
        return params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    /**
     * 通用登录头（发码 / 换 token 共用）。
     *
     * - 实测**不需要** `X-SS-STUB`（只在开发者平台出现）；
     * - `x-tt-passport-csrf-token`：发码请求 `request_csrf_token=0` ⇒ **不带**，
     *   csrf 是响应才下发的。CSRF 为空串时整个头省略，不发空值骗自己；
     * - `bd-ticket-guard-version: 3` 只是协议版本声明，**不等于**六件套齐了。
     *   真正决定 403→200 的是 [guardHeaders] 注入的动态票据。
     */
    private fun anonymousHeaders(): Map<String, String> = buildMap {
        put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        put("User-Agent", "com.larus.nova/15070040 (Linux; U; Android 16; zh_CN)")
        put("x-tt-request-tag", "t=0;n=0")
        if (CSRF.isNotEmpty()) put("x-tt-passport-csrf-token", CSRF)
        put("X-SS-REQ-TICKET", nowMillis().toString())
        put("x-vc-bdturing-sdk-version", "4.1.1.cn")
        put("agent-version", "old")
        put("bd-ticket-guard-version", "3")
        // 以下 5 个是抓包确定的常量头：真机 send_code/v1 与 account/info/v2 都带，
        // 且不含随机性（会话级的 Cookie 由服务端下发，这里不带空值骗自己）。
        put("x-ttnet-bypass-to", "-1")
        put("sdk-version", "2")
        put("passport-sdk-version", "60594")
        put("passport-sdk-settings", "x-tt-token,sec_user_id,device_transfer_s_0,device_transfer_ab_0")
        put("passport-sdk-sign", "x-tt-token,sec_user_id")
    }

    /**
     * 发码头 = 通用头 + 设备票据注入。
     *
     * 公开是为了：① 设备注册链打通后可直接复用/核对头集合；
     * ② 单测能锁死「没真值就不发假 guard 头」这条纪律，不依赖发网络请求。
     */
    fun buildLoginHeaders(): Map<String, String> =
        anonymousHeaders() + guardHeaders.headers()

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

    /** `X-SS-STUB = MD5(body).upper()`，抓包逐字节验证。 */
    internal fun md5Upper(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it) }

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
) : Exception(toMessage(accounts, hintMsgid)) {

    companion object {
        const val MESSAGE = "MULTI_ACCOUNT"

        /**
         * 序列化候选列表进 message（管线用 message 传错误，结构体到这里就丢了）：
         * `MULTI_ACCOUNT|<hint>|<sec_uid>|<名称>|<sec_uid>|<名称>...`
         *
         * 名称里若含 `|` 会破坏格式 —— 显示名做替换兜底。
         */
        private fun toMessage(accounts: List<DoubaoAuthClient.SubAccount>, hint: String): String {
            val parts = accounts.joinToString("|") { a ->
                val name = (a.name.ifEmpty { a.secUid.takeLast(6) }).replace("|", "/")
                "${a.secUid}|$name"
            }
            return "$MESSAGE|$hint|$parts"
        }

        /** 从 [DoubaoMultiAccountException.message] 解回候选列表；格式不符返回 null。 */
        fun parse(message: String?): Pair<String, List<DoubaoAuthClient.SubAccount>>? {
            if (message == null || !message.startsWith(MESSAGE)) return null
            val seg = message.split("|")
            if (seg.size < 3) return null
            val hint = seg[1]
            val subs = ArrayList<DoubaoAuthClient.SubAccount>()
            var i = 2
            while (i + 1 < seg.size) {
                subs += DoubaoAuthClient.SubAccount(secUid = seg[i], name = seg[i + 1], avatar = "", tenantName = "", isLoggedIn = false)
                i += 2
            }
            if (subs.isEmpty()) return null
            return hint to subs
        }
    }
}
