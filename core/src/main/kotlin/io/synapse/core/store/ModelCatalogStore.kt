package io.synapse.core.store

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.provider.ProviderModel
import io.synapse.core.provider.RoutedModel

/**
 * 一条已落盘的测速结果。
 *
 * 与界面的 `ModelTestStatus` 一一对应，但**不存「测试中」**这种瞬时态：
 * 进程退出后「正在测」毫无意义，重启后一律当作「未测」。
 */
data class PersistedTestResult(
    val success: Boolean,
    val latencyMs: Long = 0,
    val reason: String = "",
    val testedAt: Long = 0,
)

/**
 * 模型目录与测速结果的持久化层（借鉴 Magpie 的 last-known-good 思路）。
 *
 * 两条纪律：
 *
 * 1. **目录只存「上一次成功拉取到的副本」。** 刷新失败绝不覆盖旧数据，
 *    否则一次网络抖动/上游维护就会让用户的模型列表突然清空，
 *    表现为「配好的模型全不见了」。拉取成功后才整体覆盖写。
 * 2. **测速结果跨重启保留。** 「测速成功/失败」是真实打过的上游结果，
 *    重启后不该重新测一遍才知道——尤其是用户点「一键测速」后隔天再打开，
 *    结果仍在，分组统计（可用/待测/屏蔽）也才自洽。
 *
 * 用 Gson 手工构造 JSON，不反序列化复杂对象：加密存储里的每个值都是自造的，
 * 字段缺失时逐项回落默认值，坏数据不炸启动。
 */
class ModelCatalogStore(private val store: KeyValueStore) {

    /**
     * 读某个供应商上次成功拉到的目录副本。

     * 返回的条目统一标记 `cached = true`：界面据此显示「缓存」徽标，
     * 明确这是本机旧副本，而不是本轮真实拉取结果。
     */
    fun loadCatalog(providerId: String): List<RoutedModel>? {
        val raw = store.read(catalogKey(providerId)) ?: return null
        return runCatching { parseCatalog(raw, providerId) }.getOrNull()
    }

    /** 读全部供应商的缓存目录，键为 providerId。 */
    fun loadAll(): Map<String, List<RoutedModel>> {
        val result = LinkedHashMap<String, List<RoutedModel>>()
        for (key in store.keys(CATALOG_KEY_PREFIX)) {
            val providerId = key.removePrefix(CATALOG_KEY_PREFIX)
            if (providerId.isEmpty()) continue
            val raw = store.read(key) ?: continue
            runCatching { parseCatalog(raw, providerId) }
                .getOrNull()?.let { result[providerId] = it }
        }
        return result
    }

    /** 拉取成功后整体覆盖写入；成功返回空目录也覆盖旧缓存。 */
    fun saveCatalog(providerId: String, models: List<RoutedModel>) {
        val obj = JsonObject()
        obj.addProperty("providerId", providerId)
        obj.addProperty("fetchedAt", System.currentTimeMillis())
        val arr = JsonArray()
        for (routed in models) {
            val entry = routed.model.toJson()
            entry.addProperty("providerName", routed.providerName)
            entry.addProperty("routePrefix", routed.routePrefix)
            entry.addProperty("region", routed.region.orEmpty())
            arr.add(entry)
        }
        obj.add("models", arr)
        store.write(catalogKey(providerId), obj.toString())
    }

    /** 最近一次目录刷新尝试；失败时据此做短退避，避免每次回前台都打上游。 */
    fun lastAttemptAt(): Long = store.read(KEY_LAST_ATTEMPT_AT)?.toLongOrNull() ?: 0L

    fun setLastAttemptAt(at: Long) {
        store.write(KEY_LAST_ATTEMPT_AT, at.toString())
    }

    /** 最近一次成功聚合目录的时刻（epoch ms）；0 表示本机还没有任何成功拉取记录。 */
    fun lastFetchedAt(): Long = store.read(KEY_LAST_FETCHED_AT)?.toLongOrNull() ?: 0L

