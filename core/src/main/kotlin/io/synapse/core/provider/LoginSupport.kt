package io.synapse.core.provider

/**
 * 登录能力的可选接口族。
 *
 * 五种登录流程差异过大（WebView 回调 / 设备码轮询 / 短信 / OAuth loopback / 无），
 * 不强行塞进单一接口：provider 实现自己支持的那一个，网关按类型分派。
 * 无论哪种流程，产出都是统一的 [ProviderAccount]。
 */

/** WebView 回调型（Trae）：打开 [loginUrl]，授权后回调到 [callbackUrl]。 */
data class WebLoginTicket(val id: String, val loginUrl: String, val callbackUrl: String)

/** 设备授权型（WorkBuddy / CodeBuddy）：打开 [loginUrl]，按 [state] 轮询换取凭证。 */
data class DeviceAuthTicket(val loginUrl: String, val state: String)

/** 设备授权轮询结果。 */
sealed interface DeviceAuthPoll {
    data object Pending : DeviceAuthPoll
    data class Success(val account: ProviderAccount) : DeviceAuthPoll
    data class Failed(val error: String) : DeviceAuthPoll
}

interface WebLoginSupport {
    /** [region] 为供应商自定义的区域标识（如 Trae 的 trae.cn / trae.ai），无区域概念时可忽略。 */
    fun beginWebLogin(callbackUrl: String, region: String = ""): WebLoginTicket

    /** 回调链接可能来自 WebView 拦截，也可能由用户粘贴。 */
    fun completeWebLogin(callbackUrl: String): ProviderAccount
}

interface DeviceCodeSupport {
    /** [region] 为供应商自定义的区域标识（如 WorkBuddy 的 cn/global），无区域概念时可忽略。 */
    fun startDeviceAuth(region: String = ""): DeviceAuthTicket

    fun pollDeviceAuth(state: String, region: String = ""): DeviceAuthPoll
}

interface SmsLoginSupport {
    /**
     * 发验证码，返回上游 msgid（登录时回传）。
     *
     * [ccode] 是国家码（不带 `+`，如中国大陆 `86`、香港 `852`）。
     * 上游按国家码路由短信通道，写死 86 会让非大陆号码收不到验证码。
     */
    fun sendSmsCode(phone: String, ccode: String = DEFAULT_CCODE): String

    fun loginBySmsCode(phone: String, code: String, msgid: String, ccode: String = DEFAULT_CCODE): ProviderAccount

    companion object {
        /** 默认国家码：中国大陆。 */
        const val DEFAULT_CCODE = "86"
    }
}

/**
 * 登录前需要过一道人机校验的供应商（移动云盘：滑动拼图）。
 *
 * 与 [SmsLoginSupport] 分开是因为它只在**部分**供应商存在，硬塞进短信接口会让
 * 讯飞等实现被迫写一堆空方法。界面按「provider 是否实现了这个接口」决定要不要画画板。
 *
 * 图片一律用 Base64 传（不含 `data:` 前缀），界面负责解码渲染。
 */
interface HumanCheckSupport {

    /** 拉一张新的人机校验图。[phone] 已填时一并带上，上游据此做风控关联。 */
    fun fetchHumanCheck(phone: String): HumanCheckChallenge

    /**
     * 是否需要在发短信码之前完成人机校验。
     *
     * 默认 true；个别供应商只对高频/新设备触发，那种情况下返回 false，
     * 界面就不会强制先拖滑块。
     */
    val requiresHumanCheckBeforeSms: Boolean get() = true

    /**
     * 界面确认「人机校验已通过」时回调。
     *
     * WebView 模式下必须实现：校验页往往**自己就把短信码发出去了**，
     * provider 需要记下这件事，让后续的 [SmsLoginSupport.sendSmsCode] 跳过实际发码，
     * 否则会白耗一次上游的发码次数配额。
     *
     * 自绘模式下可以不管（界面拖完滑块后仍要 provider 自己发码）。
     */
    fun onHumanCheckPassed() {}
}

/**
 * 一张人机校验图，或一个需要内嵌 WebView 打开的校验页。
 *
 * 两种形态二选一，用 [webUrl] 是否为空区分：
 * - **自绘模式**（[sliderImage] / [backgroundImage] 有值）：上游给了两张 base64 图，
 *   界面自己画滑块。适合「上游自己实现校验」的供应商。
 * - **WebView 模式**（[webUrl] 有值）：校验由第三方 SDK 在网页里闭环完成，
 *   界面只需内嵌 WebView 打开它。
 *
 * ⚠️ 移动云盘属于**第二种**。它的滑块是阿里云验证码 3.0，跑在
 * `yun.139.com/fe/captcha/` 这个 H5 页面里，带设备指纹与阿里云侧 HMAC 签名，
 * 纯 HTTP 复刻成本极高。而且页面验证通过后会**自己把短信码发出去**
 * （实测响应 `{"success":true,"code":"0000","data":{"remainGetTimes":6}}`），
 * 所以界面拿到成功信号后**不要再自己发一次码**。
 */
data class HumanCheckChallenge(
    /** 滑块小图（Base64）。WebView 模式下为空。 */
    val sliderImage: String = "",
    /** 背景大图（Base64）。WebView 模式下为空。 */
    val backgroundImage: String = "",
    /** 滑块图案的原始宽度（px），界面据此等比缩放。 */
    val sliderWidth: Int = 96,
    /**
     * 需要在内嵌 WebView 里打开的校验页地址。非空即走 WebView 模式。
     *
     * 界面应当拦截该页发出的业务请求，读到成功响应后关闭 WebView。
     */
    val webUrl: String = "",
    /**
     * WebView 模式下需要监听的成功判据：请求 URL 含这个子串、且响应体解出
     * `"success":true` 时，视为校验通过。
     *
     * 留空表示界面自行判断（不推荐，容易把失败当成功）。
     */
    val successUrlHint: String = "",
    /**
     * WebView 应当使用的 User-Agent。
     *
     * 校验页会按 UA 判断宿主环境，留空则用系统默认 UA（可能被页面拒绝渲染）。
     * 移动云盘要求伪装成官方 App 的 WebView，见 `MCloudDevice.webViewUserAgent`。
     */
    val userAgent: String = "",
)

interface LoopbackOAuthSupport {
    fun buildAuthUrl(): String

    /** 用回调 URL 里的 authorization code 换凭证。 */
    fun exchangeCode(code: String): ProviderAccount

    /**
     * 本地回调监听的端口。
     *
     * 默认沿用 Antigravity 的注册值（历史行为）。redirect_uri 是上游注册的常量，
     * 需要自己回调地址的供应商（如 ChatGPT 的 `localhost:1455/auth/callback`）必须覆盖，
     * 否则网关监听的端口对不上，回调永远收不到。
     */
    val callbackPort: Int get() = 51121

    /** 本地回调监听的路径；理由同 [callbackPort]。 */
    val callbackPath: String get() = "/oauth-callback"

    /**
     * 用完整回调 URL 完成登录；默认实现只取 code。
     *
     * 需要校验 state / 一次性消费 code 的供应商（如 ChatGPT）覆盖此方法拿完整 URL——
     * 否则同一个回调链接可以被反复重放。
     */
    fun completeCallback(callbackUrl: String): ProviderAccount =
        exchangeCode(queryParam(callbackUrl, "code") ?: throw IllegalStateException("回调里没有 code 参数"))
}
