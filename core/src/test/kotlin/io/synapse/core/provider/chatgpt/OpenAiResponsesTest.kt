package io.synapse.core.provider.chatgpt

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Chat ⇄ Responses 双向转换与 SSE 翻译的回归。
 *
 * 这层是 ChatGPT 供应商最容易出问题的地方：**每条「不能带什么」都是上游硬约束**，
 * 而且写错了上游只回一句看不懂的 400。所以下面按上游要求逐条钉：
 * - `store` 必须 false、`include` 必须带 reasoning.encrypted_content；
 * - 不能带 max_output_tokens / temperature / top_p；
 * - system 提示用 developer 角色，不能用 instructions；
 * - 工具必须显式 strict: false（否则可选参数被当必填）；
 * - tool 结果回传用 call_id（用 name/index 上游认不出来）。
 */
class OpenAiResponsesTest {

    private fun toRequest(body: String, model: String = "gpt-5.1-codex"): String =
        OpenAiResponses.toResponsesRequest(body, model)

    private fun parse(json: String) = JsonParser.parseString(json).asJsonObject

    // ---------------------------------------------------------------- 请求转换

    @Test
    fun `请求必须带 store false 与 include`() {
        val out = parse(toRequest("""{"messages":[{"role":"user","content":"hi"}]}"""))
        assertFalse(out.get("store").asBoolean, "订阅通道必须 store=false")
        assertEquals(
            "reasoning.encrypted_content",
            out.getAsJsonArray("include")[0].asString,
        )
        assertTrue(out.get("stream").asBoolean, "上游只支持流式，必须带 stream=true")
    }

    @Test
    fun `绝不携带采样参数与输出上限`() {
        // Codex 遇到这些字段直接 400，所以要彻底剥掉
        val body = """{"messages":[{"role":"user","content":"hi"}],
            "temperature":0.7,"top_p":0.9,"max_tokens":100,"max_output_tokens":200}"""
        val out = parse(toRequest(body))
        for (key in listOf("temperature", "top_p", "max_tokens", "max_output_tokens")) {
            assertFalse(out.has(key), "$key 不该被转发给 Codex")
        }
    }

    @Test
    fun `system 提示转成 developer 角色放进 input`() {
        val body = """{"messages":[
            {"role":"system","content":"你是助手"},
            {"role":"user","content":"你好"}]}"""
        val out = parse(toRequest(body))
        // 不能有 instructions 字段
        assertFalse(out.has("instructions"), "system 不能用 instructions 传")
        val input = out.getAsJsonArray("input")
        val first = input[0].asJsonObject
        assertEquals("developer", first.get("role").asString)
        assertEquals("你是助手", first.get("content").asString)
        assertEquals("user", input[1].asJsonObject.get("role").asString)
    }

    @Test
    fun `工具必须显式 strict false`() {
        val body = """{"messages":[{"role":"user","content":"hi"}],"tools":[
            {"type":"function","function":{"name":"f","description":"d","parameters":{"type":"object"}}}]}"""
        val out = parse(toRequest(body))
        val tool = out.getAsJsonArray("tools")[0].asJsonObject
        assertTrue(tool.has("strict"), "必须显式写 strict，省略时上游按 true 处理")
        assertFalse(tool.get("strict").asBoolean)
        assertEquals("f", tool.get("name").asString)
    }

    @Test
    fun `非 function 类型的工具被丢弃`() {
        val body = """{"messages":[{"role":"user","content":"hi"}],"tools":[
            {"type":"web_search"},{"type":"function","function":{"name":"f","parameters":{}}}]}"""
        val out = parse(toRequest(body))
        assertEquals(1, out.getAsJsonArray("tools").size())
    }

