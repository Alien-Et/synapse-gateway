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
}