    /** 一次成功的聚合刷新完成后记时刻，供过期判断与冷启动判断。 */
    fun setLastFetchedAt(at: Long) {
        store.write(KEY_LAST_FETCHED_AT, at.toString())
    }

    /** 读全部已落盘的测速结果，键为模型 fullId。 */
    fun loadTestResults(): Map<String, PersistedTestResult> {
        val raw = store.read(KEY_TEST_RESULTS) ?: return emptyMap()
        return runCatching {
            JsonParser.parseString(raw).asJsonObject.entrySet().mapNotNull { (id, el) ->
                val obj = el.asJsonObject
                PersistedTestResult(
                    success = obj.opt("ok")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
                    latencyMs = obj.opt("ms")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                    reason = obj.opt("reason")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty(),
                    testedAt = obj.opt("at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                ).takeIf { id.isNotBlank() }
                    ?.let { id to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** 全量覆盖写测速结果（调用方传入最新的内存态，过滤掉「测试中」）。 */
    fun saveTestResults(results: Map<String, PersistedTestResult>) {
        if (results.isEmpty()) {
            store.delete(KEY_TEST_RESULTS)
            return
        }
        val obj = JsonObject()
        for ((id, result) in results) {
            val entry = JsonObject()
            entry.addProperty("ok", result.success)
            entry.addProperty("ms", result.latencyMs)
            entry.addProperty("reason", result.reason)
            entry.addProperty("at", result.testedAt)
            obj.add(id, entry)
        }
        store.write(KEY_TEST_RESULTS, obj.toString())
    }

    private fun parseCatalog(raw: String, providerId: String): List<RoutedModel> {
        val root = JsonParser.parseString(raw).asJsonObject
        val arr = root.get("models")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        // 新格式逐项保留区域/路由前缀；旧格式回落到根节点元数据。
        val rootName = root.opt("providerName")?.asString ?: providerId
        val rootPrefix = root.opt("routePrefix")?.asString ?: providerId
        val rootRegion = root.opt("region")?.asString.orEmpty().ifEmpty { null }
        val models = ArrayList<RoutedModel>(arr.size())
        for (el in arr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            val id = obj.opt("id")?.asString.orEmpty()
            if (id.isBlank()) continue
            val extra = LinkedHashMap<String, String>()
            obj.get("extra")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { entry ->
                if (entry.value.isJsonPrimitive) extra[entry.key] = entry.value.asString
            }
            models.add(
                RoutedModel(
                    providerId = providerId,
                    providerName = obj.opt("providerName")?.asString ?: rootName,
                    model = ProviderModel(
                        id = id,
                        name = obj.opt("name")?.asString.orEmpty(),
                        contextWindow = obj.opt("contextWindow")?.asLong ?: 0L,
                        extra = extra,
                    ),
                    routePrefix = obj.opt("routePrefix")?.asString ?: rootPrefix,
                    region = obj.opt("region")?.asString.orEmpty().ifEmpty { rootRegion },
                    // 这不是本轮真实拉取结果，永远按「缓存副本」对待
                    cached = true,
                ),
            )
        }
        return models
    }

    private fun ProviderModel.toJson(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", id)
        obj.addProperty("name", name)
        obj.addProperty("contextWindow", contextWindow)
        val extra = JsonObject()
        this.extra.forEach { (k, v) -> extra.addProperty(k, v) }
        obj.add("extra", extra)
        return obj
    }

    /** 取 JSON 里的可选字符串（对 null / 非原始值安全）。 */
    private fun JsonObject.opt(name: String): JsonElement? = get(name)?.takeIf { !it.isJsonNull }

    companion object {
        /** 每个供应商一份缓存目录，键形如 `catalog/models.<providerId>`。 */
        const val CATALOG_KEY_PREFIX = "catalog/models."

        private const val KEY_LAST_FETCHED_AT = "settings/models.fetchedAt"
        private const val KEY_LAST_ATTEMPT_AT = "settings/models.lastAttemptAt"
        private const val KEY_TEST_RESULTS = "settings/models.testResults"

        private fun catalogKey(providerId: String): String = "$CATALOG_KEY_PREFIX$providerId"
    }
}
