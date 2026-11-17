package io.synapse.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.store.KeyValueStore
import io.synapse.core.util.arrayOrNull

/**
 * 「模型屏蔽名单」：本机不想再用的模型。
 *
 * 屏蔽是**被动排除**——「这些模型已知坏了，别再碰」。被屏蔽的模型：
 *
 * 1. 不出现在 `/v1/models`；
 * 2. 不参与标签路由（`routeByLabel()` 末尾判 null）；
 * 3. 不进「同名模型自动匹配」的目录快照，故障转移也不会把它捡回来。
 *
 * 模型页 UI 仍展示被屏蔽的模型（降透明度 + 「已屏蔽」标签），否则用户没有入口取消屏蔽。
 *
 * ## 存在哪里
 *
 * 独立存储、独立保存入口，**不挂到 [GatewaySettings]** —— 否则每屏蔽一个模型都会走
 * `updateSettings` → `reconfigureProviders` 全量重载 provider。
 */
data class ModelBlocklist(
    /** 被屏蔽模型的 `fullId`（形如 `腾讯国内/glm-5.2`）。 */
    val blocked: Set<String> = emptySet(),
) {
    fun blocks(fullId: String): Boolean = blocked.contains(fullId)

    fun with(fullId: String): ModelBlocklist =
        if (blocks(fullId)) this else ModelBlocklist(blocked + fullId)

    fun without(fullId: String): ModelBlocklist =
        if (!blocks(fullId)) this else ModelBlocklist(blocked - fullId)

    fun withAll(fullIds: Collection<String>): ModelBlocklist =
        if (fullIds.isEmpty()) this else ModelBlocklist(blocked + fullIds)

    fun toJson(): JsonObject = JsonObject().apply {
        add("blocked", JsonArray().apply { blocked.sorted().forEach { add(it) } })
    }

    companion object {
        const val STORE_KEY = "settings/model_blocklist.json"

        fun fromJson(raw: String?): ModelBlocklist {
            if (raw.isNullOrBlank()) return ModelBlocklist()
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return ModelBlocklist()
            val out = LinkedHashSet<String>()
            obj.arrayOrNull("blocked")?.forEach { element ->
                // 只收非空字符串：脏数据里的数字/对象/空串一律忽略
                if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                    val id = element.asString
                    if (id.isNotEmpty()) out.add(id)
                }
            }
            return ModelBlocklist(out)
        }

        fun load(store: KeyValueStore): ModelBlocklist =
            fromJson(store.read(STORE_KEY).orEmpty())
    }
}
