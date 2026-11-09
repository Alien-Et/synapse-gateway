package io.synapse.core.provider.cline

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.util.firstLong
import io.synapse.core.util.firstString
import io.synapse.core.util.longOrNull
import io.synapse.core.util.stringOrNull
import java.time.OffsetDateTime

/**
 * Cline 凭证的**唯一形态**与解析规则。
 *
 * ## 上游长什么样（实测）
 *
 * 桌面端落在 `~/.cline/data/settings/providers.json` 的
 * `providers.cline.settings.auth`：
 *
 * ```jsonc
 * {
 *   "accessToken": "workos:eyJhbGciOiJSUzI1NiIs...",
 *   "refreshToken": "N5EUfPqyERlpRhiF4WUXDPunz",
 *   "expiresAt": 1789842888000,
 *   "accountId": "usr-01M2VWEDWFWWRS3WQ7R425E817",
 *   "metadata": { "userInfo": { "email": "...", "name": "..." } }
 * }
 * ```
 *
 * ## 三处「同名不同型」的坑（都踩过，别按直觉改）
 *
 * 1. **`workos:` 前缀必须原样带上**。发请求时 `Authorization: Bearer workos:eyJ...`，
 *    去掉前缀直接 401。这个前缀是 Cline 用来区分「WorkOS 令牌」与「自家旧版令牌」的，
 *    不是装饰。而 `/auth/refresh` 返回的 accessToken **不带**前缀（登录返回的带），
 *    所以写入前统一过 [ensureTokenPrefix]。
 *
 * 2. **`expiresAt` 有两种形态**。桌面端 `providers.json` 里是**毫秒数**；
 *    `/auth/refresh` 返回的是 **ISO 8601 字符串**（`"2026-09-19T19:30:04Z"`）。
 *    解析必须分开处理，否则续期一次就把过期时间读成 0，之后每次请求都以为要刷新。
 *
 * 3. **`refreshToken` 是一次性轮换语义**。虽然实测这次返回了同一个值，但协议本身
 *    允许轮换；并发续期时后到的那次可能拿着已作废的 token → 401 → 用户被踢下线。
 *    所以续期必须单飞（见 `ClineProvider.refreshAccount` 的锁）。
 *
 * ## 为什么 `workos:` 前缀要在**这一层**补
 *
 * 上游三个地方（登录、续期、桌面端文件）给的 token 前缀形态不一致。如果每个调用点
 * 各自补一次，漏一处就是 401；集中到 [ensureTokenPrefix] 后，「发出去的 token 一定带前缀」
 * 变成结构性保证，而不是每个调用点各自记得。
 */
internal data class ClineCredential(
    /** 已确保带 `workos:` 前缀的访问令牌。 */
    val accessToken: String,
    val refreshToken: String,
    /** 过期时刻（毫秒时间戳）；0 表示上游没给。 */
    val expiresAt: Long,
    /** 上游账号 id（`usr-...`），用作账号池 uid 的首选。 */
    val accountId: String,
    val email: String,
    val name: String,
) {
    /** 该凭证是否已在 [skewSeconds] 秒内过期（含已经过期）。 */
    fun expiresWithin(skewSeconds: Long, nowMillis: Long): Boolean {
        if (expiresAt <= 0) return false
        return expiresAt - nowMillis <= skewSeconds * 1000
    }
}

/** 令牌前缀。Cline 用它区分 WorkOS 令牌与旧版令牌，去掉就 401。 */
internal const val WORKOS_PREFIX = "workos:"

/**
 * 给令牌补上 `workos:` 前缀；已经带了就原样返回。
 *
 * 幂等是刻意的：调用点可能拿到「登录返回的（带前缀）」或「续期返回的（不带）」，
 * 两种情况都要能直接传进来，不该让调用方自己判断来源。
 */
internal fun ensureTokenPrefix(token: String): String {
    val trimmed = token.trim()
    if (trimmed.isEmpty()) return trimmed
    return if (trimmed.startsWith(WORKOS_PREFIX, ignoreCase = true)) trimmed else WORKOS_PREFIX + trimmed
}

/**
 * 解析账号凭证 JSON（[io.synapse.core.provider.ProviderAccount.secret] 的内容）。
 *
 * 同时兼容两种来源：
 * - 本模块 [toSecret] 写出的扁平形态（`accessToken` / `refreshToken` / ...）；
 * - 用户直接粘贴的桌面端 `auth` 片段（字段同名，只是多了 `metadata` 嵌套）。
 *
 * 解析失败返回 null —— 调用方据此报「凭证无法解析」，而不是拿一个半残对象去打上游。
 */
