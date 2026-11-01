package io.synapse.core.provider.mcloud

import io.synapse.core.util.HttpSupport
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * 移动云盘的上游 HTTP。
 *
 * 复用了 [HttpSupport]（同一套超时/大小上限），只额外把「会员中心」和「对话」两种
 * 请求头分开构造：两者要求的头差别很大，混在一起容易漏字段，
 * 而漏了 `X-Requested-With` 这类头会被前置网关直接拒掉（实测 403/空响应）。
 */
internal object MCloudHttp {

    const val CONNECT_TIMEOUT_MS = 20_000
    const val READ_TIMEOUT_MS = 120_000

    /**
     * 会员中心接口的公共头。
     *
     * 这些头都是官方 WebView 实际发的；其中 `Origin` / `Referer` 参与 CORS 校验，
     * `X-Requested-With` 参与 App 来源校验，`x-yun-api-version` 决定走哪版接口。
     */
    fun vipHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("Accept", "application/json, text/plain, */*")
        put("Content-Type", "application/json")
        put("Origin", MCloudConstants.VIP_BASE)
        put("Referer", "${MCloudConstants.VIP_BASE}/vip/")
        put("User-Agent", MCloudConstants.USER_AGENT)
        put("X-Requested-With", MCloudConstants.REQUESTED_WITH)
        put("x-yun-api-version", MCloudConstants.API_VERSION)
        put("x-yun-module-type", "100")
        put("x-yun-svc-type", "1")
        putAll(extra)
    }

    /**
     * App 端接口（`user-njs.yun.139.com`）的公共头。
     *
     * 这套头是官方客户端原样发的：`x-DeviceInfo` / `x-yun-client-info` 参与风控，
     * `x-yun-uni` 参与会话归属，`hcy-cool-flag` 决定走不走加密分支。
     * 缺任何一项都可能得到空响应或「参数错误」，所以集中在这里构造，不散落在调用点。
     */
    fun appHeaders(
        device: MCloudDevice,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> = buildMap {
        put("Content-Type", "application/json; charset=UTF-8")
        put("Accept-Language", "zh-CN")
        put("User-Agent", "okhttp/4.12.0")
        put("x-NationCode", "+${MCloudConstants.DEFAULT_CCODE}")
        put("x-NetType", "5")
        put("x-yun-net-type", "5")
        put("x-yun-app-channel", MCloudConstants.APP_CHANNEL)
        put("x-huawei-channelSrc", MCloudConstants.APP_CHANNEL)
        put("x-MM-Source", "019")
        put("x-SvcType", "1")
        // 设备指纹是**必填**：实测缺了这两个头，即使请求体加密完全正确，
        // 上游也只会回 code=01000001「参数错误」，非常难查。所以这里不给默认值。
        put("x-DeviceInfo", device.info)
        put("x-yun-client-info", device.info)
        put("x-yun-user-agent", "android|${device.model}|${device.osVersion}|mCloud13.2.5-019")
        putAll(extra)
    }

    /**
     * H5 校验页的请求头。
     *
     * 官方 H5 页面（`yun.139.com/fe/captcha/`）的 axios 拦截器是这么拼的：
     * ```
     * iC(t){ const t = Wb(); e["x-deviceinfo"]=t; e["x-yun-client-info"]=t }
     * ```
     * 也就是**小写** `x-deviceinfo`，且设备串是 WebView 形态（`||3|7.17.7|...`），
     * 和原生 App 的 `5|127.0.0.1|1|13.2.5|...` 不一样。
     */
    fun h5Headers(device: MCloudDevice): Map<String, String> = mapOf(
        "Content-Type" to "application/json;charset=UTF-8",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh,zh-CN;q=0.9",
        "Origin" to "https://yun.139.com",
        "Referer" to "https://yun.139.com/",
        "User-Agent" to device.webViewUserAgent,
        "x-requested-with" to "com.chinamobile.mcloud",
        "x-deviceinfo" to device.h5Info,
        "x-yun-client-info" to device.h5Info,
        "x-yun-app-channel" to MCloudConstants.APP_CHANNEL,
        "x-Nationcode" to "+${MCloudConstants.DEFAULT_CCODE}",
        "hcy-cool-flag" to "1",
    )

    /**
     * 取公钥这类「编排网关」接口的最小头。
     *
     * `orches.yun.139.com` 不校验设备指纹，带多了反而可能因字段格式不符被拒。
     */
    fun orchesHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "User-Agent" to MCloudConstants.USER_AGENT,
    )

    /** 普通 JSON 请求，返回 (状态码, 响应体)。网络层异常统一包成 [MCloudApiException]。 */
    fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): Pair<Int, String> {
        val conn = try {
            HttpSupport.open(url, "POST", headers, body, connectTimeoutMs, readTimeoutMs)
        } catch (e: Exception) {
            throw MCloudApiException(0, MCloudErrorKind.NETWORK, e.message.orEmpty())
        }
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } catch (e: Exception) {
            throw MCloudApiException(0, MCloudErrorKind.NETWORK, e.message.orEmpty())
        } finally {
            conn.disconnect()
        }
    }
}

/** 对话的连接包装：拿到未读的流后交给网关透传。 */
class MCloudStreamCall internal constructor(
    val status: Int,
    val errorBody: String,
    val stream: InputStream?,
    val contentType: String,
    private val connection: HttpURLConnection,
) {
    val isEventStream: Boolean get() = contentType.contains("text/event-stream", ignoreCase = true)

    fun close() {
        runCatching { stream?.close() }
        runCatching { connection.disconnect() }
    }
}

/** 打开一次对话流；非 2xx 时 [MCloudStreamCall.stream] 为 null。 */
internal fun openChatStream(
    url: String,
    headers: Map<String, String>,
    body: String,
    connectTimeoutMs: Int = MCloudHttp.CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = MCloudHttp.READ_TIMEOUT_MS,
): MCloudStreamCall {
    val conn = try {
        HttpSupport.open(url, "POST", headers, body, connectTimeoutMs, readTimeoutMs)
    } catch (e: Exception) {
        throw MCloudApiException(0, MCloudErrorKind.NETWORK, e.message.orEmpty())
    }
    val status = try {
        conn.responseCode
    } catch (e: Exception) {
        conn.disconnect()
        throw MCloudApiException(0, MCloudErrorKind.NETWORK, e.message.orEmpty())
    }
    val contentType = conn.contentType.orEmpty()
    if (status !in 200..299) {
        val errorBody = runCatching { HttpSupport.readBody(conn) }.getOrDefault("")
        conn.disconnect()
        return MCloudStreamCall(status, errorBody, null, contentType, conn)
    }
    return MCloudStreamCall(status, "", conn.inputStream, contentType, conn)
}


/** GET 请求，返回 (状态码, 响应体)。用于取加密公钥这类无 body 的接口。 */
internal fun MCloudHttp.getJson(
    url: String,
    headers: Map<String, String>,
    connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    readTimeoutMs: Int = READ_TIMEOUT_MS,
): Pair<Int, String> {
    val conn = try {
        HttpSupport.open(url, "GET", headers, null, connectTimeoutMs, readTimeoutMs)
    } catch (e: Exception) {
        throw MCloudApiException(0, MCloudErrorKind.NETWORK, e.message.orEmpty())
    }
    return try {
        conn.responseCode to HttpSupport.readBody(conn)
    } catch (e: Exception) {
        throw MCloudApiException(0, MCloudErrorKind.NETWORK, e.message.orEmpty())
    } finally {
        conn.disconnect()
    }
}
