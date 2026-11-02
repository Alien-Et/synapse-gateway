package io.synapse.core.gateway

import com.google.gson.JsonObject
import io.synapse.core.InMemoryKeyValueStore
import io.synapse.core.provider.AuthKind
import io.synapse.core.provider.ChatCall
import io.synapse.core.provider.CreditInfo
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.Provider
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderActionResult
import io.synapse.core.provider.ProviderModelCatalogView
import io.synapse.core.provider.UpstreamError
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 批量动作「过程可见」的回归。
 *
 * 用户点签到后要能看到进度，否则十几秒内界面毫无变化，会被当成「点了没反应」然后重复点，
 * 反而让上游请求翻倍。所以进度事件必须满足：
 *
 * 1. **开始**与**完成**两种事件都要发（只发完成的话，并发下最初几秒只能显示「准备中」）；
 * 2. 动作结束后刷额度那一轮也要发（否则进度停在 N/N 不动，看起来像卡死）；
 * 3. 两种事件用 [BatchProgress.stage] / [BatchProgress.started] 区分得开；
 * 4. 开始事件里的 [BatchProgress.done] 必须是「已完成个数」，不能是下标（并发乱序会让进度条倒退）。
 */
class BatchProgressStageTest {

    private class ProgressProvider(private val failUids: Set<String> = emptySet()) : Provider {
        override val id: String = "batch"
        override val displayName: String = "进度测试"
        override val authKind: AuthKind = AuthKind.NONE

        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            throw UnsupportedOperationException()

        override fun performAction(
            account: ProviderAccount,
            action: String,
            payload: JsonObject,
        ): ProviderActionResult =
            if (account.uid in failUids) {
                ProviderActionResult.failure("签到被拒")
            } else {
                ProviderActionResult.success("签到成功")
            }

        override fun creditInfo(account: ProviderAccount): CreditInfo = CreditInfo(100, true, "测试额度")
    }

    private fun engineWith(provider: Provider): GatewayEngine {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(provider)
        return engine
    }

    private fun targets(vararg uids: String): List<BatchTarget> = uids.map { BatchTarget("batch", it) }

    private fun seed(engine: GatewayEngine, vararg uids: String) {
        uids.forEach { engine.pool.upsert(ProviderAccount("batch", it, "账号$it", "{}")) }
    }

    @Test
    fun `每个账号都先报开始、再报完成`() {
        val engine = engineWith(ProgressProvider())
        seed(engine, "u1", "u2", "u3")
        val events = CopyOnWriteArrayList<BatchProgress>()

        engine.performActionAll(targets("u1", "u2", "u3"), "checkin", parallelism = 1) { events.add(it) }

        val starts = events.filter { it.started && it.stage == BatchStage.ACTION }
        val dones = events.filter { !it.started && it.stage == BatchStage.ACTION }
        assertEquals(3, starts.size, "三个账号都要有开始事件：$events")
        assertEquals(3, dones.size, "三个账号都要有完成事件：$events")
        assertEquals(setOf("u1", "u2", "u3"), starts.map { it.uid }.toSet())
    }

    @Test
    fun `动作之后刷额度那一轮也上报进度`() {
        val engine = engineWith(ProgressProvider())
        seed(engine, "u1", "u2")
        val events = CopyOnWriteArrayList<BatchProgress>()

        engine.performActionAll(targets("u1", "u2"), "checkin", parallelism = 1) { events.add(it) }

        val refresh = events.filter { it.stage == BatchStage.REFRESH }
        assertTrue(refresh.isNotEmpty(), "刷新额度阶段必须上报，否则进度会停在 N/N：$events")
        assertEquals(2, refresh.count { !it.started }, "两个成功账号各报一次刷新完成")
        // 刷新阶段的进度从 0 开始重新数，最大值等于成功账号数
        assertEquals(2, refresh.maxOf { it.done })
    }

    @Test
    fun `失败账号不进刷新阶段`() {
        val engine = engineWith(ProgressProvider(failUids = setOf("u2")))
        seed(engine, "u1", "u2")
        val events = CopyOnWriteArrayList<BatchProgress>()

        engine.performActionAll(targets("u1", "u2"), "checkin", parallelism = 1) { events.add(it) }

        val refreshNames = events.filter { it.stage == BatchStage.REFRESH }.map { it.uid }.toSet()
        assertEquals(setOf("u1"), refreshNames, "签到失败的账号余额没变，不该再打一次额度接口")
    }

    @Test
    fun `开始事件的已完成数不会因并发乱序而倒退`() {
        val engine = engineWith(ProgressProvider())
        seed(engine, "u1", "u2", "u3", "u4")
        val events = CopyOnWriteArrayList<BatchProgress>()

        engine.performActionAll(targets("u1", "u2", "u3", "u4"), "checkin", parallelism = 2) { events.add(it) }

        // 开始事件里的 done 是「已完成个数」，范围必须落在 [0, total]
        val startDones = events.filter { it.started }.map { it.done }
        assertTrue(startDones.all { it in 0..4 }, "开始事件的 done 越界：$startDones")
        // 完成事件的 done 必须覆盖 1..4（不要求顺序，但集合必须齐）
        val doneValues = events.filter { !it.started && it.stage == BatchStage.ACTION }.map { it.done }
        assertEquals(setOf(1, 2, 3, 4), doneValues.toSet(), "四个账号的完成数应覆盖 1..4：$doneValues")
    }
}
