package io.synapse.core.provider.doubao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `creation_block` 的解析。
 *
 * ## 为什么这些用例值得写
 *
 * 生图 / 生视频的字段名**全靠实测**，而且两者形态不同：
 *
 * | 项 | 图片 | 视频 |
 * |---|---|---|
 * | `creations[].type` | `1` | `2` |
 * | `gen_detail.task_type` | `1` | `6` |
 * | 媒体字段 | `image` | `video` |
 * | 完成判据 | `image_ori.url` 存在 | **`status == 3`** |
 *
 * 早期实现猜过 `video.url` / `video.video_play`，**全是错的** ——
 * 这几个用例就是防止再猜回去。
 *
 * 数据取自真实会话（见 docs/13.2.3），字段值做了截断但结构 1:1。
 */
class DoubaoStreamTranslatorTest {

    private fun translateOneBlock(blockJson: String): String {
        val translator = DoubaoStreamTranslator(model = "seedance_v2.0_mini")
        val raw = """event: STREAM_CHUNK
data: {"patch_op":[{"patch_value":{"content_block":[$blockJson]}}]}

"""
        val out = StringBuilder()
        for (line in raw.split("\n")) out.append(translator.translate(line))
        return out.toString()
    }

    /** 视频 `video_model` 里 `main_url` 是 base64，这里造两档，码率不同。 */
    private fun videoModelJson(): String {
        val hi = java.util.Base64.getEncoder()
            .encodeToString("https://v.example.com/original.mp4".toByteArray())
        val lo = java.util.Base64.getEncoder()
            .encodeToString("https://v.example.com/low.mp4".toByteArray())
        return """{"auto_definition":"360p","video_list":{
            "video_1":{"definition":"720p","codec_type":"bytevc1","bitrate":1563964,"main_url":"$lo"},
            "video_2":{"definition":"1080p","codec_type":"h264","bitrate":1774194,"main_url":"$hi"}
        }}""".replace("\n", "")
    }

    @Test
    fun `视频 type 为 2 且 status 为 3 时输出 markdown`() {
        val videoModel = videoModelJson().replace("\"", "\\\"")
        val block = """{"block_type":2074,"content":{"creation_block":{"creations":[
            {"type":2,"id":"61851887734175746","can_publish":true,
             "gen_detail":{"task_type":6,"ref_images":[]},
             "video":{"status":3,"vid":"v0369cg10004","duration":15.073,
                      "download_url":"https://v.example.com/dl.mp4",
                      "video_model":"$videoModel",
                      "cover":{"image_preview":{"url":"https://c.example.com/cover.jpg"}}}}
        ]}}}"""

        val out = translateOneBlock(block)
        assertTrue(out.contains("生成的视频"), "应输出视频标记：$out")
        // ★ 应优先取 video_list 里码率最高的 main_url，而不是 download_url
        assertTrue(out.contains("https://v.example.com/original.mp4"),
            "应取最高码率的 main_url（原片），实际：$out")
        assertFalse(out.contains("v.example.com/dl.mp4"),
            "不应回退到 download_url 转码版，实际：$out")
        assertTrue(out.contains("15.1s"), "应带时长：$out")
    }

    @Test
    fun `视频 status 不是 3 时不输出（生成中）`() {
        val block = """{"block_type":2074,"content":{"creation_block":{"creations":[
            {"type":2,"id":"t1","gen_detail":{"task_type":6},
             "video":{"status":1,"vid":"v123","download_url":"https://v.example.com/dl.mp4"}}
        ]}}}"""
        val out = translateOneBlock(block)
        assertFalse(out.contains("生成的视频"), "生成中不应输出：$out")
    }

    @Test
    fun `视频 vid 为空时不输出`() {
        val block = """{"block_type":2074,"content":{"creation_block":{"creations":[
            {"type":2,"id":"t2","gen_detail":{"task_type":6},
             "video":{"status":3,"vid":"","download_url":"https://v.example.com/dl.mp4"}}
        ]}}}"""
        val out = translateOneBlock(block)
        assertFalse(out.contains("生成的视频"), "没有 vid 不应输出：$out")
    }

