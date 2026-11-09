package io.synapse.core.provider.cline

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.provider.DeviceAuthPoll
import io.synapse.core.provider.DeviceAuthTicket
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderHooks
import io.synapse.core.util.HttpSupport
import io.synapse.core.util.NetworkErrorText
import io.synapse.core.util.bodySnippet
import io.synapse.core.util.jwtClaim
import io.synapse.core.util.jwtClaimLong
import io.synapse.core.util.objOrNull
import io.synapse.core.util.stringOrNull

/**
 * Cline 的设备授权登录（WorkOS RFC 8628）。
 *
 * ## 四步链路（全部实测核对）
 *
 * ```text
 * 1. POST {workos}/user_management/authorize/device   → device_code + user_code
 * 2. 用户在浏览器里输 user_code 并确认
 * 3. POST {workos}/user_management/authenticate       → WorkOS 令牌对（轮询）
 * 4. POST {api}/auth/register                         → Cline 会话令牌
 * ```
 *
 * **第 4 步不能省**：WorkOS 的令牌不能直接打 LLM 接口，必须经 `/auth/register`
 * 换成本家会话令牌（带 `workos:` 前缀 + 一套自己的 refresh token）。
 *
 * ## 为什么两个 WorkOS 端点用 form 编码而不是 JSON
 *
 * WorkOS 只认 `application/x-www-form-urlencoded`。发成 JSON 的表现是
 * 400 `invalid_request`，与「授权码不对」难以区分 —— 所以 [formPost] 单独存在，
 * 不复用项目里按 JSON 发请求的公共工具。
 *
 * ## 轮询语义（RFC 8628）
 *
 * - `authorization_pending` / `slow_down` → 继续轮询（不是错误）
 * - `expired_token` → 授权码过期，必须重新发起
 * - `access_denied` → 用户取消了
 *
 * `slow_down` 本应把间隔变长，但这里**有意接受这个降级**：上游只是继续回
 * `authorization_pending`，不会失败。
 *
 * ## `state` 里存的是 `device_code`
 *
 * 我方 [DeviceAuthTicket.state] 是「后续轮询的凭据」，正好对应 `device_code`。
 * 不额外存任何服务端状态 —— 网关重启后拿着 `state` 仍能继续轮询。
 */
