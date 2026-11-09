package io.synapse.core.gateway

import io.synapse.core.provider.ApiEndpoints
import io.synapse.core.provider.EndpointKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 端点目录与大厂协议配置的回归。
 *
 * 这两张表是「加更多大厂端点」需求的唯一事实来源：路由、provider 能力声明、
 * 编辑页下拉都从这里取。这里主要防两件事：
 * 1. 别名路径漏认（客户端写法不同就 404）；
 * 2. 把「能看图」的模型错判成「画图」的模型。
 */
class ProviderProtocolsTest {

    // ---------------------------------------------------------------- 端点目录

    @Test
    fun `对话端点及其别名都能匹配`() {
        for (path in listOf("/v1/chat/completions", "/chat/completions")) {
            val ep = ApiEndpoints.match(path)
            assertNotNull(ep)
            assertEquals(EndpointKind.CHAT, ep.kind)
        }
    }

    @Test
    fun `全部透传端点都能通过主路径与别名匹配`() {
        for (endpoint in ApiEndpoints.PASSTHROUGH) {
            assertTrue(endpoint.kind == EndpointKind.PASSTHROUGH)
            for (path in endpoint.allPaths) {
                assertNotNull(ApiEndpoints.match(path), "$path 没被认出来")
            }
        }
    }

    @Test
    fun `生图语音向量重排审核视频端点都在目录里`() {
        val paths = listOf(
            "/v1/images/generations",
            "/v1/images/edits",
            "/v1/images/variations",
            "/v1/audio/transcriptions",
            "/v1/audio/translations",
            "/v1/audio/speech",
            "/v1/embeddings",
            "/v1/rerank",
            "/v1/moderations",
            "/v1/videos/generations",
        )
        for (path in paths) {
            assertNotNull(ApiEndpoints.match(path), "$path 缺失")
        }
    }

    @Test
    fun `重排的三种大厂路径都认`() {
        for (path in listOf("/v1/rerank", "/rerank", "/v1/paas/v4/rerank", "/paas/v4/rerank")) {
            assertEquals("重排", ApiEndpoints.match(path)?.label, path)
        }
    }

    @Test
    fun `语音合成标记为二进制响应`() {
        assertTrue(ApiEndpoints.AUDIO_SPEECH.binaryResponse)
        assertFalse(ApiEndpoints.IMAGES_GENERATIONS.binaryResponse, "生图返回 JSON(base64)，不是二进制")
    }

    @Test
    fun `未知路径返回 null 且 labelOf 原样返回`() {
        assertNull(ApiEndpoints.match("/v1/unknown"))
        assertFalse(ApiEndpoints.isChat("/v1/images/generations"))
        assertTrue(ApiEndpoints.isChat("/v1/chat/completions"))
        assertEquals("/v1/unknown", ApiEndpoints.labelOf("/v1/unknown"))
        assertEquals("文生图", ApiEndpoints.labelOf("/v1/images/generations"))
    }

    // ---------------------------------------------------------------- 协议表

    @Test
    fun `九个大厂协议都登记了`() {
        val ids = ProviderProtocols.ALL.map { it.id }
        for (id in listOf(
            "openai", "anthropic", "gemini", "zhipu", "dashscope",
            "volcengine", "cohere", "deepseek", "moonshot",
        )) {
            assertTrue(id in ids, "$id 没登记")
        }
    }

    @Test
    fun `未知协议 id 回退到 OpenAI 兼容`() {
        assertEquals(ProviderProtocols.OPENAI, ProviderProtocols.of("not-a-protocol"))
        assertEquals(ProviderProtocols.OPENAI, ProviderProtocols.of(""))
        assertEquals(ProviderProtocols.ANTHROPIC, ProviderProtocols.of("ANTHROPIC"))
    }

    @Test
    fun `Anthropic 用 x-api-key 与 messages 端点`() {
        val p = ProviderProtocols.ANTHROPIC
        assertEquals(ProtocolAuth.X_API_KEY, p.auth)
        assertEquals("/v1/messages", p.chatPath)
    }

    @Test
    fun `Gemini 用查询参数鉴权与 generateContent 端点`() {
        val p = ProviderProtocols.GEMINI
        assertEquals(ProtocolAuth.QUERY_KEY, p.auth)
        assertTrue(p.chatPath.contains("generateContent"))
    }

    @Test
    fun `大厂专有端点并入 extraPaths`() {
        assertTrue(ProviderProtocols.ZHIPU.extraPaths.any { it.contains("embeddings") })
        assertTrue(ProviderProtocols.DASHSCOPE.extraPaths.any { it.contains("image-synthesis") })
        assertTrue(ProviderProtocols.VOLCENGINE.extraPaths.any { it.contains("images/generations") })
        assertTrue(ProviderProtocols.COHERE.extraPaths.any { it.contains("rerank") })
    }