    @Test
    fun `video_model 缺失时回退到 download_url`() {
        val block = """{"block_type":2074,"content":{"creation_block":{"creations":[
            {"type":2,"id":"t3","gen_detail":{"task_type":6},
             "video":{"status":3,"vid":"v456","duration":8.0,
                      "download_url":"https://v.example.com/dl.mp4"}}
        ]}}}"""
        val out = translateOneBlock(block)
        assertTrue(out.contains("https://v.example.com/dl.mp4"),
            "无 video_model 时应回退 download_url：$out")
    }

    @Test
    fun `图片 type 为 1 且 image_ori 有 URL 时输出 markdown 图片`() {
        val block = """{"block_type":2074,"content":{"creation_block":{"creations":[
            {"type":1,"id":"img-1","gen_detail":{"task_type":1},
             "image":{"status":1,
                      "placeholder":{"width":1664,"height":2496,"description":"Seedream 4.5"},
                      "image_ori":{"url":"https://p.example.com/ori.png"}}}
        ]}}}"""
        val out = translateOneBlock(block)
        assertTrue(out.contains("![Seedream 4.5](https://p.example.com/ori.png)"),
            "应输出带模型名的图片 markdown：$out")
    }

    @Test
    fun `图片占位阶段（无 URL）不输出`() {
        val block = """{"block_type":2074,"content":{"creation_block":{"creations":[
            {"type":1,"id":"img-2","gen_detail":{"task_type":1},
             "image":{"status":1,"placeholder":{"width":1664,"height":2496,"description":"Seedream 4.5"}}}
        ]}}}"""
        val out = translateOneBlock(block)
        assertFalse(out.contains("!["), "占位阶段不应输出图片：$out")
    }

    @Test
    fun `同一个 creation 重复下发只输出一次`() {
        val videoModel = videoModelJson().replace("\"", "\\\"")
        val block = """{"block_type":2074,"content":{"creation_block":{"creations":[
            {"type":2,"id":"same-id","gen_detail":{"task_type":6},
             "video":{"status":3,"vid":"v789","download_url":"https://v.example.com/x.mp4",
                      "video_model":"$videoModel"}}
        ]}}}"""
        val translator = DoubaoStreamTranslator(model = "seedance_v2.0_mini")
        fun feed(): String {
            val raw = """event: STREAM_CHUNK
data: {"patch_op":[{"patch_value":{"content_block":[$block]}}]}

"""
            val sb = StringBuilder()
            for (line in raw.split("\n")) sb.append(translator.translate(line))
            return sb.toString()
        }
        val first = feed()
        val second = feed()

        assertTrue(first.contains("生成的视频"), "第一次应输出")
        assertFalse(second.contains("生成的视频"), "第二次应去重，实际：$second")
    }

    @Test
    fun `thinking_block 输出 reasoning_content`() {
        val translator = DoubaoStreamTranslator(model = "seed")
        val raw = """event: STREAM_CHUNK
data: {"patch_op":[{"patch_value":{"content_block":[
  {"block_type":10040,"content":{"thinking_block":{"summary":"先想一想"}}}
]}}]}

"""
        val out = StringBuilder()
        for (line in raw.split("\n")) out.append(translator.translate(line))
        assertTrue(out.toString().contains("reasoning_content"), "应输出 reasoning_content")
    }

    @Test
    fun `SSE_ACK 上报真实 conversation_id`() {
        var reported = ""
        val translator = DoubaoStreamTranslator(model = "seed") { reported = it }
        val raw = """event: SSE_ACK
data: {"ack_client_meta":{"conversation_id":"38453873148032002"}}

"""
        for (line in raw.split("\n")) translator.translate(line)
        assertEquals("38453873148032002", reported)
    }

    @Test
    fun `SSE_REPLY_END 输出 finish_reason stop`() {
        val translator = DoubaoStreamTranslator(model = "seed")
        val out = translator.translate("event: SSE_REPLY_END").toMutableList<String>()
        for (line in "\ndata: {}\n\n".split("\n")) out += translator.translate(line)
        assertTrue(out.joinToString("").contains("\"finish_reason\":\"stop\""),
            "应以 stop 结束：$out")
    }

    @Test
    fun `finish 输出 DONE`() {
        val translator = DoubaoStreamTranslator(model = "seed")
        assertTrue(translator.finish().joinToString("").contains("[DONE]"))
    }
}
