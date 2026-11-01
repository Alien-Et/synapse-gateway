package io.synapse.core.provider.mcloud

/**
 * 中国移动云盘（139 云 / MCloud）AI 接口常量。
 *
 * 来源：Android 版「中国移动云盘」13.2.5 的 WebView 页面（`vip.yun.139.com`）
 * 与其加载的 `app.js` / 打包内联公钥，接口路径与参数名均按线上真实流量核对。
 *
 * ## 链路
 * 1. `POST /m4c/login/getSlidePuzzleCode` —— 手机号（RSA 加密）换滑动拼图，人机校验。
 * 2. `POST /m4c/login/commonLogin` —— 手机号 + 短信验证码（都 RSA 加密）换 SSO，拿 `Authorization: Basic`。
 * 3. `POST /api/llm/apikey/get` —— 用上一步的 Basic 头换长期 `apikey` 与 `modelUrl`。
 * 4. `POST <modelUrl>` —— 标准 OpenAI 兼容 chat/completions，`Bearer <apikey>`。
 *
 * 第 1、2 步的 `account` / `passwd` 字段是 RSA-2048 + PKCS#1 v1.5 加密后的 Base64，
 * 明文提交会被上游判「参数解密失败」。两个公钥分别用于不同接口（见 PUBLIC_KEY_ACCOUNT
 * 与 PUBLIC_KEY_SLIDE），不能混用——这是 App 打包里两个独立常量，实测混用会解密失败。
 */
object MCloudConstants {

    /** 会员中心（登录 + apikey 下发）。 */
    const val VIP_BASE = "https://vip.yun.139.com"

    /** AI 服务（对话，域名来自 `modelUrl`，这里只作为兜底拼接）。 */
    const val AI_BASE = "https://ai.yun.139.com"

    /** 手机号登录用的渠道来源 ID（`sourceid`），取自官方 WebView 的链接参数。 */
    const val SOURCE_ID = "503016"

    /** App 端登录接口（user-njs.yun.139.com 的 user 路径下）。 */
    const val USER_BASE = "https://user-njs.yun.139.com"

    /** 编排网关（加密公钥下发）。 */
    const val ORCHES_BASE = "https://orches.yun.139.com"

    /** 取加密公钥的接口路径。 */
    const val PATH_PUBLIC_KEY = "/adaptor/encrypt/publicKey"

    /** 取公钥时要带的 appId，登录链路用的是 10000000003。 */
    const val ENCRYPT_APP_ID = "10000000003"

    /** 客户端类型，登录接口必填（实测 414）。 */
    const val CLIENT_TYPE = "414"

    /** App 渠道号，参与风控校验。 */
    const val APP_CHANNEL = "10000023"

    /** App 端发短信验证码。 */
    const val PATH_SEND_SMS = "/user/sms/getSmsCode"

    /** App 端验证码登录（三方登录通道）。 */
    const val PATH_THIRD_LOGIN = "/user/thirdlogin"

    /** `apikey/get` 需要的渠道 ID，决定下发哪一套模型与计费。 */
    const val CHANNEL_ID = "102001"

    /** 发码/登录用的 `x-yun-api-version`。 */
    const val API_VERSION = "v1"

    // ------------------------------------------------------------------ 接口路径

    /** 滑动拼图（人机校验），入参 `{account}` 均为 RSA 密文。 */
    const val PATH_SLIDE_PUZZLE = "/m4c/login/getSlidePuzzleCode"

    /** 手机号登录（短信码 / 密码共用），入参 `{account, passwd, sourceid}`。 */
    const val PATH_COMMON_LOGIN = "/m4c/login/commonLogin"

    /** 短信验证码发送（`getDyncPasswd` 即「获取动态口令」），入参 `{account, ...}`。 */
    const val PATH_DYNC_PASSWD = "/m4c/login/getDyncPasswd"

    /** 用 Basic 凭证换长期 apikey + modelUrl + 模型清单。 */
    const val PATH_APIKEY_GET = "/api/llm/apikey/get"

    /** 对话（拼在 modelUrl 上，兜底用）。 */
    const val PATH_CHAT_COMPLETIONS = "/v1/chat/completions"

