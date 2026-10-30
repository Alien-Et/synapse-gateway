package io.synapse.core.provider.antigravity

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Antigravity 客户端版本号。
 *
 * 上游按 `User-Agent` 里的客户端版本决定可用模型：**低于 2.23.0 的客户端拿不到新模型**
 * （CLIProxyAPI 的注释明写这一点：`Cloud Code rejects newer models for clients below 2.23.0`；
 * MIT，router-for-me，https://github.com/router-for-me/CLIProxyAPI），
 * 版本写死会让账号随着上游推进逐渐失效。这里改成从官方自动更新清单取，并缓存 [TTL_MILLIS]。
 *
 * 取不到时用 [FALLBACK_VERSION]（等于 CLIProxyAPI 的兜底值），绝不因此让请求失败。
 * 只做一次轻量 GET 且结果缓存，不会给每个请求增加往返。
 *
 * ⚠️ **失败与成功的缓存时长必须分开**：清单拉取失败（离线、被墙、上游 5xx）时若也按
 * [TTL_MILLIS] 缓存兜底值，一次网络抖动就会让接下来 6 小时都用 2.23.0 这个最低可用版本的
 * UA——等于主动放弃新模型。所以失败只做 [FAILURE_BACKOFF_MILLIS] 的短退避，随后允许重试。
 */
internal object AntigravityVersion {

    /** 官方自动更新清单（macOS arm64），内容是 YAML，只解析 `version:` 一行。 */
    private const val MANIFEST_URL =
        "https://antigravity-hub-auto-updater-974169037036.us-central1.run.app/manifest/latest-arm64-mac.yml"

    /** 兜底版本：必须 ≥ 上游的下限 2.23.0，否则新模型会被直接拒绝。 */
    const val FALLBACK_VERSION = "2.23.0"

    /** 成功拿到版本后的缓存时长。 */
    private const val TTL_MILLIS = 6L * 60 * 60 * 1000

    /**
     * 拉取失败后的重试退避：短到网络恢复后能尽快拿到真实版本，
     * 长到不会让每个请求都去打一次清单（清单是外部服务的往返）。
     */
    private const val FAILURE_BACKOFF_MILLIS = 5L * 60 * 1000

    private const val TIMEOUT_MS = 10_000

    /** 缓存条目：[version] 当前生效版本；[checkedAt] 上次回源时刻（null = 从未回源）；[fetched] 上次回源是否成功。 */
    private data class Cache(val version: String, val checkedAt: Long?, val fetched: Boolean)

    /**
     * 从未回源过的初态：`checkedAt = null` 保证第一次调用必然回源。
     *
     * 不能用哨兵时间戳代替（如 Long.MIN_VALUE）：`nowMillis - Long.MIN_VALUE` 会整数溢出成负数，
     * 反而被判成「刚查过」。也不能用 0：`current(0L)` 这类起点为 0 的调用会算出 0 间隔。
     */
    private fun initialCache() = Cache(FALLBACK_VERSION, null, fetched = false)

    private val cachedHub = AtomicReference(initialCache())

    /**
     * 真正的回源实现。默认 [fetchLatest]（打官方清单）；
     * 单测替换成可控的假实现，才能验证「成功/失败分别缓存多久」。
     */
    private var fetcher: (String) -> String? = ::fetchLatest

    /** 当前生效的版本号（缓存过期才回源）。 */
    fun current(nowMillis: Long = System.currentTimeMillis()): String {
        val cache = cachedHub.get()
        val window = if (cache.fetched) TTL_MILLIS else FAILURE_BACKOFF_MILLIS
        val checkedAt = cache.checkedAt
        if (checkedAt != null && nowMillis - checkedAt < window) return cache.version
        val fetched = fetcher(MANIFEST_URL)
        // 失败时不要退回兜底值：可能早就成功拿到过真实版本，那比兜底值更可信
        val next = if (fetched != null) Cache(fetched, nowMillis, fetched = true)
        else Cache(cache.version, nowMillis, fetched = false)
        cachedHub.set(next)
        return next.version
    }

    /** 解析清单里的 `version:`；失败返回 null（调用方用兜底值）。 */
    internal fun parseManifest(text: String): String? =
        text.lineSequence()
            .firstOrNull { it.trimStart().startsWith("version:") }
            ?.substringAfter(':')
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() }

    /** 供单测重置缓存的钩子（真实代码不需要）。 */
    internal fun resetForTest(fetcher: ((String) -> String?)? = null) {
        cachedHub.set(initialCache())
        this.fetcher = fetcher ?: ::fetchLatest
    }

    private fun fetchLatest(url: String = MANIFEST_URL): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false
        }
        try {
            if (conn.responseCode !in 200..299) {
                null
            } else {
                parseManifest(conn.inputStream.bufferedReader().readText())
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}
