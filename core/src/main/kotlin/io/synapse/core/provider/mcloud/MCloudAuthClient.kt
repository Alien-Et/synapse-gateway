package io.synapse.core.provider.mcloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.util.objOrNull
import io.synapse.core.util.str

/**
 * 会员中心的登录客户端：滑动拼图 → 短信验证码 → 登录换 Basic 凭证。
 *
 * 三步都是同一个域名下的 m4c/login 接口，且 account/passwd 一律 RSA 加密。
 * 上游把「验证码不正确」放在 `third.thirdDesc` 里返回（HTTP 200 + resultCode 501），
 * 所以每次调用都要过 [MCloudErrors.requireData] 判业务码，不能只看 HTTP 状态。
 */
class MCloudAuthClient(
    private val vipBase: String = MCloudConstants.VIP_BASE,
    private val sourceId: String = MCloudConstants.SOURCE_ID,
) {

    /**
     * 拉一张滑动拼图。
     *
     * 返回 [SlidePuzzle]；上游没给图片时抛错。**这个接口本身不校验滑动轨迹**——
     * 官方 WebView 是纯前端比对，客户端把两张图（滑块图与背景图）拼出来给用户拖，
     * 拖对了才允许点「获取验证码」。
     *
     * 所以网关这条链路并不需要真的算出缺口位置：只要拿到图、让用户拖完，
     * 再调 [sendSmsCode] 即可，上游不会在这里二次校验——
     * 真正的人机校验发生在短信下发频率与登录频率的限流上。
     */
    fun fetchSlidePuzzle(phone: String): SlidePuzzle {
        val body = JsonObject().apply {
            addProperty("account", MCloudCrypto.encryptAesIv(phone, MCloudConstants.KEY_PHONE))
        }
        val (status, resp) = MCloudHttp.postJson(
            url = vipBase + MCloudConstants.PATH_SLIDE_PUZZLE,
            body = body.toString(),
            headers = MCloudHttp.vipHeaders(),
        )
        val data = MCloudErrors.requireData(status, resp, "获取验证码图片")
        val puzzle = data.str("puzzle")
        val picture = data.str("picture")
        if (puzzle.isEmpty() || picture.isEmpty()) {
            throw MCloudApiException(status, MCloudErrorKind.CLIENT, resp, "NO_PUZZLE")
        }
        return SlidePuzzle(
            sliderImage = puzzle,
            backgroundImage = picture,
            sliderWidth = data.str("puzzleWidth").toIntOrNull() ?: 96,
        )
    }

    /**
     * 发短信验证码。
     *
     * 返回上游的 msgid（登录时回传，用于把验证码和这次请求绑定）。
     * 上游字段名在不同版本里不一致，这里按几种可能取值，取不到就返回空串——
     * 空 msgid 在实测里也能登录成功，不该因为少个字段就拦住用户。
     */
    fun sendSmsCode(phone: String): String {
        // 实测（算法助手 Hook）：手机号用 AES-128 密钥单独加密，不是 RSA。
        // 早期按网页端推断用 RSA，上游回一句「服务器内部错误」，白查很久。
        val body = JsonObject().apply {
            addProperty("account", MCloudCrypto.encryptAesIv(phone, MCloudConstants.KEY_PHONE))
            addProperty("sourceid", sourceId)
        }
        val (status, resp) = MCloudHttp.postJson(
            url = vipBase + MCloudConstants.PATH_DYNC_PASSWD,
            body = body.toString(),
            headers = MCloudHttp.vipHeaders(),
        )
        val data = MCloudErrors.requireData(status, resp, "发送验证码")
        return data.str("msgid").ifEmpty { data.str("msgId") }.ifEmpty { data.str("transactionId") }
    }

    /**
     * 手机号 + 短信验证码登录，返回 Basic 凭证（`Authorization: Basic` 后面那串）。
     *
     * 上游把凭证放在响应头 `Set-Cookie`/`mc_at` 里，也可能直接在 body 的 `data.token`。
     * 这里两种都取：body 优先，取不到再从 `data.basic` / `data.authorization` 找。
     *
     * 拿到的不是「Token」而是完整的 Basic Base64，后续 `apikey/get` 直接原样放进头里用。
     */
    fun loginBySmsCode(phone: String, code: String): LoginCredential {
        val body = JsonObject().apply {
            // 业务数据用 AES-192（KEY_BIZ），和手机号那把（AES-128）不是同一个
            addProperty("account", MCloudCrypto.encryptAesIv(phone, MCloudConstants.KEY_BIZ))
            addProperty("passwd", MCloudCrypto.encryptAesIv(code, MCloudConstants.KEY_BIZ))
            addProperty("sourceid", sourceId)
        }
        val (status, resp) = MCloudHttp.postJson(
            url = vipBase + MCloudConstants.PATH_COMMON_LOGIN,
            body = body.toString(),
            headers = MCloudHttp.vipHeaders(),
        )
        val data = MCloudErrors.requireData(status, resp, "登录")

        val credential = firstNonEmpty(
            data.str("token"),
            data.str("basic"),
            data.str("authorization"),
        )
        if (credential.isEmpty()) {
            // 没有凭证就没法往下走，直接把上游原文带上便于排查（不进用户界面）
            throw MCloudApiException(status, MCloudErrorKind.CLIENT, resp, "NO_CREDENTIAL")
        }
        return LoginCredential(
            basic = normalizeBasic(credential),
            userId = data.str("userId").ifEmpty { data.str("userid") },
            nickname = data.str("nickName").ifEmpty { data.str("nickname") },
        )
    }

    /** 把各种形态的凭证统一成「Basic 后面那串 Base64」。 */
    private fun normalizeBasic(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("Basic ", ignoreCase = true)) return trimmed.substring(6).trim()
        // 上游有时直接给 `mobile:手机号:token` 明文，补一次 Base64
        return if (trimmed.contains(":")) {
            java.util.Base64.getEncoder().encodeToString(trimmed.toByteArray(Charsets.UTF_8))
        } else {
            trimmed
        }
    }

    private fun firstNonEmpty(vararg values: String): String = values.firstOrNull { it.isNotEmpty() }.orEmpty()
}

// 一张滑动拼图：sliderImage 与 backgroundImage 都是 Base64（不含 data: 前缀）。
data class SlidePuzzle(
    val sliderImage: String,
    val backgroundImage: String,
    val sliderWidth: Int,
)

/** 登录成功后的凭证。 */
data class LoginCredential(
    /** `Authorization: Basic` 后面的 Base64。 */
    val basic: String,
    val userId: String = "",
    val nickname: String = "",
)