    // ------------------------------------------------------------------ 公钥

    /**
     * 登录接口（`commonLogin` / `getDyncPasswd` / `apikey/get`）的 RSA 公钥。
     *
     * 对应打包产物里的常量 `o`，用于加密 account / passwd。
     */
    const val PUBLIC_KEY_ACCOUNT =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArjui87zkFWcXPa0hOFBym9odmC/" +
            "hK6gQ3kPZqrLRbQPTCc5JAGUdG57Pt8kwp18KVESnI2kz7X59mG+j6+PUASTqFQwt2qA1LSNdPBJtYEPflsBNytyK+0EVCqG" +
            "YFbmyG+1lQwElYN1mp3olIEEmiPmNPS10ohTZ53WbHHhJMbS6K3SXyWUZBLcFSyC5f68nR8bovJWOJ92JI+Um/Vhgn5/HA1S" +
            "m24Cw/LY2kzycKhDOeVOA6+bpYu2IxnxwUBKuUwv+MA/RrbBHkPqKFON5Q4KxnfcB/4idboTUbYtEI74KfbGcAE7G2N3vxUR" +
            "ihZKJZlZJ98xTzXHPQA/54UucFQIDAQAB"

    /**
     * 滑动拼图接口（`getSlidePuzzleCode`）的 RSA 公钥。
     *
     * 对应打包产物里的常量 `y`。**不能和 [PUBLIC_KEY_ACCOUNT] 混用**：
     * 用错公钥上游同样回「参数解密失败」。
     */
    const val PUBLIC_KEY_SLIDE =
        "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA9mE9AcdfhG8fdpOAgM4TvUNrKiMmq6wPWZG27wqFNM8pXR9DRXr1Z" +
            "eHUCuzpVURrAioldMFGG5cfYIsLXSPdEM+Q8JdujF7ud+nqhfun1eFL3HIPzu6ESvqLZOO7bZ92z+JL5VjeUeZS6HvV5iyvQ" +
            "GfwpxplpjsyexxjW+Nt7BhHGIdWINRnUrENQADVYoy8EXcSi8szVubqM9WRBb6h6Wwj1qWhgwvi7F90XkWkmh1EDs96aPLa2" +
            "8dWKA/mIyW6asdIjHyEK6ZMUbs/9wl8tWMFqUaZGzDd07M3fhJovhrFuL2R5qktmXQcaSYMc7uCVNHVmGLF1hT1Rw1Al4e9" +
            "8wIDAQAB"

    // ------------------------------------------------------------------ AES 密钥（Hook 实测）

    /**
     * 会员中心业务数据的 AES 密钥（24 字符 → **AES-192**）。
     *
     * 来源：`GlobalConstants.getApiKey(true)` → `D.ss(1)` → native `libdd.so`。
     * 用算法助手 Hook 到运行时的真实值（静态分析只能拿到 `libdd.so` 里的
     * `aesKey` / `rlAesKey`，那两条是另一套，解不开业务数据）。
     *
     * 用途：`AESUtil.encryptIV` / `decrypt`，即
     * `Base64(IV(16) || AES-CBC-192(plainttext))`。
     */
    const val KEY_BIZ = "2olBaQGYnEKoStYomsd1n7ax"

    /**
     * 加密手机号专用密钥（16 字符 → AES-128）。
     *
     * 实测同一个请求里手机号和业务 JSON 用的是**两把不同的密钥**，
     * 混用会得到一段能解开但全是乱码的结果。
     */
    const val KEY_PHONE = "nKdf317moJiFTrNq"

    /** 行为日志上报的密钥（16 字符）。 */
    const val KEY_LOG = "jCCHeqNSbI2xrb5O"