    @Test
    fun `Base URL 与协议路径重叠时不重复也不剥掉用户写的版本段`() {
        assertEquals(
            "https://x.com/v1/chat/completions",
            ProviderProtocols.joinBaseAndPath("https://x.com/v1", "/v1/chat/completions"),
        )
        assertEquals(
            "https://x.com/v1/chat/completions",
            ProviderProtocols.joinBaseAndPath("https://x.com", "/v1/chat/completions"),
        )
        // 以前会剥掉 /v1 再贴 /chat/completions，中转站因此 404
        assertEquals(
            "https://relay.example/v1/chat/completions",
            ProviderProtocols.joinBaseAndPath("https://relay.example/v1", "/chat/completions"),
        )
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            ProviderProtocols.joinBaseAndPath(
                "https://open.bigmodel.cn/api/paas/v4",
                "/api/paas/v4/chat/completions",
            ),
        )
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            ProviderProtocols.joinBaseAndPath(
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "/compatible-mode/v1/chat/completions",
            ),
        )
        assertEquals(
            "https://x.com/v1/chat/completions",
            ProviderProtocols.joinBaseAndPath("https://x.com/v1/chat/completions", "/v1/chat/completions"),
        )
        assertEquals(
            "https://x.com/v1/chat/completions",
            ProviderProtocols.joinBaseAndPath("https://x.com/v1/models", "/v1/chat/completions"),
        )
        assertEquals(
            "https://x.com/V1/chat/completions",
            ProviderProtocols.joinBaseAndPath("https://x.com/V1/", "/v1/chat/completions"),
        )
    }

    @Test
    fun `接口不存在的 404 不当成没有这个模型`() {
        val provider = io.synapse.core.provider.custom.CustomProvider(
            io.synapse.core.gateway.CustomProviderConfig(
                key = "site",
                name = "site",
                baseUrl = "https://relay.example/v1",
                apiKeys = emptyList(),
            ),
        )
        val endpoint = provider.classify(
            404,
            """{"error":{"message":"The requested endpoint was not found."}}""",
        )
        assertEquals(io.synapse.core.provider.ErrorKind.CLIENT, endpoint.kind)
        assertTrue(endpoint.message.contains("接口"))

        val model = provider.classify(
            404,
            """{"error":{"message":"The model `foo` does not exist"}}""",
        )
        assertEquals(io.synapse.core.provider.ErrorKind.NOT_FOUND, model.kind)
    }

    @Test
    fun `协议选择项 id 唯一`() {
        val ids = ProviderProtocols.choices().map { it.first }
        assertEquals(ids.size, ids.toSet().size, "下拉框里不能有重复 id")
    }

    // ---------------------------------------------------------------- 余额预设

    @Test
    fun `预设只补空不覆盖用户手填`() {
        val cfg = BalanceQueryConfig(
            preset = "new_api",
            field = "data.my_quota", // 用户手改过字段
            unit = "点",            // 用户手改过单位
        )
        val applied = BalancePresets.apply(cfg)
        assertEquals("data.my_quota", applied.field, "预设不能覆盖用户手填")
        assertEquals("点", applied.unit)
        assertEquals("/api/user/self", applied.path, "没填的路径应该被补上")
        assertEquals(500_000.0, applied.scale)
        assertTrue(applied.divide)
    }

    @Test
    fun `未知预设原样返回`() {
        val cfg = BalanceQueryConfig(preset = "xxx", path = "/x")
        assertEquals(cfg, BalancePresets.apply(cfg))
    }

    @Test
    fun `预设 id 唯一且 choices 含自定义项`() {
        val ids = BalancePresets.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals("自定义", BalancePresets.choices().first().second)
    }

    @Test
    fun `原始数字预设用空字段路径`() {
        val p = BalancePresets.of("raw_number")
        assertNotNull(p)
        assertEquals("", p.field, "空路径表示直接取根节点")
    }

    @Test
    fun `余额配置新增字段能往返序列化`() {
        val original = BalanceQueryConfig(
            url = "", path = "/api/x", field = "data.quota",
            method = "POST", body = """{"a":1}""", preset = "new_api", unit = "$",
        )
        val restored = BalanceQueryConfig.fromJson(original.toJson())
        assertNotNull(restored)
        assertEquals(original, restored)
    }

    // ---------------------------------------------------------------- 生图模型识别

    @Test
    fun `已知生图模型 id 被识别`() {
        for (id in listOf(
            "seedream-4.0", "doubao-seedream-5-lite", "qwen-image-3.0-pro",
            "dall-e-3", "gpt-image-1", "flux-1.1-pro", "cogview-3", "hunyuan-image",
        )) {
            assertTrue(
                io.synapse.core.provider.ProviderModel(id, id).isImageGeneration,
                "$id 应该被识别为生图模型",
            )
        }
    }

    @Test
    fun `上游 ability 说生图就识别`() {
        val model = io.synapse.core.provider.ProviderModel(
            id = "custom-abc", name = "绘图模型",
            extra = mapOf("ability" to "支持文生图与图片编辑"),
        )
        assertTrue(model.isImageGeneration)
    }

    @Test
    fun `tags 里带 image 也识别`() {
        val model = io.synapse.core.provider.ProviderModel(
            id = "model-x", name = "X",
            extra = mapOf("tags" to "image-generation"),
        )
        assertTrue(model.isImageGeneration)
    }

    @Test
    fun `vision 与 vl 是识图不是生图`() {
        // 这两个最容易误判：混进来会让正常对话模型的探测被跳过
        for (id in listOf(
            "qwen3-vl-plus", "gpt-4-vision-preview", "glm-4v-plus",
            "doubao-1.5-vision-pro", "gemini-2.5-flash",
        )) {
            val model = io.synapse.core.provider.ProviderModel(id, id)
            assertFalse(
                model.isImageGeneration,
                "$id 是能看图/对话模型，不该被当成生图",
            )
        }
    }

    @Test
    fun `带 image 词根的模型被识别为生图`() {
        // agnes-image-2.0-flash 这类「品牌-用途」命名，之前漏判 → 测试打到 chat/completions → 400
        for (id in listOf(
            "agnes-image-2.0-flash", "agnes-image-2.5-flash", "some-brand-image-1",
        )) {
            assertTrue(
                io.synapse.core.provider.ProviderModel(id, id).isImageGeneration,
                "$id 应该被识别为生图模型",
            )
        }
    }

    @Test
    fun `识图模型不会被 image 兜底词根误判成生图`() {
        // vision/vl/vlm/understand 这些走的是对话端点，误判会让正常对话测试被跳过
        for (id in listOf(
            "xxx-image-understanding", "qwen-vl-image", "vlm-image-caption",
        )) {
            assertFalse(
                io.synapse.core.provider.ProviderModel(id, id).isImageGeneration,
                "$id 是识图模型，不该当成生图",
            )
        }
    }

    @Test
    fun `生视频模型单独识别`() {
        for (id in listOf("veo-3", "sora-2", "kling-v2", "seedance-1", "hailuo-video")) {
            val model = io.synapse.core.provider.ProviderModel(id, id)
            assertTrue(model.isVideoGeneration, "$id 应该被识别为生视频模型")
            assertTrue(model.isMediaGeneration, "生视频也算媒体生成，不能走对话端点")
        }
        assertFalse(io.synapse.core.provider.ProviderModel("gpt-4o", "gpt-4o").isVideoGeneration)
    }

    @Test
    fun `上游能力字段能直接声明视频`() {
        val model = io.synapse.core.provider.ProviderModel(
            id = "brand-x", name = "X",
            extra = mapOf("ability" to "文生视频"),
        )
        assertTrue(model.isVideoGeneration)
    }

    @Test
    fun `模型按类型分到各自的端点`() {
        fun kind(id: String) = io.synapse.core.provider.ProviderModel(id, id).endpointKind
        // 之前只分「生图 / 对话」，向量、重排、语音全被当成对话 → 测试一律 400
        assertEquals(io.synapse.core.provider.ModelEndpointKind.CHAT, kind("gpt-4o"))
        assertEquals(io.synapse.core.provider.ModelEndpointKind.IMAGE, kind("agnes-image-2.0-flash"))
        assertEquals(io.synapse.core.provider.ModelEndpointKind.VIDEO, kind("veo-3"))
        assertEquals(io.synapse.core.provider.ModelEndpointKind.EMBEDDING, kind("text-embedding-3-large"))
        assertEquals(io.synapse.core.provider.ModelEndpointKind.EMBEDDING, kind("bge-m3"))
        assertEquals(io.synapse.core.provider.ModelEndpointKind.RERANK, kind("bge-reranker-v2"))
        assertEquals(io.synapse.core.provider.ModelEndpointKind.TRANSCRIPTION, kind("whisper-1"))
        assertEquals(io.synapse.core.provider.ModelEndpointKind.SPEECH, kind("tts-1"))
    }

    @Test
    fun `上游声明端点类型时优先采信`() {
        val model = io.synapse.core.provider.ProviderModel(
            id = "brand-x", name = "X",
            extra = mapOf("endpoint" to "EMBEDDING"),
        )
        assertEquals(io.synapse.core.provider.ModelEndpointKind.EMBEDDING, model.endpointKind)
    }

    @Test
    fun `Responses 协议带 input 形状且默认强制流式`() {
        val p = ProviderProtocols.OPENAI_RESPONSES
        assertEquals(RequestShape.RESPONSES, p.requestShape)
        assertEquals("/v1/responses", p.chatPath)
        assertTrue(p.forceStreaming, "上游只吃流式，默认就得强制")
        // 普通 OpenAI 兼容不受影响
        assertEquals(RequestShape.CHAT, ProviderProtocols.OPENAI.requestShape)
        assertFalse(ProviderProtocols.OPENAI.forceStreaming)
    }

    @Test
    fun `识图能力走 supportsImageInput`() {
        val model = io.synapse.core.provider.ProviderModel(
            id = "qwen-vl", name = "VL",
            extra = mapOf("vision" to "true"),
        )
        assertTrue(model.supportsImageInput)
        assertFalse(model.isImageGeneration)
    }
}
