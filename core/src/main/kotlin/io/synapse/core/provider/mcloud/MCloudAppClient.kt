package io.synapse.core.provider.mcloud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.synapse.core.util.objOrNull
import io.synapse.core.util.str

/**
 * App 端登录接口（user-njs.yun.139.com 的 user 路径下）。
 *
 * ## 与会员中心那套的区别
 * 会员中心（vip.yun.139.com 的 m4c 路径下）用 **RSA** 加密参数，但它的发码接口
 * `getDyncPasswd` 实测恒返回 `500 服务器内部错误`（上游已弃用）。
 * 真正在用的是 App 这条。
 *
 * ## 加密真相（2026-11-01 用算法助手 Hook App 真机请求实测确认）
 * - **普通接口**（getSmsCode/logout/refreshToken/queryRights 等）：
 *   `CommonEncryptDecryInterceptor.encryptIntercept(chain, key)` 内部就是
 *   `AESUtil.encryptIV(KEY_BIZ, body)` = `Base64(IV(16) ‖ AES-192-CBC(KEY_BIZ, json))`。
 *   **没有国密**，`GuoMiHelper`/SM2/SM4 只用于行为埋点上报通道。
 * - **thirdlogin 是唯一例外**：body 是 **hex**，算法是
 *   `AES-128-ECB(KEY_RL_AES) + PKCS7`，密文做「尾部 r 字节（=填充长-1）旋转到头部」，
 *   见 [MCloudCrypto.encryptThirdLogin]；响应 `data` 内层同构，用
 *   [MCloudCrypto.decryptThirdLoginInner] 解。
 *
 * ## 完整时序（逆向自 mCloud 13.2.5）
 * ```
 * 1. POST /user/sms/getSmsCode   发验证码（普通信封；未过滑块回 200059554）
 * 2. POST /user/thirdlogin       验证码换凭证（thirdlogin 专用信封）
 * 3. POST /api/llm/apikey/get    换长期 apikey（Basic 认证）
 * ```
 *
 * ## 响应
 * 外层是普通信封密文；解密失败不能当成功处理 ——
 * 那会把「参数错误」当成正常数据往上传。
 */