    /**
     * H5 人机校验页（`yun.139.com/fe/captcha/`）用的密钥，**32 字符 → AES-256**。
     *
     * 实测来源：`captcha.js` 里的 `gp = Te.enc.Utf8.parse("h7DLzCTAjn+altjy2a9E9FSt7AC6pmx1")`。
     *
     * ⚠️ 这把和 App 原生的 [KEY_BIZ] **不是同一把**：
     * - 原生发码走 `2olBaQGYnEKoStYomsd1n7ax`（AES-192）
     * - H5 页发码走 `h7DLzCTAjn+altjy2a9E9FSt7AC6pmx1`（AES-256）
     *
     * 两者都是 `Base64(IV(16) || AES-CBC(明文))` 的同一种信封，只有密钥长度不同。
     */
    const val KEY_H5_CAPTCHA = "h7DLzCTAjn+altjy2a9E9FSt7AC6pmx1"

    /**
     * **thirdlogin（短信登录换凭证）专用密钥**，16 字符 → AES-128-ECB。
     *
     * 来源：`libdd.so` 数据段全局变量 `rlAesKey`（算法助手 v7 脚本从
     * `base+0x3bb5a0` 指针 dump；同一位置附近的 `aesKey`=`QWrZexZTTawO8+I^`
     * 是 `D.aesEncode`/`encryArray` 的默认钥，thirdlogin 不走那条路）。
     *
     * 已用 Hook 到的 thirdlogin 已知明密文对**逐块验证**：
     * 前 11 块 = `AES-128-ECB(本钥, payload[0:176])`，
     * 尾块 = `AES-128-ECB(本钥, payload[176:186] + PKCS7(06×6))`，全部吻合。
     */
    const val KEY_RL_AES = "gB^gGdQHX1l+XteY"

    // ------------------------------------------------------------------ 人机校验（阿里云验证码 3.0）

    /**
     * 人机校验页地址（`LoginActivity.jumpVerifyWeb` 里硬编码）。
     *
     * 入参走 `?data=base64({captchaType, params, headers})`，**不是**一个独立 API。
     */
    const val URL_CAPTCHA_PAGE = "https://yun.139.com/fe/captcha/"

    /** 阿里云验证码的调用方前缀（`Fp` 表里的 `prefix`）。 */
    const val ALIYUN_CAPTCHA_PREFIX = "7elm7k"

    /**
     * `captchaType` → 阿里云 `SceneId` 映射（`captcha.js` 的 `Fp` 表）：
     * - `2`：滑块拼图
     * - `3`：另一种形态
     */
    const val ALIYUN_SCENE_SLIDE = "1rmw421p"
    const val ALIYUN_SCENE_ALT = "kcx16g7r"

    /** 发码请求里承载人机校验凭证的字段名。 */
    const val FIELD_PUZZLE_TOKEN = "puzzleVerfycode"

    /** 服务端说「先过滑块」的返回码。 */
    const val CODE_NEED_CAPTCHA = "200059554"

    /** 服务端说「滑块校验没过」的返回码（常见 `F019` = token 过期/已用过）。 */
    const val CODE_CAPTCHA_FAILED = "200059553"

    // ------------------------------------------------------------------ 登录字段常量

    /**
     * `NewPlatformLoginInput.pintype` 取值（smali 里的 `PIN_TYPE_*` 常量）。
     *
     * `8` = 内地短信；`23` = 香港（`+852`）；`19` = SIM 卡；`9` = 账号；`20` = CMIC。
     */
    const val PIN_TYPE_SMS = "8"
    const val PIN_TYPE_SIM = "19"
    const val PIN_TYPE_ACCOUNT = "9"
    const val PIN_TYPE_CMIC = "20"
    const val PIN_TYPE_SMS_HK = "23"

    /**
     * `NewPlatformLoginInput.version`：**实测（Hook App 真机请求）值是 versionCode 数字**
     * `30132508`（= 13.2.5 的 versionCode），不是自研网关的 30003。
     * 字段对齐错误会让上游回 `9103 输入参数不合法`。
     */
    const val VERSION_CODE = "30132508"

    /**
     * `NewPlatformLoginInput.cpid`：**实测值是 `"58"`**（Hook 真机 thirdlogin 请求），
     * 不是 `"mcloud"`。
     */
    const val CPID = "58"

    /** `NewPlatformLoginInput.srvInfoVer`：取自 `McsConfig.get("conf_server_ver")`。 */
    const val SRV_INFO_VER = ""