internal class ClineLogin(
    private val hooks: ProviderHooks,
    private val nowMillis: () -> Long,
    /**
     * WorkOS 与 Cline API 的基址。默认走真实上游；测试注入本地假服务。
     *
     * 与 `ClineProvider(apiBaseUrl = ...)` 同一做法 —— 登录流程的四步链路
     * 不测就只能靠真机手工点，而这条链路有 4 个状态分支（pending / 过期 /
     * 拒绝 / 成功），手工点一遍覆盖不完。
     */
    private val workosBaseUrl: String = WORKOS_BASE_URL,
    private val apiBaseUrl: String = API_BASE_URL,
) {

    /**
     * 第一步：发起设备授权，返回「去哪个页面输码」+ 轮询凭据。
     *
     * 优先用 `verification_uri_complete`（码已预填，用户少一步）。
     * 上游两个字段都缺时退回 [DEVICE_VERIFY_FALLBACK]，而不是抛异常 ——
     * 让用户至少能看到一个可打开的页面。
     */
    fun start(): DeviceAuthTicket {
        val body = "client_id=$WORKOS_CLIENT_ID"
        val (status, text) = formPost("$workosBaseUrl/user_management/authorize/device", body)
        if (status !in 200..299) {
            val detail = errorDetailOf(text)
            throw IllegalStateException("Cline 设备授权失败（HTTP $status）：$detail")
        }
        val payload = parseObject(text) ?: throw IllegalStateException("Cline 设备授权响应不是合法 JSON")
        val deviceCode = payload.stringOrNull("device_code").orEmpty().trim()
        val userCode = payload.stringOrNull("user_code").orEmpty().trim()
        if (deviceCode.isEmpty() || userCode.isEmpty()) {
            throw IllegalStateException("Cline 设备授权响应缺少 device_code 或 user_code")
        }
        val verification = payload.stringOrNull("verification_uri_complete").orEmpty().trim()
            .ifEmpty { payload.stringOrNull("verification_uri").orEmpty().trim() }
            .ifEmpty { DEVICE_VERIFY_FALLBACK }
        val expiresIn = payload.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: DEFAULT_EXPIRES_SECONDS
        hooks.onLog("Cline 设备授权已发起：请在浏览器打开授权页并输入授权码 $userCode（$expiresIn 秒内有效）")
        // state = device_code：轮询只需它，网关不需要保存任何额外状态。
        return DeviceAuthTicket(loginUrl = verification, state = deviceCode)
    }

    /**
     * 第三步 + 第四步：轮询一次。
     *
     * 成功时**顺手完成第四步**（`/auth/register` 换码），调用方拿到的就是可直接
     * 打上游的会话令牌 —— 把「还差一步」这种中间态暴露给调用方，只会让每个调用点
     * 都要记得再换一次码。
     */
    fun poll(deviceCode: String): DeviceAuthPoll {
        if (deviceCode.isBlank()) return DeviceAuthPoll.Failed("Cline 登录流程已失效，请重新发起登录")
        val body = buildString {
            append("grant_type=").append(GRANT_TYPE)
            append("&device_code=").append(deviceCode)
            append("&client_id=").append(WORKOS_CLIENT_ID)
        }
        val (status, text) = try {
            formPost("$workosBaseUrl/user_management/authenticate", body)
        } catch (e: Exception) {
            return DeviceAuthPoll.Failed(NetworkErrorText.describe(e, HOST))
        }
        if (status !in 200..299) {
            return when (val errorCode = errorCodeOf(text)) {
                // 还没确认 / 请求太快：都只是「继续等」，当错误会让用户刚授权完就看到报错
                "authorization_pending", "slow_down" -> DeviceAuthPoll.Pending
                "expired_token" -> DeviceAuthPoll.Failed("Cline 登录超时（授权码已过期），请重新发起登录")
                "access_denied" -> DeviceAuthPoll.Failed("Cline 登录被拒绝（在授权页取消了）")
                else -> DeviceAuthPoll.Failed(
                    "Cline 登录失败（HTTP $status）：${errorDetailOf(text, errorCode)}",
                )
            }
        }
        val payload = parseObject(text) ?: return DeviceAuthPoll.Failed("Cline 登录响应不是合法 JSON")
        val workosAccess = payload.stringOrNull("access_token").orEmpty().trim()
        val workosRefresh = payload.stringOrNull("refresh_token").orEmpty().trim()
        if (workosAccess.isEmpty()) {
            return DeviceAuthPoll.Failed("Cline 登录响应缺少 access_token")
        }
        return try {
            DeviceAuthPoll.Success(registerSession(workosAccess, workosRefresh))
        } catch (e: Exception) {
            DeviceAuthPoll.Failed(e.message ?: "Cline 令牌登记失败")
        }
    }

    /**
     * 第四步：把 WorkOS 令牌换成 Cline 会话令牌。
     *
     * 响应里的 `accessToken` **不带** `workos:` 前缀，这里补上（与续期接口同一口径）。
     * `refreshToken` 上游可能不给，缺失时沿用 WorkOS 的那个 —— 否则账号存下来
     * 就没有续期能力了。
     */
    private fun registerSession(workosAccess: String, workosRefresh: String): ProviderAccount {
        val body = JsonObject().apply {
            addProperty("accessToken", workosAccess)
            addProperty("refreshToken", workosRefresh)
        }.toString()
        val (status, text) = postJson(
            "$apiBaseUrl/auth/register",
            body,
            mapOf(
                "Accept" to "application/json",
                "Content-Type" to "application/json",
                // 产品面标识头：缺了它部分端点按非官方客户端处理
                "X-CLIENT-TYPE" to CLIENT_TYPE,
            ),
        )
        if (status !in 200..299) {
            throw IllegalStateException("Cline 令牌登记失败（HTTP $status）：${errorDetailOf(text)}")
        }
        val payload = parseObject(text)
            ?: throw IllegalStateException("Cline 令牌登记响应不是合法 JSON")
        // 成功响应可能带 `{"data":{...},"success":true}` 信封，也可能直接是扁平对象
        val data = payload.objOrNull("data") ?: payload
        val access = data.stringOrNull("accessToken").orEmpty().trim()
        if (access.isEmpty()) throw IllegalStateException("Cline 令牌登记响应缺少 accessToken")
        val refresh = data.stringOrNull("refreshToken").orEmpty().trim().ifEmpty { workosRefresh }
        // 账号 id 的兜底来源：会话令牌正常也是 JWT（实测带 `external_id`），
        // 但上游换发形态可能变（扁平字符串、不带该声明的 JWT…）。
        // WorkOS 的令牌**一定是**带 `external_id` 的 JWT，所以拿它兜底最稳 ——
        // 少了这一步，uid 会退回 refresh_token 哈希，账号能登录却查不了余额
        // （余额路径要的是 `usr-…`）。
        return accountOf(access, refresh, fallbackIdToken = workosAccess)
    }

    /**
     * 会话令牌 → [ProviderAccount]。
     *
     * uid 优先用 JWT 里的 `account_id`（`usr-...`，稳定）；取不到时退回
     * **refresh_token 的哈希** —— access_token 每次登录都换，拿它做哈希会让
     * 同一账号反复变成新条目（看起来像「只能登一个号」）。
     *
     * [fallbackIdToken]：主令牌里读不到账号 id 时的第二来源（WorkOS 令牌）。
     * 只用于**读 id**，不会替换真正写入凭证的 access_token。
     */
    private fun accountOf(
        accessToken: String,
        refreshToken: String,
        fallbackIdToken: String = "",
    ): ProviderAccount {
        val normalized = ensureTokenPrefix(accessToken)
        // 账号 id 优先级与上游一致：external_id（`usr-…`）> clineUserId > sub。
        // `external_id` 是余额路径要的那个值，取错了账号能登录但查不了余额。
        val idKeys =
            arrayOf("external_id", "externalId", "clineUserId", "account_id", "accountId", "sub")
        val accountId = jwtClaim(normalized, *idKeys)
            .ifEmpty { jwtClaim(ensureTokenPrefix(fallbackIdToken), *idKeys) }
        val email = jwtClaim(normalized, "email")
            .ifEmpty { jwtClaim(ensureTokenPrefix(fallbackIdToken), "email") }
        // 展示名优先邮箱：用户认得出的是自己注册用的邮箱，姓名只是兜底
        val name = email.ifEmpty {
            val source = listOf(normalized, ensureTokenPrefix(fallbackIdToken))
            source.firstNotNullOfOrNull { token ->
                val first = jwtClaim(token, "firstName")
                val last = jwtClaim(token, "lastName")
                (last + first).ifEmpty { jwtClaim(token, "name") }.ifEmpty { null }
            }.orEmpty()
        }
        val expiresAt = jwtClaimLong(normalized, "exp")?.let { it * 1000 }
            ?: jwtClaimLong(ensureTokenPrefix(fallbackIdToken), "exp")?.let { it * 1000 }
            ?: 0L
        val credential = ClineCredential(
            accessToken = normalized,
            refreshToken = refreshToken,
            expiresAt = expiresAt,
            accountId = accountId,
            email = email,
            name = name,
        )
        val uid = accountId.ifEmpty {
            val material = refreshToken.ifEmpty { normalized }
            "cline-" + material.hashCode().toUInt().toString(16)
        }
        val nickname = name.ifEmpty { accountId.ifEmpty { "Cline 账号" } }
        return ProviderAccount(ID, uid, nickname, credential.toSecret())
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * 发一次 form 编码的 POST（WorkOS 两个端点都只认这个编码）。
     *
     * 不复用公共 JSON 工具是刻意的：那会把 `Content-Type` 设成 `application/json`，
     * 上游回 400 `invalid_request`，与「码不对」难以区分。
     */
    private fun formPost(url: String, body: String): Pair<Int, String> {
        val conn = HttpSupport.open(
            url = url,
            method = "POST",
            headers = mapOf(
                "Content-Type" to "application/x-www-form-urlencoded",
                "Accept" to "application/json",
            ),
            body = body,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun postJson(url: String, body: String, headers: Map<String, String>): Pair<Int, String> {
        val conn = HttpSupport.open(
            url = url,
            method = "POST",
            headers = headers,
            body = body,
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS,
        )
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseObject(text: String): JsonObject? =
        runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()

    /** RFC 8628 的 `error` 字段（小写归一，便于比较）。 */
    private fun errorCodeOf(text: String): String =
        parseObject(text)?.stringOrNull("error").orEmpty().trim().lowercase()

    /**
     * 给用户看的原因：优先 `error_description`，其次 `error`，最后截一段原文。
     *
     * 截断是必须的：WorkOS 的失败响应可能带整段 HTML/JSON，直接塞进提示会劝退用户。
     */
    private fun errorDetailOf(text: String, fallbackCode: String = ""): String {
        val payload = parseObject(text)
        val description = payload?.stringOrNull("error_description").orEmpty().trim()
        if (description.isNotEmpty()) return description
        val code = payload?.stringOrNull("error").orEmpty().trim()
        if (code.isNotEmpty()) return code
        if (fallbackCode.isNotEmpty()) return fallbackCode
        return bodySnippet(text, 160).ifEmpty { "上游没有给出原因" }
    }

    companion object {
        /** 与 `ClineProvider.ID` 保持一致（登录成功产出的账号归属这家）。 */
        const val ID = "cline"

        const val HOST = "api.cline.bot"

        /** 上游 API 基址。路径里**只有一个 `v1`**（上游把 baseURL 当根拼）。 */
        const val API_BASE_URL = "https://api.cline.bot/api/v1"

        const val WORKOS_BASE_URL = "https://api.workos.com"

        /**
         * Cline 的 WorkOS client_id。
         *
         * 来源：从已登录 accessToken（JWT）的 `client_id` / `iss` 声明里读出，
         * 不是猜的。它决定设备授权页归属于哪个应用。
         */
        const val WORKOS_CLIENT_ID = "client_01K3A541FN8TA3EPPHTD2325AR"

        /** 上游没给 `verification_uri` 时的兜底授权页。 */
        const val DEVICE_VERIFY_FALLBACK = "https://authkit.cline.bot/device"

        /** 产品面标识头。缺了它免费池模型一律 403。 */
        const val CLIENT_TYPE = "cline-sdk"

        /** RFC 8628 的设备码授权类型。 */
        private const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"

        /** 上游 `expires_in` 缺失时的兜底（实测 300 秒）。 */
        private const val DEFAULT_EXPIRES_SECONDS = 300L

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