class MCloudAppClient(
    private val userBase: String = MCloudConstants.USER_BASE,
    private val orchesBase: String = MCloudConstants.ORCHES_BASE,
    private val appId: String = MCloudConstants.ENCRYPT_APP_ID,
    verbose: ((String, String) -> Unit)? = null,
) {
    /**
     * 详细日志回调（tag, text）。
     *
     * 排查用：会把每个请求的真实 URL / 请求头 / 加密后的 body / 响应原文 / 解密结果都打出来。
     *
     * 用 `var + private set` 而不是构造参数，是因为 [MCloudProvider] 在 `init` 里才拿到
     * `hooks`，需要在构造后接上 `hooks.onVerbose`；而测试直接传构造参数，两种都支持。
     */
    var verbose: ((String, String) -> Unit)? = verbose

    private fun log(tag: String, text: String) = verbose?.invoke(tag, text)

    /** 缓存的 SM2 公钥，避免每次请求都拉一次。 */
    private var pubKeyCache: PublicKeyBundle? = null

    /**
     * 取加密用的 SM2 公钥。
     *
     * 这个接口**不需要登录**，返回的 `pubKey` 是 65 字节未压缩 EC 点，
     * `pubIndex`（如 `20_5`）要原样塞进 `yun-encrypt-index` 头。
     */
    fun fetchPublicKey(): PublicKeyBundle {
        pubKeyCache?.let { return it }
        val (status, body) = MCloudHttp.getJson(
            url = "$orchesBase${MCloudConstants.PATH_PUBLIC_KEY}?appId=$appId",
            headers = MCloudHttp.orchesHeaders(),
        )
        val data = MCloudErrors.requireData(status, body, "获取加密公钥")
        val pubKey = data.str("pubKey")
        if (pubKey.isEmpty()) {
            throw MCloudApiException(status, MCloudErrorKind.CLIENT, body, "NO_PUBKEY")
        }
        return PublicKeyBundle(pubKey = pubKey, pubIndex = data.str("pubIndex")).also { pubKeyCache = it }
    }

    /** 丢掉缓存的公钥（上游轮换后重新拉）。 */
    fun invalidatePublicKey() {
        pubKeyCache = null
    }

    /**
     * 发短信验证码。
     *
     * [deviceInfo] 是 `x-DeviceInfo` / `x-yun-client-info` 两头的值，格式由官方客户端定义，
     * 缺了会被风控拒绝。这里按抓到的形态生成一份（见 [MCloudDevice.info]）。
     *
     * [puzzleToken] 是人机校验凭证（`puzzleVerfycode`）。风控要求过滑块时必须带，
     * 否则上游回 [MCloudConstants.CODE_NEED_CAPTCHA]。见 [MCloudCaptcha]。
     */
    fun sendSmsCode(phone: String, device: MCloudDevice, puzzleToken: String? = null): SmsSent {
        // 字段与顺序按 App 实测逐个对齐（算法助手 Hook 到真实明文）：
        // {"clientType":"414","mode":"0","phoneNumber":"18529186660","random":"pmbvpo","reqType":"3"}
        // 少 random / clientType 都会回 code=01000001「参数错误」。
        //
        // 走 H5 校验页那条路时明文形态（同样是 Hook 到的）：
        // {"random":"...","clientType":"414","nationCode":"+86","phoneNumber":"...",
        //  "reqType":"3","additionalSpaceSelected":"0","captchaType":2,"puzzleVerfycode":"..."}
        val needCaptcha = !puzzleToken.isNullOrEmpty()
        val payload = JsonObject().apply {
            addProperty("clientType", MCloudConstants.CLIENT_TYPE)
            if (!needCaptcha) {
                // 原生 Client 的字段集合
                addProperty("mode", "0")
                addProperty("phoneNumber", phone)
                addProperty("random", randomToken(6))
                addProperty("reqType", "3")
            } else {
                // H5 校验页的字段集合（顺序与 encryptedBody 一致，便于对拍）
                addProperty("random", System.currentTimeMillis().toString() + randomToken(3))
                addProperty("nationCode", "+${MCloudConstants.DEFAULT_CCODE}")
                addProperty("phoneNumber", phone)
                addProperty("reqType", "3")
                addProperty("additionalSpaceSelected", "0")
                addProperty("captchaType", 2)
                addProperty(MCloudConstants.FIELD_PUZZLE_TOKEN, puzzleToken)
            }
        }
        val data = postPlainEncrypted(MCloudConstants.PATH_SEND_SMS, payload, device)
        return SmsSent(
            msgId = data.str("msgid").ifEmpty { data.str("msgId") },
            raw = data,
        )
    }

    /**
     * 走 H5 校验页的密钥发一次码（H5 用的是 AES-256 的 [MCloudConstants.KEY_H5_CAPTCHA]）。
     *
     * 官方 H5 页面就是这么发的，和 [sendSmsCode] 的唯一区别是请求体密钥。
     * 保留两条路是因为 H5 页面的设备指纹（`||3|7.17.7|...`）和原生 App 不同，
     * 上游对两者的风控口径可能有差异。
     */
    fun sendSmsCodeViaH5(phone: String, device: MCloudDevice, puzzleToken: String): SmsSent {
        val payload = JsonObject().apply {
            addProperty("random", System.currentTimeMillis().toString() + randomToken(3))
            addProperty("clientType", MCloudConstants.CLIENT_TYPE)
            addProperty("nationCode", "+${MCloudConstants.DEFAULT_CCODE}")
            addProperty("phoneNumber", phone)
            addProperty("reqType", "3")
            addProperty("additionalSpaceSelected", "0")
            addProperty("captchaType", 2)
            addProperty(MCloudConstants.FIELD_PUZZLE_TOKEN, puzzleToken)
        }
        val body = MCloudCrypto.encryptAesIv(payload.toString(), MCloudConstants.KEY_H5_CAPTCHA)
        val (status, raw) = MCloudHttp.postJson(
            url = userBase + MCloudConstants.PATH_SEND_SMS,
            body = body,
            headers = MCloudHttp.h5Headers(device),
        )
        val plain = runCatching {
            MCloudCrypto.decryptAesIv(raw.trim(), MCloudConstants.KEY_H5_CAPTCHA)
        }.getOrElse { raw }
        val data = MCloudErrors.requireData(status, plain, MCloudConstants.PATH_SEND_SMS)
        return SmsSent(
            msgId = data.str("msgid").ifEmpty { data.str("msgId") },
            raw = data,
        )
    }

    /**
     * 用验证码换登录凭证。
     *
     * 走 `user/thirdlogin`，输入是 `NewPlatformLoginInput`（Retrofit body，字段名见下）。
     * 这条链的字段名和 H5 那条**完全不同**，是从 smali 里读出来的，不是猜的：
     *
     * | 字段 | 值 |
     * |---|---|
     * | `msisdn` | 手机号 |
     * | `clienttype` | `414` |
     * | `dycpwd` | 动态口令 = **短信验证码**（这条链上 `verfycode` 是 null，验证码实际走这里） |
     * | `pintype` | `8`（内地）；`+852` 时是 `23` |
     * | `verfycode` | 另传一次，双保险 |
     * | `version` | versionCode 数字（如 `30003`） |
     * | `cpid` | 会员中心套餐 ID |
     * | `srvInfoVer` | `McsConfig.get("conf_server_ver")` |
     * | `loginMode` | `1` |
     * | `extInfo` | `{"additionalSpaceSelected":"0", ...}` |
     *
     * **没有 `msgid` 字段** —— 走 WebView 校验时码是页面自己发的，
     * 而它的响应里根本没有 msgid，所以这里不接受 msgId。
     *
     * `x-NationCode` 是**请求头**不是 body 字段（`IUserDomainApi.login` 的第二个参数
     * 标注了 `@Header("x-NationCode")`）。
     */
    fun loginBySmsCode(phone: String, code: String, msgId: String, device: MCloudDevice): MCloudLogin {
        val payload = buildThirdLoginPayload(phone, code)
        return requestThirdLogin(payload, device, phone)
    }

    /**
     * thirdlogin 请求体。
     *
     * 字段与顺序**逐字节对齐 Hook 到的 App 真机请求**（2026-11-01，mCloud 13.2.5）：
     * ```json
     * {"clienttype":"414","cpid":"58","dycpwd":"657407","extInfo":{"ifOpenAccount":"1",
     *  "additionalSpaceSelected":"1"},"loginMode":"1","msisdn":"185...","pintype":"8",
     *  "version":"30132508"}
     * ```
     * 外层字段是字母序（Gson `JsonObject` 保插入序，按序 add 即可）；`extInfo` 里
     * `ifOpenAccount` 在前（实测如此，非字母序）。**没有** `verfycode`/`srvInfoVer`/
     * `msgid` —— 早期实现多发了 `verfycode` 且 `cpid` 用了 `"mcloud"`，
     * 正是上游回 `9103 输入参数不合法` 的原因。
     */
    private fun buildThirdLoginPayload(phone: String, code: String): JsonObject = JsonObject().apply {
        addProperty("clienttype", MCloudConstants.CLIENT_TYPE)
        addProperty("cpid", MCloudConstants.CPID)
        addProperty("dycpwd", code)
        add("extInfo", JsonObject().apply {
            addProperty("ifOpenAccount", "1")
            addProperty("additionalSpaceSelected", "1")
        })
        addProperty("loginMode", MCloudConstants.LOGIN_MODE_SMS)
        addProperty("msisdn", phone)
        addProperty("pintype", MCloudConstants.PIN_TYPE_SMS)
        addProperty("version", MCloudConstants.VERSION_CODE)
    }

    /** thirdlogin 的 request body 信封形态。 */
    /**
     * 发一次 thirdlogin 并解回凭证。
     *
     * 请求信封（Hook 实测逆向，2026-11-01）：
     * - body = `hex(rotate(AES-128-ECB-PKCS7(KEY_RL_AES, payload)))`，见 [MCloudCrypto.encryptThirdLogin]
     * - headers：普通 appHeaders（默认已带 `x-NationCode: +86`），**不带**
     *   `Authorization`/`hcy-cool-flag`/`yun-encrypt-*`
     *
     * 响应：外层还是 `Base64(IV‖AES-192-CBC(KEY_BIZ))` 的普通信封（[decodeResponse] 解），
     * 解出来是 `{"success":true,"code":"0000",...,"data":"<hex>"}` —— `data` 是**内层
     * 密文**，要用 [MCloudCrypto.decryptThirdLoginInner] 再解一层才拿得到 token。
     */
    private fun requestThirdLogin(
        payload: JsonObject,
        device: MCloudDevice,
        phone: String,
    ): MCloudLogin {
        val plain = payload.toString()
        log("MCloud 请求", "POST $userBase${MCloudConstants.PATH_THIRD_LOGIN}")
        log("MCloud 请求明文", plain.take(2000))

        val body = MCloudCrypto.encryptThirdLogin(plain.toByteArray(Charsets.UTF_8))
        log("MCloud 加密", "body=${body.take(120)}… (${body.length} 字符)")
        val headers = MCloudHttp.appHeaders(device)

        val (status, raw) = MCloudHttp.postJson(
            url = userBase + MCloudConstants.PATH_THIRD_LOGIN,
            body = body,
            headers = headers,
        )
        log("MCloud 响应", "HTTP $status，原文 ${raw.length} 字符")
        log("MCloud 响应原文", raw.take(2000))

        val decoded = decodeResponse(status, raw, MCloudConstants.KEY_BIZ)
        log("MCloud 解密结果", decoded.take(2000))

        val data = unwrapThirdLoginData(status, decoded)
        log("MCloud 内层字段", data.keySet().joinToString(","))
        log("MCloud 内层ID", "userDomainId=${data.str("userDomainId")} userId=${data.str("userId")}")
        // Hook 实测（2026-11-01）：内层 JSON 的凭证字段叫 **authToken**（不是 token），
        // 用户 ID 叫 userDomainId（即请求头 x-yun-uni 用的那个数字）。
        // 形态：{"account":"134...","authToken":"S2qb1a8N|1|RCS|...","userDomainId":"1039...",...}
        val token = firstNonEmpty(
            data.str("authToken"), data.str("token"), data.str("basic"), data.str("authorization"),
        )
        if (token.isEmpty()) {
            throw MCloudApiException(200, MCloudErrorKind.CLIENT, data.toString(), "NO_CREDENTIAL")
        }
        return MCloudLogin(
            // App 真机实测：ai.yun.139.com 的请求头是
            // `Authorization: Basic base64("mobile:<手机号>:<authToken>")`
            // （Hook 解码确认）。normalizeBasic 见冒号会自动 base64。
            basic = normalizeBasic("mobile:$phone:$token"),
            userId = firstNonEmpty(data.str("userDomainId"), data.str("userId"), data.str("userid")),
            nickname = firstNonEmpty(data.str("nickName"), data.str("nickname"), data.str("userNickName")),
            raw = data,
        )
    }

    /**
     * thirdlogin 响应的 `data` 字段有两层：外层信封解开后 `data` 是 hex 内层密文
     * （登录链专用 AES-128-ECB + 旋转，见 [MCloudCrypto.decryptThirdLoginInner]），
     * 解不开或非 hex 时按普通对象处理；`success=false` 走 [MCloudErrors.requireData] 抛业务错。
     */
    private fun unwrapThirdLoginData(status: Int, decoded: String): JsonObject {
        val obj = runCatching { JsonParser.parseString(decoded).asJsonObject }.getOrNull()
            ?: MCloudErrors.requireData(status, decoded, MCloudConstants.PATH_THIRD_LOGIN)
        val success = obj.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
        if (!success) {
            // success 缺失或 false：交给 requireData 抛业务错（带 code/message）
            return MCloudErrors.requireData(status, decoded, MCloudConstants.PATH_THIRD_LOGIN)
        }
        val el = obj.get("data") ?: return JsonObject()
        return when {
            el.isJsonObject -> el.asJsonObject
            el.isJsonPrimitive -> {
                val hexBody = el.asString
                val inner = MCloudCrypto.decryptThirdLoginInner(hexBody)
                    ?: throw MCloudApiException(
                        200, MCloudErrorKind.CLIENT, decoded.take(500), "THIRDLOGIN_DECRYPT_FAIL",
                    )
                log("MCloud 内层解密", inner.take(2000))
                JsonParser.parseString(inner).asJsonObject
            }
            else -> JsonObject()
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 发一个**普通信封**请求并解回 `data` 对象。
     *
     * Hook 实测（2026-11-01，App 真机）：getSmsCode/logout/refreshToken 等普通接口
     * 走 `CommonEncryptDecryInterceptor` → 内部 `AESUtil.encryptIV(KEY_BIZ, body)`
     * = `Base64(IV(16) ‖ AES-192-CBC(KEY_BIZ, json))`，且 user-njs 域的请求**带
     * `hcy-cool-flag: 1`**（thirdlogin 是唯一不带它、也不走这个信封的接口）。
     * 响应同样用 KEY_BIZ 解（[decodeResponse] 的首选组合就是实测命中的那条）。
     */
    private fun postPlainEncrypted(
        path: String,
        payload: JsonObject,
        device: MCloudDevice,
    ): JsonObject {
        val plainPayload = payload.toString()
        log("MCloud 请求", "POST $userBase$path")
        log("MCloud 请求明文", plainPayload.take(2000))

        val body = MCloudCrypto.encryptAesIv(plainPayload, MCloudConstants.KEY_BIZ)
        log("MCloud 加密", "body=${body.take(120)}… (${body.length} 字符)")

        val headers = MCloudHttp.appHeaders(device, extra = mapOf("hcy-cool-flag" to "1"))
        log("MCloud 请求头", headers.entries.joinToString("\n") { "  ${it.key}: ${it.value.take(120)}" })

        val (status, raw) = MCloudHttp.postJson(
            url = userBase + path,
            body = body,
            headers = headers,
        )
        log("MCloud 响应", "HTTP $status，原文 ${raw.length} 字符")
        log("MCloud 响应原文", raw.take(2000))

        val plain = decodeResponse(status, raw, MCloudConstants.KEY_BIZ)
        log("MCloud 解密结果", plain.take(2000))

        return MCloudErrors.requireData(status, plain, path)
    }

    /**
     * 把响应体解成明文。
     *
     * 上游有三种情况，都要处理：
     * 1. 正常：SM4 密文（hex）→ 用本次密钥解
     * 2. 网关层报错：明文 JSON（`{"code":"...","message":"..."}`）
     * 3. 空 body：HTTP 非 2xx 且无内容
     */
    private fun decodeResponse(status: Int, raw: String, keyHex: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return raw
        if (trimmed.startsWith("{")) return trimmed

        val inner = trimmed.removeSurrounding("\"")
        val sessionKey = keyHex.toByteArray(Charsets.US_ASCII)

        // 响应可能的编码 × 算法 × 密钥组合（上游在 user 域接口上把 hex/base64 与 SM4/AES 混着用，
        // 而且不能 100% 确定响应是不是用同一个会话密钥加密）。全部试一遍，命中能解出 JSON 的那个；
        // 每条尝试结果都进日志，一次登录就能定位真实组合。
        // 顺序有讲究：`AES-CBC/KEY_BIZ/b64` 是实测命中的组合，放最前面省掉无效尝试
        val keyCandidates: List<Pair<String, ByteArray>> = listOf(
            "KEY_BIZ" to MCloudConstants.KEY_BIZ.toByteArray(Charsets.US_ASCII),
            "session" to sessionKey,
            "KEY_H5" to MCloudConstants.KEY_H5_CAPTCHA.toByteArray(Charsets.US_ASCII),
            "aesKey" to "QWrZexZTTawO8+I^".toByteArray(Charsets.US_ASCII),
        )

        data class Attempt(val name: String, val run: () -> String?)

        val attempts = buildList {
            for ((keyName, key) in keyCandidates) {
                fun addBinary(alg: String, hexMode: Boolean) {
                    if (hexMode && !(inner.length % 2 == 0 && inner.all { it in "0123456789abcdefABCDEF" })) return
                    if (!hexMode && inner.length % 4 != 0) return
                    add(
                        Attempt("$alg/$keyName/${if (hexMode) "hex" else "b64"}") {
                            val data = if (hexMode) {
                                org.bouncycastle.util.encoders.Hex.decode(inner)
                            } else {
                                java.util.Base64.getDecoder().decode(inner)
                            }
                            decryptWith(key, data, alg)
                        },
                    )
                }
                addBinary("AES-CBC", true); addBinary("AES-CBC", false)
                addBinary("SM4-ECB", true); addBinary("SM4-ECB", false)
                addBinary("SM4-CBC", true); addBinary("SM4-CBC", false)
                addBinary("AES-ECB", true); addBinary("AES-ECB", false)
            }
        }

        for (attempt in attempts) {
            val out = runCatching(attempt.run).getOrElse { e ->
                log("MCloud 解密尝试", "${attempt.name} 失败：${e.message?.take(60)}")
                null
            }
            if (out != null && out.trimStart().startsWith("{")) {
                log("MCloud 解密命中", attempt.name)
                return out
            }
            if (out != null) log("MCloud 解密尝试", "${attempt.name} 解出但非 JSON：${out.take(60)}")
        }
        log("MCloud 解密尝试", "全部组合均未解出 JSON，原文长度 ${inner.length}")
        return raw
    }

    /**
     * 用同一个会话密钥，按 [alg] 指定的算法解一段密文。
     *
     * `-CBC` 假定前 16 字节是 IV（上游 AES 老方案的习惯）；16 字节密钥同时兼容
     * AES-128 与 SM4（两者分组都是 16 字节），所以同一段数据要分别按两种算法试。
     */
    private fun decryptWith(key: ByteArray, data: ByteArray, alg: String): String? {
        if (data.size < 16 || data.size % 16 != 0) return null
        return when (alg) {
            "SM4-ECB" -> MCloudCrypto.sm4DecryptEcb(key, data)
            "SM4-CBC" -> MCloudCrypto.sm4DecryptCbcIvPrefix(key, data)
            "AES-ECB" -> MCloudCrypto.aesDecryptEcb(key, data)
            "AES-CBC" -> MCloudCrypto.aesDecryptCbcIvPrefix(key, data)
            else -> null
        }
    }

    /**
     * 生成随机小写字母串（`random` 字段）。
     *
     * 上游没有说明它的用途，实测每次请求都不同、长度固定 6。
     * 照抄形态即可——空着或长度不符都会让上游判「参数错误」。
     */
    private fun randomToken(len: Int): String {
        val pool = "abcdefghijklmnopqrstuvwxyz"
        val sb = StringBuilder(len)
        repeat(len) { sb.append(pool[java.security.SecureRandom().nextInt(pool.length)]) }
        return sb.toString()
    }

    /** 把各种形态的凭证统一成 `Basic` 后面那串 Base64。 */
    private fun normalizeBasic(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("Basic ", ignoreCase = true)) return t.substring(6).trim()
        return if (t.contains(":")) {
            java.util.Base64.getEncoder().encodeToString(t.toByteArray(Charsets.UTF_8))
        } else {
            t
        }
    }

    private fun firstNonEmpty(vararg values: String): String = values.firstOrNull { it.isNotEmpty() }.orEmpty()
}

/** 服务端下发的加密公钥。 */
data class PublicKeyBundle(
    /** 65 字节未压缩 EC 点（hex，`04` 开头）。 */
    val pubKey: String,
    /** 公钥索引（如 `20_5`），要放进 `yun-encrypt-index` 头。 */
    val pubIndex: String,
)

/** 发码结果。[msgId] 可能为空（部分流程不需要回传）。 */
data class SmsSent(val msgId: String, val raw: JsonObject)

/** 登录结果。[basic] 是 `Authorization: Basic` 后面那串 Base64。 */
data class MCloudLogin(
    val basic: String,
    val userId: String,
    val nickname: String,
    val raw: JsonObject,
)

/**
 * 设备指纹。
 *
 * `x-DeviceInfo` / `x-yun-client-info` 两个头共用一套 `|` 分隔的字段，
 * 上游按它做设备识别与风控。字段顺序照抄官方客户端，缺一项都可能被拦。
 */
data class MCloudDevice(
    val brand: String = "OnePlus",
    val model: String = "PJE110",
    val osVersion: String = "android 16",
    val resolution: String = "1080X2208",
    val deviceId: String,
) {
    /** 两头的实际值。 */
    val info: String
        get() = "5|127.0.0.1|1|13.2.5|$brand|$model|$deviceId|02-00-00-00-00-00|$osVersion|$resolution|zh||||019|0|"

    /**
     * H5 校验页用的设备串（实测形态）。
     *
     * 和 [info] 完全不是一套：H5 页面的 `Wb()` 生成的是
     * `||3|7.17.7|机型|机型|32位hex||Android 16|360X792||||UEpFMTEw|`。
     * 注意版本号是 `7.17.7`（SDK 版本，不是 App 版本），分辨率也换成 WebView 的 CSS 像素。
     */
    val h5Info: String
        get() = "||3|7.17.7|$model|$model|${deviceIdLower()}||Android 16|360X792||||${base64Model()}|"

    /** WebView 里页面自己的 UA（不是 okhttp 的）。 */
    val webViewUserAgent: String
        get() = "Mozilla/5.0 (Linux; $osVersion; $model Build/TP1A.220905.001; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/158.0.8127.2 Mobile Safari/537.36 " +
            "MCloudApp/13.2.5 AppLanguage/zh-CN"

    private fun deviceIdLower(): String = deviceId.lowercase()

    /** `UEpFMTEw` == base64("PJE110")，H5 侧把机型名 base64 了。 */
    private fun base64Model(): String =
        java.util.Base64.getEncoder().encodeToString(model.toByteArray(Charsets.UTF_8))

    companion object {
        /** 随机生成一个 32 位 hex 设备号（上游只要求稳定，不校验真伪）。 */
        fun random(): MCloudDevice {
            val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            return MCloudDevice(deviceId = bytes.joinToString("") { "%02X".format(it) })
        }
    }
}
