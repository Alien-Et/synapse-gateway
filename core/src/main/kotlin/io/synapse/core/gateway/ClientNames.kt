package io.synapse.core.gateway

/**
 * 下游客户端标识的解析与归一。
 *
 * 网关只有**一个** `settings.apiKey`，没有多 Key 命名体系，所以「谁在打网关」只能从
 * `User-Agent` 反推。这里集中放规则表，两处共用：
 *
 * 1. 请求进来时：[GatewayHttpServer] 用它把 UA 变成 `clientName` 写进调用记录与在途表；
 * 2. 展示历史记录时：早期版本没有规则表，存的是整条 UA 产品名（如 `SomeClient-Android/3.3.0`），
 *    界面用 [normalize] 再跑一次规则，把旧数据收敛成同一个名字，避免同一个客户端
 *    在拓扑里分裂成两个节点。
 *
 * 认不出时取 UA 的产品名（第一个 `/` 或空格之前）并截到 16 字符——整条 UA 动辄几十字符，
 * 直接塞进标签会把布局撑坏。绝不编造名字。
 */
object ClientNames {

    /** 空串表示「认不出来」，由界面决定怎么显示；这里不替界面造词。 */
    fun fromUserAgent(ua: String): String {
        val raw = ua.trim()
        if (raw.isEmpty()) return ""
        val l = raw.lowercase()
        return when {
            l.contains("cursor") -> "Cursor"
            l.contains("cline") -> "Cline"
            l.contains("continue") -> "Continue"
            l.contains("windsurf") -> "Windsurf"
            l.contains("kilo") -> "Kilo Code"
            l.contains("roo") -> "Roo Code"
            l.contains("vscode") -> "VS Code"
            l.contains("jetbrains") -> "JetBrains"
            l.contains("cherry") -> "Cherry Studio"
            l.contains("chatbox") -> "Chatbox"
            l.contains("lobe") -> "LobeChat"
            l.contains("nextchat") -> "NextChat"
            l.contains("rikkahub") -> "RikkaHub"
            l.contains("okhttp") -> "Android"
            l.contains("cfnetwork") || l.contains("darwin") -> "iOS"
            l.contains("dart") -> "Flutter"
            l.contains("python") -> "Python"
            l.contains("curl") -> "curl"
            l.contains("postman") -> "Postman"
            l.contains("node") || l.contains("axios") -> "Node"
            l.contains("openai") -> "OpenAI SDK"
            else -> raw.substringBefore('/').substringBefore(' ').take(16).ifEmpty { "" }
        }
    }

    /**
     * 把已存下来的客户端名再归一一次（历史数据清洗）。
     *
     * 对 [fromUserAgent] 的输出是幂等的：正常名字再跑一遍还是它自己；
     * 旧版本存下的整条 UA 会被收敛成规则里的正名。
     */
    fun normalize(stored: String): String = fromUserAgent(stored)
}
