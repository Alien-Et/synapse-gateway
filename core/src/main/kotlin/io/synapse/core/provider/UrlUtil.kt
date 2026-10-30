package io.synapse.core.provider

import java.net.URLDecoder

/**
 * 从 URL 查询串里取参数（不依赖任何平台 API）。
 *
 * ⚠️ **必须做 URL 解码**：OAuth 的 authorization code 几乎一定带转义
 * （Google 的形如 `4/0AeanS0b...`，到 URL 里就是 `4%2F0AeanS0b...`），
 * 拿未解码的原文去换 token 会直接被上游回 `invalid_grant`——
 * 表现为「浏览器里明明授权成功了，应用里却提示登录失败」。
 *
 * 用 [URLDecoder] 而不是手写替换：它按 x-www-form-urlencoded 语义把 `+` 当空格、
 * 逐字节解 `%XX`，和浏览器/服务端的行为一致；解不开（畸形转义）时退回原文，
 * 绝不让一个坏参数把整个登录流程变成异常。
 */
fun queryParam(url: String, name: String): String? {
    val query = url.substringAfter('?', "")
    if (query.isEmpty()) return null
    // 去掉 #fragment，避免把 hash 混进最后一个参数的值里
    val cleaned = query.substringBefore('#')
    for (pair in cleaned.split('&')) {
        val index = pair.indexOf('=')
        if (index <= 0) continue
        if (pair.substring(0, index) != name) continue
        val raw = pair.substring(index + 1)
        return decode(raw)
    }
    return null
}

/** 单个查询参数的解码；`+` 按表单语义还原成空格，畸形转义保持原文。 */
private fun decode(value: String): String =
    runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
