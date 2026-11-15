package io.synapse.core.provider.doubao

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 豆包凭证。
 *
 * ## 为什么是「整串 Cookie」而不是拆字段
 *
 * 豆包的鉴权全在 Cookie 里，且**字段会随版本增加**（`passport_csrf_token`、
 * `odin_tt`、`passport_mfa_token` 都是登录后才出现的）。拆成固定字段意味着
 * 每次上游加一个 Cookie 就要改一次解析、改一次数据结构 —— 而多带的 Cookie
 * 对上游无害，少带才会 401。
 *
 * 所以这里**原样保存整串 Cookie**，只在需要判空/展示时按需读关键几项。
 *
 * ## 会话 id 为什么也放这里
 *
 * 「固定一个窗口」要求把服务端分配的 `conversationId` 持久化。它天然是
 * **跟着账号走**的（换账号就该换会话），与凭证同生命周期，放一起最省事。
 * 注意它与「线路设置」（专家/极速）不同：线路是用户偏好，存 provider options；
 * 会话 id 是上游分配的状态，存凭证。
 */
data class DoubaoCredential(
    /** 原始 Cookie 串，原样回传。 */
    val cookie: String,
    /** 用户 uid（`__tea__ug__uid`），用于展示与 `sender_id`。 */
    val uid: String,
    /** 设备标识（`cdid`），与 ttwid 成对，换机会触发风控。 */
    val cdid: String,
    /** 设备 id（`device_id`）。 */
    val deviceId: String,
    /** 机器人 id，默认主对话 bot。 */
    val botId: String = DEFAULT_BOT_ID,
    /**
     * 固定窗口的会话 id。空串表示「下一次请求让服务端分配」。
     *
     * 实测：首次请求带 `main_<uuid>_<botId>-local` 占位，服务端在响应里回真实 id
     * （如 `38453774466884610`），之后复用即可。
     */
    val conversationId: String = "",
) {

    /** Cookie 里是否含主凭证 `d_ticket`。 */
    val hasTicket: Boolean get() = cookie.contains("d_ticket=")

    /** 按名字取单个 Cookie 值（取不到返回空串）。 */
    fun cookieValue(name: String): String {
        for (part in cookie.split(';')) {
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq <= 0) continue
            if (trimmed.substring(0, eq).trim() == name) return trimmed.substring(eq + 1).trim()
        }
        return ""
    }

    fun toSecret(): String = JsonObject().apply {
        addProperty("cookie", cookie)
        addProperty("uid", uid)
        addProperty("cdid", cdid)
        addProperty("deviceId", deviceId)
        addProperty("botId", botId)
        addProperty("conversationId", conversationId)
    }.toString()

    companion object {
        /** 主对话 bot（实测抓包里的 `bot_id`）。 */
        const val DEFAULT_BOT_ID = "7234781073513644036"

        /**
         * 解析凭证。
         *
         * 两种输入都认：
         * 1. 本类自己 [toSecret] 出来的 JSON；
         * 2. **用户直接粘贴的原始 Cookie 串**（从抓包里复制的那种）。
         *
         * 第 2 种是刻意的：让用户「把 Cookie 贴进来就能用」，
         * 不必先手工拼 JSON —— 少一步手工操作就少一处出错。
         */
        fun parse(raw: String): DoubaoCredential? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            if (!text.startsWith("{")) {
                // 原始 Cookie 串：uid / cdid 尽量从 Cookie 里挖，挖不到留空
                val cookie = text.removePrefix("Cookie:").trim()
                if (!cookie.contains("d_ticket=")) return null
                return DoubaoCredential(
                    cookie = cookie,
                    uid = extractCookie(cookie, "__tea__ug__uid"),
                    cdid = extractCookie(cookie, "cdid"),
                    deviceId = "",
                )
            }
            val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
            val cookie = obj.stringOrEmpty("cookie")
            if (cookie.isEmpty() || !cookie.contains("d_ticket=")) return null
            return DoubaoCredential(
                cookie = cookie,
                uid = obj.stringOrEmpty("uid").ifEmpty { extractCookie(cookie, "__tea__ug__uid") },
                cdid = obj.stringOrEmpty("cdid").ifEmpty { extractCookie(cookie, "cdid") },
                deviceId = obj.stringOrEmpty("deviceId"),
                botId = obj.stringOrEmpty("botId").ifEmpty { DEFAULT_BOT_ID },
                conversationId = obj.stringOrEmpty("conversationId"),
            )
        }

        private fun extractCookie(cookie: String, name: String): String {
            for (part in cookie.split(';')) {
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                if (trimmed.substring(0, eq).trim() == name) return trimmed.substring(eq + 1).trim()
            }
            return ""
        }

        private fun JsonObject.stringOrEmpty(key: String): String =
            get(key)?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
    }
}