    /** `NewPlatformLoginInput.loginMode`：短信登录固定 `1`（smali 里 `const-string "1"`）。 */
    const val LOGIN_MODE_SMS = "1"

    // ------------------------------------------------------------------ 上游返回码

    /** 会员中心的成功码（`resultCode`）。 */
    const val CODE_OK = "0"

    /**
     * AI 服务的成功码（`code`），**四位的 `0000`**——和会员中心的 `"0"` 不是同一个值。
     * 上游两套接口各自实现，混用会把成功判成失败。
     */
    const val CODE_OK_AI = "0000"

    /** apikey 缺失（鉴权头没带上）。 */
    const val CODE_APIKEY_MISSING = "01000001"

    /** apikey 无效/已作废。 */
    const val CODE_APIKEY_INVALID = "01000002"

    /** 请求参数错误，最常见的原因是 RSA 公钥用错、或字段名写错。 */
    const val CODE_BAD_REQUEST = "500400"

    /** 会话/授权失效。 */
    const val CODE_UNAUTHORIZED = "401"

    /** 国家码默认值（中国大陆）。 */
    const val DEFAULT_CCODE = "86"

    /** 客户端标识：官方 WebView 会把 App 版本报上来，上游据此下发不同的套餐。 */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/158.0.8127.2 Mobile Safari/537.36 MCloudApp/13.2.5 AppLanguage/zh-CN"

    /** 请求来源标识，缺了会被网关层拦掉。 */
    const val REQUESTED_WITH = "com.chinamobile.mcloud"

    // ------------------------------------------------------------------ 额度（抓包实测）

    /** 权益查询域名（`ypqy.mcloud.139.com`，走 isbo2/openApi）。 */
    const val BENEFIT_BASE = "https://ypqy.mcloud.139.com"

    /**
     * 查可用权益：返回该账号的 AI tokens 总量与已用量。
     *
     * 实测响应（2026-10-31 抓包）：
     * ```
     * {"userAvailableBenefitList":[{"benefitNo":"RHR135","benefitName":"aitokens",
     *   "benefitValue":"11000000","benefitUsed":"116656","benefitStatus":"2",
     *   "reserve3":"[1000万 tokens/月]Token"}]}
     * ```
     * `benefitValue - benefitUsed` = 剩余可用 tokens。
     */
    const val PATH_AVAILABLE_BENEFIT = "/isbo2/openApi/queryAvailableBenefitV2"

    /** 查本月累计消耗：`{"month":"2026-10","totalTokens":116656}`（明文，未加密）。 */
    const val PATH_MONTHLY_CONSUMPTION = "/isbo2/openApi/queryMonthlyTokenConsumption"

    /**
     * 查消耗明细（会员中心侧）。
     *
     * 实测响应：
     * ```
     * {"data":{"aiTokenConsumptionRecordList":[{"action":"1","benefitNum":16656,
     *   "modelName":"Qwen3.5-Plus","coefficient":"0.6","recordTime":"2026-10-31 18:11:23",
     *   "comments":"云盘MClaw"}, ...]}}
     * ```
     * `action`：`1`=消耗，`4`=到期清理（清掉的不算使用量）。
     */
    const val PATH_TOKEN_RECORDS = "/m4c/benefit/getAiTokenConsumptionRecords"

    /** AI tokens 权益编号（实测固定 `RHR135`）。 */
    const val BENEFIT_NO_AI_TOKENS = "RHR135"

    /**
     * 会员身份查询。**注意是 openapi 版本**：`/m4c/openapi/userIdentity` 才接受
     * `Authorization: Basic`；`/m4c/userInfo/userIdentity` 是给会员中心 H5 页面用的，
     * 靠 Cookie 认证、body 是 `{"memberTypeList":[]}`，用 Basic 打过去拿不到数据。
     *
     * 实测响应：`{"data":[{"type":"1001","typeName":"白银会员","iconUrl":"...silver.png"}]}`
     */
    const val PATH_USER_IDENTITY = "/m4c/openapi/userIdentity"
}
