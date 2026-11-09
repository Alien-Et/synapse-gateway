package io.synapse.core.gateway

import io.synapse.core.provider.RoutedModel

/**
 * 一轮模型目录刷新带来的变更（新增 / 移除）。
 *
 * ## 为什么需要它
 *
 * 刷新模型时上游会增删模型：新上架、下架、限免活动结束、账号换了套餐……
 * 界面从前只会「列表变了」，用户不知道变了什么，也就无从判断「我配的模型怎么不见了」。
 *
 * ## 只比较「本轮真的拉到目录」的供应商
 *
 * 这是本类型最重要的一条语义：拉取失败（无账号、上游故障、超时）**不参与比较**。
 * 否则一次网络抖动就会把整家供应商的模型判成「已移除」，用户看到的是一次假下架 ——
 * 与「未知 ≠ 零」是同一条纪律。
 *
 * 因此 [of] 要求调用方把「成功拉到的供应商」显式传进来，而不是让它去猜。
 */
data class ModelCatalogChanges(
    /** 新增的模型（`fullId`），按供应商分组。 */
    val added: Map<String, List<String>> = emptyMap(),
    /** 移除的模型（`fullId`），按供应商分组。 */
    val removed: Map<String, List<String>> = emptyMap(),
) {
    /** 是否完全没有变更。 */
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()

    /** 变更总数（新增 + 移除）。 */
    val total: Int get() = added.values.sumOf { it.size } + removed.values.sumOf { it.size }

    /**
     * 一句话摘要，直接给界面/日志用；无变更时返回空串。
     *
     * 形如 `模型目录有变化：Cline 新增 2、移除 1；谷歌 移除 3`。
     * 每家的条目过多时只报数量（几十条铺在界面上没有意义）。
     */
    fun summary(): String {
        if (isEmpty) return ""
        val parts = (added.keys + removed.keys).sorted().mapNotNull { providerId ->
            val plus = added[providerId].orEmpty().size
            val minus = removed[providerId].orEmpty().size
            if (plus == 0 && minus == 0) return@mapNotNull null
            buildString {
                append(providerId)
                if (plus > 0) append(" 新增 ").append(plus)
                if (plus > 0 && minus > 0) append("、")
                if (minus > 0) append("移除 ").append(minus)
            }
        }
        return if (parts.isEmpty()) "" else "模型目录有变化：" + parts.joinToString("；")
    }

    companion object {
        /**
         * 比较两轮目录。
         *
         * @param previous 上一轮的模型（可为空：首次加载时没有「上一轮」，此时只报新增没有意义，
         *   所以 [firstLoad] 为 true 时直接返回空变更）。
         * @param current 本轮聚合后的模型。
         * @param refreshedProviders 本轮**真的拉到了目录**的供应商 id 集合；
         *   只有这些供应商参与比较，其余保持原样（拉取失败不等于下架）。
         * @param firstLoad 首次加载：没有基准可比，一律视为「无变更」。
         */
        fun of(
            previous: List<RoutedModel>,
            current: List<RoutedModel>,
            refreshedProviders: Set<String>,
            firstLoad: Boolean = false,
        ): ModelCatalogChanges {
            if (firstLoad || refreshedProviders.isEmpty()) return ModelCatalogChanges()
            val prevByProvider = previous.groupBy { it.providerId }
            val currByProvider = current.groupBy { it.providerId }
            val added = LinkedHashMap<String, List<String>>()
            val removed = LinkedHashMap<String, List<String>>()
            for (providerId in refreshedProviders.sorted()) {
                val before = prevByProvider[providerId].orEmpty().map { it.fullId }.toSet()
                val after = currByProvider[providerId].orEmpty().map { it.fullId }.toSet()
                val plus = (after - before).sorted()
                val minus = (before - after).sorted()
                if (plus.isNotEmpty()) added[providerId] = plus
                if (minus.isNotEmpty()) removed[providerId] = minus
            }
            return ModelCatalogChanges(added, removed)
        }
    }
}
