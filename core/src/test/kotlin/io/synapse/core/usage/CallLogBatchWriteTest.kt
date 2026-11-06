package io.synapse.core.usage

import io.synapse.core.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * C5 回归：一笔调用记录的「记录本身 + 三处聚合」必须合成一次批量写，
 * 且聚合数据仍然完整可读（不能被合并写丢掉）。
 */
class CallLogBatchWriteTest {

    private fun record(id: String, providerId: String, tokens: Long) = CallRecord(
        id = id,
        startedAtMillis = System.currentTimeMillis(),
        providerId = providerId,
        model = "标签/model",
        accountUid = "uid",
        accountNickname = "账号",
        streaming = true,
        status = CallStatus.SUCCESS,
        httpStatus = 200,
        promptTokens = tokens,
        completionTokens = 0,
        totalTokens = tokens,
        durationMillis = 100,
        error = "",
        requestBody = "{}",
        responseBody = "hi",
    )

    @Test
    fun `一笔记录只触发一次批量写且四个键都落盘`() {
        val store = CountingStore()
        val calls = CallLogStore(store, capacity = 100, preloadOnConstruction = true)

        calls.add(record("r1", "codebuddy", 123))

        assertEquals(1, store.batchCount, "应该只调一次 writeBatch")
        assertEquals(0, store.singleWriteCount, "不该再有零散的单键写")
        val keys = store.lastBatch!!.keys
        assertTrue(keys.any { it.contains("r1") }, "记录本身要在批量里")
        assertTrue(keys.any { it == "usage/total" }, "累计聚合要在批量里")
        assertTrue(keys.size >= 4, "记录 + 总计 + 当日 + 按供应商当日，至少 4 个键")
    }

    @Test
    fun `聚合数据在批量写后仍能正确读回`() {
        val store = InMemoryKeyValueStore()
        val calls = CallLogStore(store, capacity = 100, preloadOnConstruction = true)
        calls.add(record("r1", "codebuddy", 100))
        calls.add(record("r2", "codebuddy", 200))

        assertEquals(300L, calls.totalStats().totalTokens)
        assertEquals(300L, calls.todayStats(System.currentTimeMillis()).totalTokens)
        assertNotNull(store.read("usage/total"))
    }

    @Test
    fun `writeBatch 的默认实现逐键写也保持语义`() {
        // 覆盖「没有重写 writeBatch 的实现」（FileKeyValueStore / 测试 store）
        val store = InMemoryKeyValueStore()
        store.writeBatch(mapOf("a" to "1", "b" to "2"))
        assertEquals("1", store.read("a"))
        assertEquals("2", store.read("b"))
    }

    /** 计数用 store：验证调用次数而不只是结果。 */
    private class CountingStore : io.synapse.core.store.KeyValueStore {
        private val data = LinkedHashMap<String, String>()
        var batchCount = 0
        var singleWriteCount = 0
        var lastBatch: Map<String, String>? = null

        override fun read(key: String): String? = data[key]
        override fun write(key: String, value: String) {
            singleWriteCount++
            data[key] = value
        }
        override fun writeBatch(entries: Map<String, String>) {
            batchCount++
            lastBatch = entries
            data.putAll(entries)
        }
        override fun delete(key: String) { data.remove(key) }
        override fun keys(prefix: String): List<String> = data.keys.filter { it.startsWith(prefix) }
    }
}
