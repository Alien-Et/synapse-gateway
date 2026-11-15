package io.synapse.core.provider.doubao

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.SSE_DONE
import io.synapse.core.provider.UpstreamError
import io.synapse.core.util.arrayOrNull
import io.synapse.core.util.asObjectOrNull
import io.synapse.core.util.intOrNull
import io.synapse.core.util.longOrNull
import io.synapse.core.util.objOrNull
import io.synapse.core.util.stringOrNull
import java.util.UUID

/**
 * 豆包 SSE → OpenAI SSE 的逐行翻译器。
 *
 * ## 为什么用「逐行 + 状态机」而不是整段解析
 *
 * [io.synapse.core.provider.LineTransformStream] 是逐行喂的，流式响应必须边收边发
 * （否则首字延迟等于整段生成时间）。所以这里自己维护「当前事件名 + data 缓冲」，
 * 遇到空行才把攒好的一个事件派发出去 —— 这与 SSE 规范一致。
 *
 * ## 实测事件表（来自抓包，见 docs/05）
 *
 * | 事件 | 处理 |
 * |---|---|
 * | `SSE_HEARTBEAT` | 丢弃 |
 * | `SSE_ACK` | **取真实 `conversation_id`**（固定窗口靠它），然后丢弃 |
 * | `FULL_MSG_NOTIFY` | 用户消息回显，丢弃 |
 * | `STREAM_MSG_NOTIFY` | 旁路，丢弃 |
 * | `STREAM_CHUNK` | `patch_op[].patch_value.content_block[]`，按块类型分流 |
 * | `CHUNK_DELTA` | `{"text":"字"}` → `delta.content` |
 * | `DOWNLINK_CMD` | IM 指令帧，丢弃 |
 * | **`STREAM_ERROR`** | **流内业务错误 → OpenAI 顶层 `error` 帧 + `[DONE]`**（见下） |
 * | `SSE_REPLY_END` | `finish_reason: "stop"` |
 *
 * ## 为什么 `STREAM_ERROR` 不能丢
 *
 * 实测（逆向资料 §18.18/§18.19）：账号/设备被频控时豆包回的是 **HTTP 200 + SSE**，
 * 帧序列 `SSE_HEARTBEAT → STREAM_ERROR(710022002「当前服务访问频繁，请稍后再试」)
 * → SSE_REPLY_END`。旧实现的 `else -> emptyList()` 把错误帧整帧吞掉，
 * 只补一个空 `finish_reason:stop`：流式客户端收到「正常结束但零输出」，
 * 非流式被判成「豆包返回空回复」——用户永远看不到真正的原因。
 *
 * 对齐 TraeSse / RaccoonProvider 的既有做法：发 OpenAI 标准顶层 `error` 对象帧，
 * 网关 `GatewayHttpServer.parseChunk` 能识别 `error.message` 并把调用记为 FAILED；
 * 同时把 [failure] 暴露给 `DoubaoProvider` 的非流式路径，返回 `StreamFailureChatCall`
 * 而不是含糊的「空回复」。
 *
 * ## 内容块（实测 block_type）
 *
 * | block_type | 含义 | 输出 |
 * |---|---|---|
 * | `10000` | text_block | `delta.content` |
 * | `10040` | thinking_block | `delta.reasoning_content` |
 * | `10025` | search_query_result_block | 丢弃（引用卡片，OpenAI 无对应位） |
 * | `2074` | **creation_block** | 生图/生视频结果 → Markdown 链接写进 `delta.content` |
 * | `10050` | rich_media_block | 搜索结果媒体，丢弃 |
 */
