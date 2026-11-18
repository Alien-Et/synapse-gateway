package io.synapse.core.provider.doubao

/**
 * 登录链路的最小 Cookie 罐（匿名会话 + 登录态都放这里）。
 *
 * ## 为什么必须有它
 *
 * 真机 `send_code/v1` 的请求头里 **一定带 `Cookie`**（匿名会话：`odin_tt` /
 * `ttwid` / `passport_csrf_token` / `d_ticket` …，实测 235 字符）。
 * 网关此前 `post()` 不带任何 Cookie，等于「一个从没跟服务端打过交道的陌生客户端
 * 直接要验证码」——服务端只能按最保守的策略处理。
 *
 * 正确模型与真机一致：**会话是攒出来的**——
 *
 * 1. 首次请求（`GET /passport/account/info/v2/`）服务端下发 `odin_tt`；
 * 2. 发码响应再下发 `passport_csrf_token` / `passport_csrf_token_default`；
 * 3. 后续每个请求把攒到的 Cookie 原样回带。
 *
 * ## 设计取舍
 *
 * - **只存 name=value**：`Path` / `Domain` / `Max-Age` 这些属性对本场景没有意义
 *   （所有请求都打同一台 `api-normal.doubao.com`），解析它们只会引入 bug；
 * - **空值不覆盖**：`Set-Cookie: x=; Max-Age=0` 是「删除」指令，照做会把状态打坏，
 *   这里直接忽略空值；
 * - **顺序即插入顺序**：`LinkedHashMap` 保证回带顺序稳定，便于和抓包逐字对照。
 */
class DoubaoSessionCookieJar(
    initial: String = "",
    /** 落盘回调（App 侧接加密存储）；默认不落盘，纯内存。 */
    private val persist: (String) -> Unit = {},
) {

    private val map = LinkedHashMap<String, String>()

    init {
        load(initial)
    }

    /** 载入一整串 `k=v; k2=v2`。 */
    fun load(cookie: String) {
        if (cookie.isEmpty()) return
        for (part in cookie.split(';')) {
            val t = part.trim()
            val eq = t.indexOf('=')
            if (eq <= 0) continue
            val name = t.substring(0, eq).trim()
            val value = t.substring(eq + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) map[name] = value
        }
    }

    /**
     * 合并一次响应的 `Set-Cookie`。
     *
     * @return 是否有变化（调用方据此决定要不要落盘）。
     */
    fun merge(setCookie: List<String>): Boolean {
        var changed = false
        for (raw in setCookie) {
            val first = raw.split(';').firstOrNull()?.trim() ?: continue
            val eq = first.indexOf('=')
            if (eq <= 0) continue
            val name = first.substring(0, eq).trim()
            val value = first.substring(eq + 1).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            if (map[name] != value) {
                map[name] = value
                changed = true
            }
        }
        if (changed) persist(header())
        return changed
    }

    /** 回带用的 `Cookie` 头值；空罐返回空串（调用方据此省略整个头）。 */
    fun header(): String = map.entries.joinToString("; ") { (k, v) -> "$k=$v" }

    /** 当前快照（落盘/单测用）。 */
    fun snapshot(): String = header()

    fun get(name: String): String = map[name].orEmpty()

    fun isEmpty(): Boolean = map.isEmpty()

    val size: Int get() = map.size

    fun clear() {
        map.clear()
        persist("")
    }
}
