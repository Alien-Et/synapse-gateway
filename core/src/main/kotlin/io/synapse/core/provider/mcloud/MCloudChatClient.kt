package io.synapse.core.provider.mcloud

/**
 * 对话客户端。
 *
 * 上游 `modelUrl` 就是标准 OpenAI 兼容的 chat/completions（`Bearer <apikey>` 鉴权），
 * 所以这里不做任何协议改写：选号 → 补头 → 转发 → 原样回传 SSE。
 *
 * 需要注意的坑：`modelUrl` 里带渠道号（`/api/llm/102001/v1/chat/completions`），
 * **不能自己用 aiBase 拼**，否则渠道号不对会直接 404/鉴权失败。
 */
class MCloudChatClient {

    /** 发起一次对话，返回尚未读取的连接。 */
    fun openStream(account: MCloudAccount, body: String): MCloudStreamCall {
        val headers = mapOf(
            "Accept" to "text/event-stream",
            "Content-Type" to "application/json",
            "Authorization" to "Bearer ${account.apiKey}",
            "User-Agent" to MCloudConstants.USER_AGENT,
        )
        return openChatStream(
            url = account.modelUrl.ifEmpty {
                "${MCloudConstants.AI_BASE}/api/llm/${MCloudConstants.CHANNEL_ID}${MCloudConstants.PATH_CHAT_COMPLETIONS}"
            },
            headers = headers,
            body = body,
        )
    }

    companion object {
        /**
         * 非对话端点的透传（生图 / 视频 / 模型列表）。
         *
         * 与对话共用同一套鉴权头与渠道前缀，只是路径不同、不读 SSE。
         * 响应体原样返回：图片是 base64、视频是任务 JSON，任何改写都可能损坏内容。
         */
        fun passthrough(
            account: MCloudAccount,
            method: String,
            url: String,
            body: String?,
        ): io.synapse.core.provider.ChatCall {
            val headers = mapOf(
                "Accept" to "application/json",
                "Authorization" to "Bearer ${account.apiKey}",
                "User-Agent" to MCloudConstants.USER_AGENT,
            ) + if (body != null) mapOf("Content-Type" to "application/json") else emptyMap()
            val conn = try {
                io.synapse.core.util.HttpSupport.open(
                    url = url,
                    method = method,
                    headers = headers,
                    body = body,
                    connectTimeoutMs = 20_000,
                    readTimeoutMs = 180_000,
                )
            } catch (e: Exception) {
                return io.synapse.core.provider.FailedChatCall(0, e.message ?: "连接失败")
            }
            val status = try {
                conn.responseCode
            } catch (e: Exception) {
                conn.disconnect()
                return io.synapse.core.provider.FailedChatCall(0, e.message ?: "连接失败")
            }
            if (status !in 200..299) {
                val errBody = runCatching {
                    io.synapse.core.util.HttpSupport.readLimited(conn.errorStream)
                }.getOrDefault("")
                conn.disconnect()
                return io.synapse.core.provider.FailedChatCall(status, errBody)
            }
            val text = try {
                conn.inputStream.use { io.synapse.core.util.HttpSupport.readLimited(it) }
            } catch (e: Exception) {
                conn.disconnect()
                return io.synapse.core.provider.FailedChatCall(0, e.message ?: "读取响应失败")
            } finally {
                runCatching { conn.disconnect() }
            }
            return io.synapse.core.provider.AggregatedChatCall(status, text)
        }
    }
}