    @Test
    fun `工具结果转成 function_call_output 且用 call_id`() {
        val body = """{"messages":[
            {"role":"assistant","content":null,"tool_calls":[
                {"id":"call_1","type":"function","function":{"name":"f","arguments":"{}"}}]},
            {"role":"tool","tool_call_id":"call_1","content":"结果"}]}"""
        val input = parse(toRequest(body)).getAsJsonArray("input")

        // assistant 的 tool_calls → function_call
        val call = input[0].asJsonObject
        assertEquals("function_call", call.get("type").asString)
        assertEquals("call_1", call.get("call_id").asString)
        assertEquals("f", call.get("name").asString)

        // tool 结果 → function_call_output，**回传键必须是 call_id**
        val output = input[1].asJsonObject
        assertEquals("function_call_output", output.get("type").asString)
        assertEquals("call_1", output.get("call_id").asString)
        assertEquals("结果", output.get("output").asString)
    }

    @Test
    fun `工具结果缺 tool_call_id 时退回 name`() {
        val body = """{"messages":[{"role":"function","name":"legacy_fn","content":"ok"}]}"""
        val input = parse(toRequest(body)).getAsJsonArray("input")
        assertEquals("legacy_fn", input[0].asJsonObject.get("call_id").asString)
    }

    @Test
    fun `多模态图片转成 input_image`() {
        val body = """{"messages":[{"role":"user","content":[
            {"type":"text","text":"看这个"},
            {"type":"image_url","image_url":{"url":"data:image/png;base64,AAA"}}]}]}"""
        val content = parse(toRequest(body)).getAsJsonArray("input")[0]
            .asJsonObject.getAsJsonArray("content")
        assertEquals("input_text", content[0].asJsonObject.get("type").asString)
        assertEquals("看这个", content[0].asJsonObject.get("text").asString)
        assertEquals("input_image", content[1].asJsonObject.get("type").asString)
        assertEquals("data:image/png;base64,AAA", content[1].asJsonObject.get("image_url").asString)
    }

    @Test
    fun `reasoning_effort 透传进 reasoning 对象`() {
        val body = """{"messages":[{"role":"user","content":"hi"}],"reasoning_effort":"high"}"""
        val out = parse(toRequest(body))
        assertEquals("high", out.getAsJsonObject("reasoning").get("effort").asString)
        assertEquals("auto", out.getAsJsonObject("reasoning").get("summary").asString)
    }

    @Test
    fun `非法 JSON 抛出可读异常`() {
        val error = runCatching { toRequest("not json") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error.message.orEmpty().contains("合法 JSON"))
    }

    // ---------------------------------------------------------------- SSE 翻译

    private fun translator() = OpenAiResponses.SseTranslator("cmpl-1", "gpt-5.1-codex", createdSeconds = 1)

    @Test
    fun `文本增量翻译成 chat chunk`() {
        val t = translator()
        assertTrue(t.translate("""event: response.output_text.delta""").isEmpty(), "event 行本身不出帧")
        val frames = t.translate("""data: {"type":"response.output_text.delta","delta":"你好"}""")
        assertEquals(1, frames.size)
        val payload = parse(frames[0].removePrefix("data: ").trim())
        assertEquals("chat.completion.chunk", payload.get("object").asString)
        assertEquals("cmpl-1", payload.get("id").asString)
        val delta = payload.getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta")
        assertEquals("你好", delta.get("content").asString)
    }

    @Test
    fun `reasoning 增量走到 reasoning_content`() {
        val t = translator()
        val frames = t.translate(
            """data: {"type":"response.reasoning_summary_text.delta","delta":"思考中"}""",
        )
        val delta = parse(frames[0].removePrefix("data: ").trim())
            .getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta")
        assertEquals("思考中", delta.get("reasoning_content").asString)
    }

    @Test
    fun `事件名缺失时回退到 event 行`() {
        val t = translator()
        t.translate("event: response.created")
        // data 里没有 type，应回退到上面记下的 event 名
        val frames = t.translate("data: {}")
        assertEquals(1, frames.size)
        val delta = parse(frames[0].removePrefix("data: ").trim())
            .getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta")
        assertEquals("assistant", delta.get("role").asString)
    }

