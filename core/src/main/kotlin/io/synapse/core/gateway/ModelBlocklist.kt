package io.synapse.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.store.KeyValueStore
import io.synapse.core.util.arrayOrNull

/**
 * 「模型黑名单」：本机不想再用的模型。
 *
 * 与 [ModelExposureSettings]（白名单）方向相反，两者可以同时存在 —— **屏蔽优先**：
 * 一个模型即使还在开放名单里，只要被屏蔽也不会对外暴露、也不会被路由。
 *
 * ## 为什么要和开放名单分开
 *
 * 开放名单回答的是「哪些模型对客户端可见」，是**主动挑选**；
 * 黑名单回答的是「哪些模型已知坏了，别再碰」，是**被动排除**。
 * 合成一个列表的话，用户每次想临时禁掉一个坏模型，都得先确认它在不在白名单里，
 * 否则「取消勾选」和「屏蔽」会变成同一个动作，语义彻底糊掉。
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