internal fun parseClineCredential(secret: String): ClineCredential? {
    val obj = runCatching { JsonParser.parseString(secret).asJsonObject }.getOrNull() ?: return null
    val access = obj.firstString("accessToken", "access_token")
    if (access.isEmpty()) return null
    val metadata = obj.objOrNull("metadata")
    val userInfo = metadata?.objOrNull("userInfo") ?: obj.objOrNull("userInfo")
    return ClineCredential(
        accessToken = ensureTokenPrefix(access),
        refreshToken = obj.firstString("refreshToken", "refresh_token"),
        expiresAt = parseExpiresAt(obj),
        // 上游 JWT 里的账号 id 叫 `external_id`（`usr-...`），不是 `account_id`。
        // 读错字段的后果不是报错，而是 uid 变成空串 —— 余额路径会拼成
        // `/users//balance`，上游回 `Invalid request format`，看起来像「协议不对」。
        accountId = obj.firstString(
            "accountId", "account_id", "external_id", "externalId",
            "clineUserId", "cline_user_id", "userId", "uid",
        ),
        // email / name 两个来源都要认：桌面端 auth 片段把它们放在 metadata.userInfo 里，
        // 而本模块 [toSecret] 写的是顶层扁平形态。只读嵌套会让「自己存下去的凭证
        // 读不回来」，只读顶层会让用户粘贴的桌面端文件丢显示名。
        email = userInfo?.stringOrNull("email").orEmpty()
            .ifEmpty { obj.stringOrNull("email").orEmpty() },
        name = userInfo?.stringOrNull("name").orEmpty()
            .ifEmpty { obj.stringOrNull("name").orEmpty() }
            .ifEmpty { obj.stringOrNull("displayName").orEmpty() },
    )
}

/**
 * 把凭证写回 `ProviderAccount.secret` 的 JSON。
 *
 * **永远写毫秒数**（与桌面端 `providers.json` 同形态）：续期接口返回的 ISO 字符串
 * 在这一步已经被 [parseIsoExpiresAt] 转成毫秒，落盘形态因此只有一种。
 * 存两种形态会让「读回来要不要判断类型」变成每个调用点的心智负担。
 */
internal fun ClineCredential.toSecret(): String = JsonObject().apply {
    addProperty("accessToken", accessToken)
    addProperty("refreshToken", refreshToken)
    addProperty("expiresAt", expiresAt)
    if (accountId.isNotEmpty()) addProperty("accountId", accountId)
    if (email.isNotEmpty()) addProperty("email", email)
    if (name.isNotEmpty()) addProperty("name", name)
}.toString()

/**
 * 读 `expiresAt`，**两种形态都认**。
 *
 * - 数字 → 毫秒时间戳（桌面端 `providers.json`）；
 * - 字符串 → 先试 ISO 8601（`/auth/refresh` 的返回），再试纯数字字符串。
 *
 * 解析不出来返回 0（「未知」），而不是当前时间 —— 后者会让凭证立刻被判为过期，
 * 每次请求都去续期。
 */
internal fun parseExpiresAt(obj: JsonObject): Long {
    val element = obj.get("expiresAt") ?: obj.get("expires_at") ?: return 0L
    if (element.isJsonPrimitive) {
        val primitive = element.asJsonPrimitive
        if (primitive.isNumber) {
            val value = primitive.asLong
            // 秒级时间戳（10 位）与毫秒级（13 位）都要能认：上游两种都出现过。
            return if (value in 1..9_999_999_999L) value * 1000 else value
        }
        if (primitive.isString) {
            val raw = primitive.asString.trim()
            if (raw.isEmpty()) return 0L
            parseIsoExpiresAt(raw)?.let { return it }
            return raw.toLongOrNull()?.let { if (it in 1..9_999_999_999L) it * 1000 else it } ?: 0L
        }
    }
    return obj.longOrNull("expiresAt") ?: obj.firstLong("expiresAt", "expires_at")
}

/**
 * ISO 8601 → 毫秒。解析不了返回 null。
 *
 * 用 [OffsetDateTime] 而不是手写正则：上游给的是带时区的完整时刻
 * （`2026-09-19T19:30:04Z`），手写解析在 `Z` / `+08:00` / 毫秒小数位上很容易漏。
 */
internal fun parseIsoExpiresAt(raw: String): Long? = runCatching {
    OffsetDateTime.parse(raw.trim()).toInstant().toEpochMilli()
}.getOrNull()

/** `JsonObject.objOrNull` 的本地副本，避免为一行工具再引一次公共包。 */
private fun JsonObject.objOrNull(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject
