package dev.aigw.core.usage

import dev.aigw.core.InMemoryKeyValueStore
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 调用记录的完整原文溢出机制：
 * 加密存储只留每字段前 8K 摘要，超出部分写到溢出目录，详情页按 id 回读全文；
 * 删除/清空/过期清理/手动截断都必须连带清理溢出文件，避免缓存目录只进不出。
 */
class CallLogSpillTest {

    private fun record(
        id: String,
        request: String,
        response: String = "resp",
        raw: String = "",
        at: Long = 1_700_000_000_000,
    ) = CallRecord(
        id = id,
        startedAtMillis = at,
        providerId = "trae",
        model = "glm-5.3",
        accountUid = "u1",
        accountNickname = "昵称",
        streaming = false,
        status = CallStatus.SUCCESS,
        httpStatus = 200,
        promptTokens = 1,
        completionTokens = 2,
        totalTokens = 3,
        pointsConsumed = 0,
        durationMillis = 10,
        error = "",
        requestBody = request,
        responseBody = response,
        rawResponse = raw,
    )

    @Test
    fun `long bodies spill to files and short ones do not`() {
        val store = InMemoryKeyValueStore()
        val spill = kotlin.io.path.createTempDirectory("spill").toFile()
        val log = CallLogStore(store, spillDir = spill)

        val long = "x".repeat(CallLogStore.MAX_SUMMARY_CHARS + 100)
        log.add(record("id-long", request = long, response = "短响应"))
        log.add(record("id-short", request = "短请求"))

        // 超长字段有全文，短字段没有
        assertEquals(long, log.fullText("id-long", CallTextField.REQUEST))
        assertNull(log.fullText("id-long", CallTextField.RESPONSE))
        assertNull(log.fullText("id-short", CallTextField.REQUEST))

        // 存储内是摘要（8K），列表展示不受影响
        val listed = log.list().first { it.id == "id-long" }
        assertEquals(CallLogStore.MAX_SUMMARY_CHARS, listed.requestBody.length)
        spill.deleteRecursively()
    }

    @Test
    fun `spill respects per-file cap`() {
        val store = InMemoryKeyValueStore()
        val spill = kotlin.io.path.createTempDirectory("spill").toFile()
        val log = CallLogStore(store, spillDir = spill)

        val huge = "y".repeat(CallLogStore.MAX_SPILL_CHARS + 5_000)
        log.add(record("id-huge", request = huge))

        val full = log.fullText("id-huge", CallTextField.REQUEST)
        assertEquals(CallLogStore.MAX_SPILL_CHARS, full?.length)
        spill.deleteRecursively()
    }

    @Test
    fun `delete and clear remove spill files`() {
        val store = InMemoryKeyValueStore()
        val spill = kotlin.io.path.createTempDirectory("spill").toFile()
        val log = CallLogStore(store, spillDir = spill)

        val long = "z".repeat(CallLogStore.MAX_SUMMARY_CHARS + 1)
        log.add(record("id-a", request = long))
        log.add(record("id-b", request = long))

        log.delete("id-a")
        assertNull(log.fullText("id-a", CallTextField.REQUEST))
        assertTrue(spill.listFiles()!!.isNotEmpty())

        log.clear()
        assertTrue(spill.listFiles().isNullOrEmpty())
        spill.deleteRecursively()
    }

    @Test
    fun `purgeOlderThan and truncateFields also clean spill files`() {
        val store = InMemoryKeyValueStore()
        val spill = kotlin.io.path.createTempDirectory("spill").toFile()
        val log = CallLogStore(store, spillDir = spill)

        val long = "q".repeat(CallLogStore.MAX_SUMMARY_CHARS + 1)
        log.add(record("old", request = long))
        log.add(record("truncated", request = long, at = System.currentTimeMillis() + 60_000))

        // 过期清理（起点晚于 old 的记录时间）应带走 old 的溢出文件，且只清 old
        log.purgeOlderThan(System.currentTimeMillis())
        assertNull(log.fullText("old", CallTextField.REQUEST))
        assertTrue(log.fullText("truncated", CallTextField.REQUEST) != null)

        // 手动截断后，被截字段的全文缓存一并回收
        val (changed, _) = log.truncateFields(200)
        assertEquals(1, changed)
        assertNull(log.fullText("truncated", CallTextField.REQUEST))
        assertFalse(File(spill, CallLogStore.spillFileName("truncated", CallTextField.REQUEST)).exists())
        spill.deleteRecursively()
    }
}
