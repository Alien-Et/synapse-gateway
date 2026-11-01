package io.synapse.core.provider.mcloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.util.objOrNull
import io.synapse.core.util.str
import java.util.Base64

/**
 * 人机校验（阿里云验证码 3.0）的凭证模型。
 *
 * ## 逆出来的真实流程（mCloud 13.2.5，实测跑通）
 *
 * ```
 * 1. App  POST /user/sms/getSmsCode（AES-192 加密，无凭证）
 *    <- {"success":false,"code":"200059554","message":"请先获取滑块拼图验证码","data":{"captchaType":2}}
 *
 * 2. App  打开 WebView：yun.139.com/fe/captcha/?data=base64({captchaType,params,headers})
 *    页面里跑阿里云验证码 SDK（prefix=7elm7k），拉图：
 *      static-captcha.aliyuncs.com/qst/PUZZLE/online/903/<uuid>/back.png    背景图
 *      static-captcha.aliyuncs.com/qst/PUZZLE/online/903/<uuid>/shadow.png  滑块阴影
 *    用户拖动 -> 页面 POST 7elm7k-verify.captcha-open.aliyuncs.com（Action=VerifyCaptchaV3）
 *    <- {"Result":{"securityToken":"...","VerifyCode":"T001","VerifyResult":true,"certifyId":"..."}}
 *
 * 3. 页面自己再发一次 getSmsCode，这次带 puzzleVerfycode（AES-256，H5 专用密钥）
 *    puzzleVerfycode = base64({"certifyId","sceneId","isSign","securityToken"})
 * ```
 *
 * ## 关键点
 *
 * - 滑块不是 vip.yun.139.com/m4c/getSlidePuzzleCode（那条路是 RSA，且不是这套流程）
 * - 验证结果不经过 App 回调：LoginActivity.jumpVerifyWeb 只 startActivity 打开页面，
 *   没有任何 onActivityResult 分支——页面验证完自己把码发出去
 * - 发码请求里唯一的凭证字段就是 FIELD_PUZZLE_TOKEN，其余字段和普通发码一致
 * - H5 密钥与 App 原生密钥不同：前者 AES-256，后者 AES-192，信封格式一致
 */
object MCloudCaptcha {

    /**
     * 校验一次发码响应是否在要求人机校验。
     *
     * 两种码都要认：
     * - CODE_NEED_CAPTCHA  200059554 还没验证过
     * - CODE_CAPTCHA_FAILED 200059553 验证过但 token 过期/复用（F019）
     */
    fun detectChallenge(raw: JsonObject): Challenge? {
        val code = raw.str("code")
        val message = raw.str("message")
        return when (code) {
            MCloudConstants.CODE_NEED_CAPTCHA ->
                Challenge(kind = ChallengeKind.NEED_CAPTCHA, captchaType = raw.captchaType(), message = message)
            MCloudConstants.CODE_CAPTCHA_FAILED ->
                Challenge(kind = ChallengeKind.TOKEN_REJECTED, captchaType = raw.captchaType(), message = message)
            else -> null
        }
    }

    private fun JsonObject.captchaType(): Int {
        val data = objOrNull("data") ?: return 2
        val n = data.get("captchaType")
        return if (n != null && n.isJsonPrimitive) runCatching { n.asInt }.getOrDefault(2) else 2
    }

    /**
     * 校验页 URL（App 里 LoginActivity.jumpVerifyWeb 拼的那个）。
     *
     * 打开它就能让用户手动过滑块，和官方客户端完全一致。
     * device.info 会作为 x-yun-client-info 原样带给页面。
     */
    fun buildVerifyUrl(
        phone: String,
        device: MCloudDevice,
        captchaType: Int = 2,
        nationCode: String = "+${MCloudConstants.DEFAULT_CCODE}",
    ): String {
        val params = JsonObject().apply {
            addProperty("clientType", MCloudConstants.CLIENT_TYPE)
            addProperty("nationCode", nationCode)
            addProperty("phoneNumber", phone)
            addProperty("reqType", "3")
            addProperty("additionalSpaceSelected", "0")
        }
        val headers = JsonObject().apply {
            addProperty("x-yun-app-channel", "10000023")
            // 页面要求纯 ASCII：非可打印字符要剔掉（App 里就是这么干的）
            addProperty("x-yun-client-info", device.info.replace(Regex("[^\\x20-\\x7E]"), ""))
        }
        val data = JsonObject().apply {
            addProperty("captchaType", captchaType)
            add("params", params)
            add("headers", headers)
        }
        val encoded = Base64.getEncoder().encodeToString(data.toString().toByteArray(Charsets.UTF_8))
        return MCloudConstants.URL_CAPTCHA_PAGE + "?data=" + encodeStrict(encoded)
    }

    /**
     * 解析 puzzleVerfycode。
     *
     * 它就是一个 base64 过的 JSON：{certifyId, sceneId, isSign, securityToken}。
     * 网关如果要自己做过滑块，就得负责产出这个结构；只是转发的话原样透传即可。
     */
    fun parsePuzzleToken(token: String): PuzzleVerify? {
        val json = runCatching {
            val normalized = token.trim().replace('-', '+').replace('_', '/')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull() ?: return null

        val obj = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: return null
        return PuzzleVerify(
            certifyId = obj.str("certifyId"),
            sceneId = obj.str("sceneId"),
            securityToken = obj.str("securityToken"),
            isSign = runCatching { obj.get("isSign")?.asBoolean ?: false }.getOrDefault(false),
            raw = obj,
        )
    }

    /** 用 PuzzleVerify 重新拼出一个 puzzleVerfycode。 */
    fun buildPuzzleToken(verify: PuzzleVerify): String {
        val obj = JsonObject().apply {
            addProperty("certifyId", verify.certifyId)
            addProperty("sceneId", verify.sceneId)
            addProperty("isSign", verify.isSign)
            addProperty("securityToken", verify.securityToken)
        }
        return Base64.getEncoder().encodeToString(obj.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * 严格 URL 编码。
     *
     * 只保留 RFC 3986 的 unreserved 字符。Base64 里的 + 必须编成 %2B——
     * 不编的话到服务端会被当成空格，data 就解不出来（表现为页面白屏）。
     */
    private fun encodeStrict(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '_' || ch == '.' || ch == '~') {
                sb.append(ch)
            } else {
                sb.append('%').append("%02X".format(c))
            }
        }
        return sb.toString()
    }
}

/** 服务端要求过滑块。 */
data class Challenge(
    val kind: ChallengeKind,
    /** 2 = 滑块拼图。 */
    val captchaType: Int,
    val message: String,
)

enum class ChallengeKind {
    /** 200059554：这个设备/号码还没过验证。 */
    NEED_CAPTCHA,

    /** 200059553：过了但 token 过期或已用过（阿里云错误码多为 F019）。 */
    TOKEN_REJECTED,
}

/** 解析后的 puzzleVerfycode。 */
data class PuzzleVerify(
    val certifyId: String,
    val sceneId: String,
    val securityToken: String,
    val isSign: Boolean,
    val raw: JsonObject,
)