class DoubaoStreamTranslator(
    private val model: String,
    /** 拿到服务端分配的真实会话 id 时回调（只回调一次）。 */
    private val onConversationId: (String) -> Unit = {},
) {

    private val id: String = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(20)
    private val created: Long = System.currentTimeMillis() / 1000

    private var event = ""
    private val data = StringBuilder()
    private var finished = false
    private var conversationReported = false

    /**
     * 流内业务错误（HTTP 200 但 SSE 里报了错）。
     *
     * `DoubaoProvider` 非流式路径聚合完后读它，返回 `StreamFailureChatCall`，
     * 才能把「访问太频繁」如实传给网关/客户端，而不是误报成「空回复」。
     */
    var failure: UpstreamError? = null
        private set

    /** 已经输出过的媒体 id，避免重复推同一张图（上游会在后续帧里重复下发完整结果）。 */
    private val emittedCreations = HashSet<String>()

    /** 逐行入口：返回要写给下游的 OpenAI SSE 文本片段。 */
    fun translate(line: String): List<String> {
        if (line.isEmpty()) {
            val ev = event
            val payload = data.toString()
            event = ""
            data.setLength(0)
            return dispatch(ev, payload)
        }
        when {
            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
            line.startsWith("data:") -> {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.removePrefix("data:").trim())
            }
            // `id: N` / 其它字段：与 OpenAI 无关
        }
        return emptyList()
    }

    /** 流结束：只有 `STREAM_ERROR` 错误帧已自带 `[DONE]` 时才跳过；正常流照常补，避免半截流。 */
    fun finish(): List<String> = if (failure != null) emptyList() else listOf(SSE_DONE)

    // ------------------------------------------------------------------ 派发

    private fun dispatch(ev: String, payload: String): List<String> {
        return when (ev) {
            "CHUNK_DELTA" -> onChunkDelta(payload)
            "STREAM_CHUNK" -> onStreamChunk(payload)
            "SSE_ACK" -> {
                reportConversation(payload)
                emptyList()
            }
            "STREAM_ERROR" -> onStreamError(payload)
            "SSE_REPLY_END" -> {
                // 已经因 STREAM_ERROR 结束过就不要再补一个「正常 stop」，
                // 否则同一条流里既有 FAILED 又有 SUCCESS，语义自相矛盾。
                if (finished) return emptyList()
                finished = true
                listOf(chunk(JsonObject(), "stop"))
            }
            // SSE_HEARTBEAT / FULL_MSG_NOTIFY / STREAM_MSG_NOTIFY / DOWNLINK_CMD
            else -> emptyList()
        }
    }

    /**
     * `STREAM_ERROR`：HTTP 200 的流内业务错误。
     *
     * 实测载荷：`{"error_code":710022002,"error_msg":"当前服务访问频繁，请稍后再试",
     * "extra":{"ack":"1"}}`。`error_msg` 在抓包里被 Hook 以 latin-1 解码成了乱码，
     * 所以**不盲信原文**：`710022002`（以及发码端点的 `7`）一律映射成稳定的中文
     * 「上游访问频繁，请稍后再试」，其它码保留上游原文（截断防日志爆炸）。
     *
     * 输出 OpenAI 标准顶层 `error` 帧 + `[DONE]`：网关 `parseChunk` 读 `error.message`
     * 把调用记为 FAILED，客户端也能直接看到原因（对齐 TraeSse / RaccoonProvider）。
     */
    private fun onStreamError(payload: String): List<String> {
        if (finished) return emptyList()
        finished = true
        val obj = parse(payload)
        val code = obj?.longOrNull("error_code") ?: 0L
        val raw = obj?.stringOrNull("error_msg").orEmpty()
        val message = when {
            code == RATE_LIMIT_STREAM_CODE -> "上游访问频繁，请稍后再试"
            raw.isNotEmpty() -> "豆包流内错误（$code）：${raw.take(200)}"
            else -> "豆包流内错误（$code）"
        }
        /**
         * 只有实测的频控码才归 `SOFT_RATE`（退避重试）；其它未知流内错误归 `SERVER`
         * （如实失败、不误判成凭证失效或空回复）。一律标 SOFT_RATE 会让「参数不合规」
         * 这类错误也被网关拿去退避重试，那是另一种误报。
         */
        val kind = if (code == RATE_LIMIT_STREAM_CODE) ErrorKind.SOFT_RATE else ErrorKind.SERVER
        failure = UpstreamError(kind, message, code)
        val errorFrame = JsonObject().apply {
            add("error", JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
                addProperty("type", kind.name)
            })
        }
        return listOf("data: $errorFrame\n\n", SSE_DONE)
    }

    /** `CHUNK_DELTA`：`{"text":"字"}`，逐字增量。 */
    private fun onChunkDelta(payload: String): List<String> {
        val obj = parse(payload) ?: return emptyList()
        val text = obj.stringOrNull("text").orEmpty()
        if (text.isEmpty()) return emptyList()
        return listOf(chunk(delta(content = text)))
    }

    /** `STREAM_CHUNK`：`patch_op[].patch_value.content_block[]`。 */
    private fun onStreamChunk(payload: String): List<String> {
        val obj = parse(payload) ?: return emptyList()
        val out = ArrayList<String>()
        val ops = obj.arrayOrNull("patch_op") ?: return emptyList()
        for (opElement in ops) {
            val op = opElement.asObjectOrNull() ?: continue
            val value = op.objOrNull("patch_value") ?: continue
            val blocks = value.arrayOrNull("content_block") ?: continue
            for (blockElement in blocks) {
                val block = blockElement.asObjectOrNull() ?: continue
                out += renderBlock(block)
            }
        }
        return out
    }

    /** 按 `block_type` 分流一个内容块。 */
    private fun renderBlock(block: JsonObject): List<String> {
        val type = block.intOrNull("block_type") ?: return emptyList()
        val content = block.objOrNull("content") ?: return emptyList()
        return when (type) {
            BLOCK_TEXT -> {
                val text = content.objOrNull("text_block")?.stringOrNull("text").orEmpty()
                if (text.isEmpty()) emptyList() else listOf(chunk(delta(content = text)))
            }
            BLOCK_THINKING -> {
                val thinking = content.objOrNull("thinking_block") ?: return emptyList()
                // thinking_block 里主要是标题文案；真正的思考摘要可能在 summary（常为空）
                val summary = thinking.stringOrNull("summary").orEmpty()
                if (summary.isEmpty()) emptyList()
                else listOf(chunk(delta(reasoning = summary)))
            }
            BLOCK_CREATION -> renderCreation(content)
            // 10025 搜索引用 / 10050 富媒体：OpenAI 无对应位，丢弃
            else -> emptyList()
        }
    }

    /**
     * 生图 / 生视频结果 → Markdown 链接。
     *
     * ## 实测结构（见 docs/13.2.3）
     *
     * - **图片**：`creations[].type == 1`，媒体字段 `image`，
     *   判据 `image.image_ori.url` 存在；`task_type == 1`。
     * - **视频**：`creations[].type == 2`，媒体字段 `video`，
     *   判据 **`video.status == 3`** 且 `video.vid` 非空；`task_type == 6`。
     *   直链取 **`video_model.video_list` 的 `main_url`（原片，1080p/60fps）**，
     *   退而求其次才用 `video.download_url`（**720p 转码版**，`qs=0`）。
     *   见 docs/14.13.4「画质与规格」——下载链接码率反而更低，这点很反直觉。
     *
     * ## 为什么「视频」和「图片」的完成判据不同
     *
     * 图片的占位阶段 `status` 也是 1，所以只能看 URL 有没有出现；
     * 视频有独立的 `status` 字段，实测完成态是 `3`。
     * 两者不能套用同一套判断 —— 这是 `creation_block` 里唯一的形态差异。
     *
     * ## 视频是异步的
     *
     * 豆包不会在 SSE 流里返回视频。机器人先回一条「预计等待 10 分钟」，
     * 生成完再 push 一条新消息。所以这条路径大多数时候收不到视频 ——
     * 收到了就说明是历史消息回放或极快的小视频。
     */
    private fun renderCreation(content: JsonObject): List<String> {
        val creations = content.objOrNull("creation_block")?.arrayOrNull("creations") ?: return emptyList()
        val out = ArrayList<String>()
        for (element in creations) {
            val creation = element.asObjectOrNull() ?: continue
            val creationId = creation.stringOrNull("id").orEmpty()
            val type = creation.intOrNull("type") ?: 0

            val image = creation.objOrNull("image")
            val video = creation.objOrNull("video")
            if (image == null && video == null) continue

            val key = creationId.ifEmpty { type.toString() }

            if (video != null || type == TYPE_VIDEO) {
                // ---- 视频：status==3 且 vid 非空才算完成 ----
                val videoStatus = video?.intOrNull("status")
                val vid = video?.stringOrNull("vid").orEmpty()
                if (videoStatus != VIDEO_STATUS_DONE || vid.isEmpty()) continue
                // ⚠️ 画质优先级：`video_list.video_1.main_url`（原片）> `download_url`（转码）
                // 实测「下载」按钮拿的是 720p 转码版（qs=0），原片在 video_list 里，
                // 所以想要最高画质必须用 main_url，不能用 download_url。
                val url = firstVideoUrl(video).ifEmpty { video?.stringOrNull("download_url").orEmpty() }
                if (url.isEmpty()) continue
                if (!emittedCreations.add(key)) continue
                val duration = video?.get("duration")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
                val cover = video?.objOrNull("cover")
                    ?.objOrNull("image_preview")?.stringOrNull("url").orEmpty()
                val markdown = buildString {
                    append("\n[生成的视频](").append(url).append(")")
                    if (duration > 0) append(" · ").append(formatDuration(duration))
                    if (cover.isNotEmpty()) append("\n![封面](").append(cover).append(")")
                    append('\n')
                }
                out += chunk(delta(content = markdown))
            } else {
                // ---- 图片：image_ori / image_thumb 有 URL 才算完成 ----
                val url = image?.objOrNull("image_ori")?.stringOrNull("url")
                    ?: image?.objOrNull("image_thumb")?.stringOrNull("url")
                    ?: ""
                if (url.isEmpty()) continue
                if (!emittedCreations.add(key)) continue
                val modelHint = image?.objOrNull("placeholder")?.stringOrNull("description").orEmpty()
                out += chunk(delta(content = "\n![${modelHint.ifEmpty { "image" }}]($url)\n"))
            }
        }
        return out
    }

    /**
     * 从 `video.video_model`（嵌套 JSON 字符串）里取最高画质的 `main_url`。
     *
     * `video_model` 是个**字符串化的 JSON**，里面的 `main_url` 又是 **base64**。
     * 两层编码，少解一层都拿不到可用的 URL。
     *
     * 按 `definition` 排序取最高档（实测 `video_1` 是原片 1080p/60fps，
     * `video_2` 是 h264 兼容版），不要盲信 key 的顺序。
     */
    private fun firstVideoUrl(video: JsonObject?): String {
        val raw = video?.stringOrNull("video_model").orEmpty()
        if (raw.isEmpty()) return ""
        val model = parse(raw) ?: return ""
        val list = model.objOrNull("video_list") ?: return ""
        var best = ""
        var bestScore = -1L
        for ((_, value) in list.entrySet()) {
            val item = value.asObjectOrNull() ?: continue
            val encoded = item.stringOrNull("main_url").orEmpty()
            if (encoded.isEmpty()) continue
            val decoded = runCatching {
                String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            }.getOrNull().orEmpty()
            if (!decoded.startsWith("http")) continue
            val bitrate = item.get("bitrate")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
            if (bitrate > bestScore) { bestScore = bitrate; best = decoded }
        }
        return best
    }

    /** `15.073` → `15.1s`；`65.5` → `1:05`。 */
    private fun formatDuration(seconds: Double): String {
        if (seconds < 60) return String.format(java.util.Locale.US, "%.1fs", seconds)
        val total = seconds.toInt()
        return String.format(java.util.Locale.US, "%d:%02d", total / 60, total % 60)
    }

    /** 从 `SSE_ACK` 里取真实会话 id（`ack_client_meta.conversation_id`）。 */
    private fun reportConversation(payload: String) {
        if (conversationReported) return
        val obj = parse(payload) ?: return
        val meta = obj.objOrNull("ack_client_meta") ?: return
        val newId = meta.stringOrNull("conversation_id").orEmpty()
        if (newId.isEmpty()) return
        conversationReported = true
        onConversationId(newId)
    }

    // ------------------------------------------------------------------ 帧构造

    private fun parse(payload: String): JsonObject? {
        if (payload.isEmpty()) return null
        return runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
    }

    private fun delta(content: String? = null, reasoning: String? = null): JsonObject =
        JsonObject().apply {
            content?.let { addProperty("content", it) }
            reasoning?.let { addProperty("reasoning_content", it) }
        }

    private fun chunk(delta: JsonObject, finishReason: String? = null): String {
        val choice = JsonObject().apply {
            addProperty("index", 0)
            add("delta", delta)
            if (finishReason != null) addProperty("finish_reason", finishReason)
            else add("finish_reason", JsonNull.INSTANCE)
        }
        val obj = JsonObject().apply {
            addProperty("id", id)
            addProperty("object", "chat.completion.chunk")
            addProperty("created", created)
            addProperty("model", model)
            add("choices", JsonArray().apply { add(choice) })
        }
        return "data: $obj\n\n"
    }

    private companion object {
        const val BLOCK_TEXT = 10000
        const val BLOCK_THINKING = 10040
        const val BLOCK_CREATION = 2074

        /** `creations[].type`：1 = 图片，2 = 视频。 */
        const val TYPE_VIDEO = 2

        /** `video.status`：实测完成态是 3。 */
        const val VIDEO_STATUS_DONE = 3

        /** `/chat/completion` 流内频控码（与发码端点 `error_code=7` 同源，实测）。 */
        const val RATE_LIMIT_STREAM_CODE = 710022002L
    }
}