    @Test
    fun `工具调用先出头帧再出参数增量`() {
        val t = translator()
        val head = t.translate(
            """data: {"type":"response.output_item.added","output_index":1,
               "item":{"type":"function_call","call_id":"call_9","name":"f"}}""",
        )
        val call = parse(head[0].removePrefix("data: ").trim())
            .getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta")
            .getAsJsonArray("tool_calls")[0].asJsonObject
        assertEquals("call_9", call.get("id").asString)
        assertEquals("f", call.getAsJsonObject("function").get("name").asString)
        assertEquals(0, call.get("index").asInt)

        val argFrames = t.translate(
            """data: {"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"a\""}""",
        )
        val argCall = parse(argFrames[0].removePrefix("data: ").trim())
            .getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta")
            .getAsJsonArray("tool_calls")[0].asJsonObject
        assertEquals(0, argCall.get("index").asInt, "参数增量要落在同一个 tool 下标上")
        assertEquals("{\"a\"", argCall.getAsJsonObject("function").get("arguments").asString)
    }

    @Test
    fun `有工具调用时 finish_reason 是 tool_calls`() {
        val t = translator()
        t.translate(
            """data: {"type":"response.output_item.added","output_index":0,
               "item":{"type":"function_call","call_id":"c","name":"f"}}""",
        )
        val frames = t.translate("""data: {"type":"response.completed","response":{}}""")
        val choice = parse(frames[0].removePrefix("data: ").trim())
            .getAsJsonArray("choices")[0].asJsonObject
        assertEquals("tool_calls", choice.get("finish_reason").asString)
        assertEquals("data: [DONE]\n\n", frames[1])
    }

    @Test
    fun `usage 映射 input output 到 prompt completion`() {
        val t = translator()
        val frames = t.translate(
            """data: {"type":"response.completed","response":{"usage":{
               "input_tokens":10,"output_tokens":5,"total_tokens":15}}}""",
        )
        val usage = parse(frames[0].removePrefix("data: ").trim()).getAsJsonObject("usage")
        assertEquals(10, usage.get("prompt_tokens").asInt)
        assertEquals(5, usage.get("completion_tokens").asInt)
        assertEquals(15, usage.get("total_tokens").asInt)
    }

    @Test
    fun `incomplete 且超输出上限时映射成 length`() {
        val t = translator()
        val frames = t.translate(
            """data: {"type":"response.incomplete","response":{"status":"incomplete",
               "incomplete_details":{"reason":"max_output_tokens"}}}""",
        )
        val choice = parse(frames[0].removePrefix("data: ").trim())
            .getAsJsonArray("choices")[0].asJsonObject
        assertEquals("length", choice.get("finish_reason").asString)
    }

    @Test
    fun `上游失败事件把原因作为内容交给客户端并正常收尾`() {
        val t = translator()
        val frames = t.translate(
            """data: {"type":"response.failed","response":{"error":{"message":"boom"}}}""",
        )
        val delta = parse(frames[0].removePrefix("data: ").trim())
            .getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta")
        assertTrue(delta.get("content").asString.contains("boom"))
        assertEquals("stop", parse(frames[0].removePrefix("data: ").trim())
            .getAsJsonArray("choices")[0].asJsonObject.get("finish_reason").asString)
        assertEquals("data: [DONE]\n\n", frames[1])
    }

    @Test
    fun `终态之后不再出帧`() {
        val t = translator()
        t.translate("""data: {"type":"response.completed","response":{}}""")
        // 终态已出，后续任何事件都不该再产生帧（否则客户端会看到两段结束）
        assertTrue(t.translate("""data: {"type":"response.output_text.delta","delta":"x"}""").isEmpty())
        assertTrue(
            t.translate("""data: {"type":"response.completed","response":{}}""").isEmpty(),
        )
    }

    @Test
    fun `onFinish 在没收到终态时补结束帧`() {
        val t = translator()
        val frames = t.onFinish()
        assertEquals(2, frames.size)
        assertEquals("data: [DONE]\n\n", frames[1])
        assertTrue(t.onFinish().isEmpty(), "重复收尾不能重复出帧")
    }

    @Test
    fun `onFinish 在已收终态后不再补帧`() {
        val t = translator()
        t.translate("""data: {"type":"response.completed","response":{}}""")
        assertTrue(t.onFinish().isEmpty(), "上游已经收过尾了，不能再补一次")
    }

    @Test
    fun `DONE 与空数据行被忽略`() {
        val t = translator()
        assertTrue(t.translate("data: [DONE]").isEmpty())
        assertTrue(t.translate("data: ").isEmpty())
        assertTrue(t.translate("").isEmpty())
        assertTrue(t.translate(": keep-alive").isEmpty())
    }

    // ---------------------------------------------------------------- 目录与额度

    @Test
    fun `模型目录只保留 visibility=list`() {
        val raw = """{"models":[
            {"slug":"a","display_name":"A","visibility":"list","context_window":100},
            {"slug":"hidden","display_name":"H","visibility":"hidden"},
            {"id":"b","display_name":"B","context_length":200}]}"""
        val models = OpenAiResponses.parseModels(raw)
        assertEquals(2, models.size, "hidden 的条目要过滤掉")
        assertEquals("a", models[0].id)
        assertEquals("A", models[0].name)
        assertEquals(100L, models[0].contextWindow)
        // 没有 visibility 字段的按可见处理（不能因为缺字段就丢掉）
        assertEquals("b", models[1].id)
        assertEquals(200L, models[1].contextWindow)
    }

    @Test
    fun `模型目录缺 id 的条目被跳过`() {
        val raw = """{"models":[{"display_name":"没有 id"},{"slug":"ok"}]}"""
        val models = OpenAiResponses.parseModels(raw)
        assertEquals(1, models.size)
        assertEquals("ok", models[0].id)
        assertEquals("ok", models[0].name, "缺 display_name 时用 id 兜底")
    }

    @Test
    fun `用量投影成两个 limit=100 的窗口`() {
        val raw = """{"rate_limit":{
            "primary_window":{"used_percent":30.7,"reset_at":1700000000},
            "secondary_window":{"used_percent":85.2,"reset_at":1700100000}}}"""
        val packs = OpenAiResponses.parseUsage(raw)
        assertEquals(2, packs.size)
        assertEquals("5 小时窗口", packs[0].name)
        assertEquals(100L, packs[0].limit)
        assertEquals(30L, packs[0].used, "百分比向下取整")
        assertEquals(70L, packs[0].remain)
        assertEquals(1_700_000_000L, packs[0].expireAt)
        assertEquals("周窗口", packs[1].name)
        assertEquals(85L, packs[1].used)
        assertEquals(15L, packs[1].remain)
    }

    @Test
    fun `用量百分比被夹到合法区间`() {
        // 上游偶尔给 105 或负数，超出会让进度条画出屏幕
        val raw = """{"rate_limit":{"primary_window":{"used_percent":105.5},
            "secondary_window":{"used_percent":-3}}}"""
        val packs = OpenAiResponses.parseUsage(raw)
        assertEquals(100L, packs[0].used)
        assertEquals(0L, packs[0].remain)
        assertEquals(0L, packs[1].used)
        assertEquals(100L, packs[1].remain)
    }

    @Test
    fun `缺窗口或畸形响应返回空列表而不抛异常`() {
        assertTrue(OpenAiResponses.parseUsage("{}").isEmpty())
        assertTrue(OpenAiResponses.parseUsage("not json").isEmpty())
        // 只有 primary 时也要能用
        assertEquals(1, OpenAiResponses.parseUsage("""{"rate_limit":{"primary_window":{"used_percent":1}}}""").size)
    }
}
