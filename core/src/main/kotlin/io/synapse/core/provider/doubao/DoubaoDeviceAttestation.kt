package io.synapse.core.provider.doubao

import io.synapse.core.util.HttpSupport
import io.synapse.core.util.stringOrNull
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** attestation 结果的轻量持久化（challenge / REE 密钥 / 下发证书的落盘口）。 */
interface AttestationCache {
    fun get(key: String): String?
    fun put(key: String, value: String)

    /** 进程内实现：重启即失效，等价于「每次冷启动重新做一次设备证明」。 */
    class Memory : AttestationCache {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    }
}

/**
 * 设备证明链（TEE Key Attestation）—— 上游 `bdticketguard` 客户端侧的一比一复刻。
 *
 * ## 上游的引用方式（本类照做的原型）
 *
 * ```text
 * 启动期  TicketGuardManager.tryInit() → MainTicketGuardManager.loadTee()
 *         → TeeKeyHelper/TeeApi：在 AndroidKeyStore(TEE) 里生成/加载密钥对
 * 请求期  每个 passport 请求经请求管线取头：
 *         bd-ticket-guard-client-cert / -client-csr / -ree-public-key
 *         / -attest-certs / -server-cert-sn / -attestkey-ts-sign …
 * ```
 *
 * 也就是「**启动时备好设备密钥，请求时把产物作为头注入**」。本类按同样的两段式组织：
 * [prepare]（备密钥与证书）+ [asGuardHeaders]（请求时注入）。
 *
 * ## 完整链路
 *
 * ```text
 * ① 主 TEE 密钥（signingAlias）      → 公钥 = teePublicKey
 * ② REE 软密钥（普通 EC P-256）      → 公钥 = reePublicKey（软密钥，可导出/自持）
 * ③ challenge = SHA-256(teePub + "#" + reePub)
 * ④ attestation 密钥（setAttestationChallenge）→ getCertificateChain() 证书链
 * ⑤ POST get_client_cert/  用 CSR 换 client-cert（服务端签）
 * ⑥ POST verify_pub_key/   上报证书链；响应头裁决（0 = 通过）
 * ```
 *
 * ## 为什么可以复刻
 *
 * `AndroidKeyStore` 是公开 API，本项目自建密钥、自签 CSR 即可 —— 不需要上游的私钥，
 * 服务端登记的是**本机上报的**公钥。
 *
 * ## 边界（不假装已闭环）
 *
 * - 服务端是否校验证书链里的 `attestationApplicationId`（包名 + 签名摘要，系统写入、
 *   应用无法伪造）—— 只能实测；
 * - 任何一步失败都**安静降级成不带 guard 头**，与接线前的行为完全一致。
 */
class DoubaoDeviceAttestation(
    private val tee: TeeKeyStore = TeeKeyStore(),
    private val cache: AttestationCache = AttestationCache.Memory(),
    /** 主签名密钥别名（上游由调用方传入，这里给本项目的固定值）。 */
    val signingAlias: String = DEFAULT_SIGNING_ALIAS,
) {

    companion object {
        const val HOST = "security.snssdk.com"
        const val PATH_GET_CLIENT_CERT = "/passport/ticket_guard/get_client_cert/"
        const val PATH_VERIFY_PUB_KEY = "/passport/ticket_guard/verify_pub_key/"
        const val PATH_ATTEST_COMPENSATION = "/passport/ticket_guard/attest_compensation_check/"

        const val HEADER_ATTEST_CERTS = "bd-ticket-guard-attest-certs"
        const val HEADER_REE_PUBLIC_KEY = "bd-ticket-guard-ree-public-key"
        const val HEADER_CLIENT_CERT = "bd-ticket-guard-client-cert"
        const val HEADER_CLIENT_CSR = "bd-ticket-guard-client-csr"
        const val HEADER_ATTEST_RESULT = "bd-ticket-guard-verify-public-key-result"
        const val HEADER_LOG_ID = "X-Tt-Logid"

        // ---- 业务请求头（抓包实测，`send_code` / `account/info` 登录前就带这一套） ----

        /** `1` = TEE 可用。 */
        const val HEADER_TEE_STATUS = "bd-ticket-guard-tee-status"

        /** `get_client_cert` 响应里 `data.server_sn` 回填；服务端据此找自己的证书。 */
        const val HEADER_SERVER_CERT_SN = "bd-ticket-guard-server-cert-sn"

        /** 机型 + 系统版本串，设备侧 `Build.DISPLAY`（如 `PJE110_16.0.5.1301(CN01)`）。 */
        const val HEADER_DISPLAY_OS_VERSION = "bd-ticket-guard-display-os-version"

        /** guard 协议大版本，上游固定 `3`。 */
        const val HEADER_VERSION = "bd-ticket-guard-version"

        /** 迭代版本，抓包里以 `2` 为主。 */
        const val HEADER_ITERATION_VERSION = "bd-ticket-guard-iteration-version"

        const val TEE_STATUS_AVAILABLE = "1"
        const val GUARD_VERSION = "3"
        const val ITERATION_VERSION = "2"

        /** `verify_pub_key` 写死的请求标签（补偿检查用的是 `t=<0/1>;n=1`）。 */
        const val REQUEST_TAG = "t=0;n=0"

        const val DEFAULT_SIGNING_ALIAS = "synapse_device_key"

        private const val KEY_LAST_CHALLENGE = "attest_challenge"
        private const val KEY_REE_PRIVATE = "ree_private_pkcs8"
        private const val KEY_REE_PUBLIC = "ree_public_x509"
        private const val KEY_CLIENT_CERT = "client_cert_pem"
        private const val KEY_ATTEST_CERTS = "attest_certs_header"
        private const val KEY_SERVER_CERT_SN = "server_cert_sn"

        /**
         * challenge = SHA-256(`teePub + "#" + reePub`)。
         *
         * 上游传给 `setAttestationChallenge()` 的是**这个摘要的原始字节**；它另存一份
         * base64 文本用于判断是否为同一会话（不同则重建密钥，防重放）。
         */
        fun computeChallenge(teePublicKey: String, reePublicKey: String): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .digest("$teePublicKey#$reePublicKey".toByteArray(Charsets.UTF_8))

        /** `bd-ticket-guard-client-csr` / `-client-cert` 的值形状：`base64("pub." + 主体)`。 */
        fun publicBlobHeader(body: String): String =
            Base64.getEncoder().encodeToString("pub.$body".toByteArray(Charsets.UTF_8))
    }

    /** 一次设备证明的本地产物。 */
    data class Prepared(
        val teePublicKey: String,
        val attestCerts: String,
        val certChainSize: Int,
        val hasKeyAttestation: Boolean,
        val challengeBase64: String,
    )

    /** 服务端裁决结果。 */
    data class VerifyResult(
        /** `bd-ticket-guard-verify-public-key-result`，0 = 通过。 */
        val errorCode: Int?,
        val logId: String?,
        /** 失败原因（本地判定，用于日志）。 */
        val reason: String? = null,
    ) {
        val ok: Boolean get() = errorCode == 0
    }

    @Volatile
    private var cachedPrepared: Prepared? = null

    /** TEE 是否可用；不可用时整条证明链被跳过（行为等同接线前）。 */
    fun available(): Boolean = tee.isAvailable()

    // ------------------------------------------------------------------ 启动期：备密钥与证书

    /**
     * 生成/复用两把设备密钥，产出上报材料。结果进程内缓存（上游也是启动期一次性加载）。
     */
    fun prepare(force: Boolean = false): Prepared? {
        if (!tee.isAvailable()) return null
        if (!force) cachedPrepared?.let { return it }

        val reePublicKey = reePublicKeyBase64()
        val signing = tee.ensureSigningKeyPair(signingAlias)
        val teePublicKey = Base64.getEncoder().encodeToString(tee.ecPoint(signing.public))
        val challenge = computeChallenge(teePublicKey, reePublicKey)
        val challengeBase64 = Base64.getEncoder().encodeToString(challenge)

        // challenge 变了必须重建（沿用旧 challenge 会被判重放）
        if (cache.get(KEY_LAST_CHALLENGE) != challengeBase64 ||
            !tee.containsAlias(TeeKeyStore.ATTESTATION_ALIAS)
        ) {
            tee.ensureAttestationKeyPair(challenge)
            cache.put(KEY_LAST_CHALLENGE, challengeBase64)
        }

        val chain = tee.certificateChain(TeeKeyStore.ATTESTATION_ALIAS)
        val certs = tee.certificateChainHeaderValue()
        cache.put(KEY_ATTEST_CERTS, certs)

        return Prepared(
            teePublicKey = teePublicKey,
            attestCerts = certs,
            certChainSize = chain.size,
            hasKeyAttestation = tee.keyAttestationExtension() != null,
            challengeBase64 = challengeBase64,
        ).also { cachedPrepared = it }
    }

    /**
     * REE 软密钥：普通 EC P-256，公/私钥都持久化。
     *
     * 私钥可导出、由本项目自持 —— 正是这一点让它**不属于 TEE 边界**：上游同样把它
     * 明文存在 SharedPreferences 里（`sp_key_private_key_ree_*`）。
     */
    private fun reeKeyPair(): KeyPair {
        val storedPriv = cache.get(KEY_REE_PRIVATE)
        val storedPub = cache.get(KEY_REE_PUBLIC)
        if (storedPriv != null && storedPub != null) {
            runCatching {
                val factory = KeyFactory.getInstance("EC")
                return KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(storedPub))),
                    factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(storedPriv))),
                )
            }
        }
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        cache.put(KEY_REE_PRIVATE, Base64.getEncoder().encodeToString(pair.private.encoded))
        cache.put(KEY_REE_PUBLIC, Base64.getEncoder().encodeToString(pair.public.encoded))
        return pair
    }

    /**
     * REE 公钥 base64 —— **裸点**（65 字节 `04 || X || Y`）。
     *
     * 抓包实测 `bd-ticket-guard-ree-public-key` 解码后正好 65 字节、首字节 `0x04`，
     * base64 88 字符（含一个 `=` 填充）；上游同一把密钥在 SP 里另存一份 X.509 SPKI
     * （`sp_key_public_key_ree_*`，124 字符）用于内部校验 —— **HTTP 头只吃裸点**。
     *
     * 端到端校验：把真机 SP 的 REE 私钥（`sp_key_private_key_ree_*`）在 P-256 上推公钥，
     * 得到的裸点与 `sp_key_public_key_ree_*` 逐字节一致，证明这条推导链无误。
     */
    fun reePublicKeyBase64(): String =
        Base64.getEncoder().encodeToString(tee.ecPoint(reeKeyPair().public))

    /**
     * TEE 主密钥公钥 base64 —— 同样是**裸点**。
     *
     * 抓包里 `bd-ticket-guard-client-cert` 解两层后就是这个点；反编译侧对应的日志
     * 字符串是 `add TEE public key instead of cert`（没有证书时用 TEE 公钥顶上）。
     */
    fun teePublicKeyBase64(): String =
        Base64.getEncoder().encodeToString(tee.ecPoint(tee.ensureSigningKeyPair(signingAlias).public))

    /**
     * `bd-ticket-guard-display-os-version`：设备侧 `Build.DISPLAY` 原样串。
     *
     * 抓包值 `PJE110_16.0.5.1301(CN01)` 即该机型 `Build.DISPLAY`。`:core` 是纯 JVM
     * 模块，只能反射读；JVM 上读不到就返回空串，头自动省略。
     */
    fun displayOsVersion(): String = runCatching {
        val build = Class.forName("android.os.Build")
        val display = build.getField("DISPLAY").get(null) as? String
        display?.takeIf { it.isNotBlank() }
            ?: (build.getField("MODEL").get(null) as? String).orEmpty() +
            "_" + (build.getField("RELEASE").get(null) as? String).orEmpty()
    }.getOrDefault("")

    /** `bd-ticket-guard-server-cert-sn`：`get_client_cert` 响应里的 `data.server_sn`。 */
    fun serverCertSn(): String = cache.get(KEY_SERVER_CERT_SN).orEmpty()

    /**
     * 走一次 `get_client_cert`：用主 TEE 密钥签的 CSR 换服务端签发的 client-cert。
     *
     * 拿到就缓存；任何失败都返回空串（上层就不带这个头）。
     */
    fun enrollClientCert(force: Boolean = false): String {
        if (!tee.isAvailable()) return ""
        if (!force) {
            val cached = cache.get(KEY_CLIENT_CERT)
            // 证书与 server_sn 是配对的：缺任一就重新登记（否则业务头拿不到 sn）
            if (!cached.isNullOrEmpty() && cache.get(KEY_SERVER_CERT_SN) != null) return cached
        }
        if (prepare() == null) return ""

        val csrPem = runCatching {
            Pkcs10.toPem(Pkcs10.build(tee.ensureSigningKeyPair(signingAlias)))
        }.getOrNull() ?: return ""

        val csrB64 = Base64.getEncoder().encodeToString(csrPem.toByteArray(Charsets.UTF_8))
        val body = form(
            "terminal_type" to "3",
            "csr" to csrB64,
            "account_sdk_source" to "app",
            "os_type" to "4",
            "multi_login" to "1",
            "server_data" to "1",
        )
        val headers = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "x-tt-request-tag" to REQUEST_TAG,
            "bd-ticket-guard-version" to "3",
            "bd-ticket-guard-iteration-version" to "2",
            HEADER_CLIENT_CSR to publicBlobHeader(csrPem),
            HEADER_REE_PUBLIC_KEY to reePublicKeyBase64(),
        )
        val resp = post(PATH_GET_CLIENT_CERT, body, headers) ?: return ""
        val data = resp.jsonBody?.getAsJsonObject("data")
        val cert = data?.stringOrNull("cert").orEmpty()
        data?.stringOrNull("server_sn")?.takeIf { it.isNotEmpty() }
            ?.let { cache.put(KEY_SERVER_CERT_SN, it) }
        if (cert.isNotEmpty()) cache.put(KEY_CLIENT_CERT, cert)
        return cert
    }

    /**
     * 走一次 `verify_pub_key`：上报证书链，服务端按响应头裁决。
     */
    fun verifyPubKey(): VerifyResult? {
        val prepared = prepare() ?: return null
        runCatching { enrollClientCert() } // 先保证已登记（verify 依赖登记结果）
        val headers = buildVerifyHeaders(
            prepared = prepared,
            reePublicKey = reePublicKeyBase64(),
            clientCert = teePublicKeyBase64(),
        )
        val resp = post(PATH_VERIFY_PUB_KEY, "", headers) ?: return null
        val result = parseVerifyResult(resp.headers)
        cache.put("verify_result", result.errorCode?.toString().orEmpty())
        return result
    }

    // ------------------------------------------------------------------ 请求期：头注入

    /**
     * 装配 **`verify_pub_key` 专用**的三个头（业务请求不要用这个，见 [asGuardHeaders]）。
     *
     * | 头 | 值 | 缺失后果 |
     * |---|---|---|
     * | `bd-ticket-guard-attest-certs` | 证书链（PEM 链整体 base64） | 服务端无法验 TEE |
     * | `bd-ticket-guard-ree-public-key` | REE 公钥裸点 base64 | 无法验读写分离 |
     * | `bd-ticket-guard-client-cert` | **TEE 公钥**裸点 base64（`pub.` 封装） | 同上 |
     *
     * 另有固定 `x-tt-request-tag: t=0;n=0`（写死，不是随机值）。
     */
    fun buildVerifyHeaders(
        prepared: Prepared,
        reePublicKey: String,
        clientCert: String,
    ): Map<String, String> = buildMap {
        put(HEADER_ATTEST_CERTS, prepared.attestCerts)
        if (reePublicKey.isNotEmpty()) put(HEADER_REE_PUBLIC_KEY, reePublicKey)
        if (clientCert.isNotEmpty()) put(HEADER_CLIENT_CERT, publicBlobHeader(clientCert))
        put("x-tt-request-tag", REQUEST_TAG)
    }

    /**
     * 解析 `verify_pub_key` 的响应。
     *
     * 关键：**裁决只看响应头，不看 body**（上游 `t0.c` 自始至终没被读）。
     * 所以 HTTP 200 + 空 body 完全可能是失败。头名大小写不敏感。
     */
    fun parseVerifyResult(headers: Map<String, String>): VerifyResult {
        val lower = headers.entries.associate { it.key.lowercase() to it.value }
        val raw = lower[HEADER_ATTEST_RESULT.lowercase()]
        val code = raw?.trim()?.toIntOrNull()
        return VerifyResult(
            errorCode = code,
            logId = lower[HEADER_LOG_ID.lowercase()],
            reason = when {
                raw == null -> "响应缺少 $HEADER_ATTEST_RESULT（裁决位缺失）"
                code == null -> "裁决位不是整数：$raw"
                else -> null
            },
        )
    }

    /**
     * 接进 [DeviceGuardHeaders]：**这就是上游「请求管线取头」那一步的等价物**。
     *
     * 交付的是**业务请求**该带的那一套（与抓包 `send_code/v1` / `account/info/v2` 对齐）：
     *
     * | 头 | 值 | 说明 |
     * |---|---|---|
     * | `bd-ticket-guard-tee-status` | `1` | TEE 可用 |
     * | `bd-ticket-guard-version` | `3` | 协议大版本 |
     * | `bd-ticket-guard-iteration-version` | `2` | 迭代版本 |
     * | `bd-ticket-guard-display-os-version` | `Build.DISPLAY` | 机型串 |
     * | `bd-ticket-guard-client-cert` | `base64("pub." + base64(裸点))` | **TEE 公钥**（不是证书） |
     * | `bd-ticket-guard-ree-public-key` | `base64(裸点)` | REE 公钥 |
     * | `bd-ticket-guard-server-cert-sn` | `data.server_sn` | 服务端证书序列号 |
     *
     * 两个**只在 `verify_pub_key` 出现**的头不在这里：`bd-ticket-guard-attest-certs`
     * （证书链）与 `x-tt-request-tag: t=0;n=0`。抓包实证登录前的业务请求不带它们，
     * 混进来反而是特征差异（专用头见 [buildVerifyHeaders]）。
     *
     * 拿不到真值（TEE 不可用 / 公钥取不出）就返回空 map —— 与接线前的默认实现一致。
     */
    fun asGuardHeaders(): DeviceGuardHeaders = DeviceGuardHeaders {
        if (runCatching { prepare() }.getOrNull() == null) return@DeviceGuardHeaders emptyMap()
        val teePoint = runCatching { teePublicKeyBase64() }.getOrNull().orEmpty()
        if (teePoint.isEmpty()) return@DeviceGuardHeaders emptyMap()
        runCatching { enrollClientCert() } // 触发登记：拿 client-cert 与 server_sn
        buildMap {
            put(HEADER_TEE_STATUS, TEE_STATUS_AVAILABLE)
            put(HEADER_VERSION, GUARD_VERSION)
            put(HEADER_ITERATION_VERSION, ITERATION_VERSION)
            displayOsVersion().takeIf { it.isNotEmpty() }?.let { put(HEADER_DISPLAY_OS_VERSION, it) }
            put(HEADER_CLIENT_CERT, publicBlobHeader(teePoint))
            runCatching { reePublicKeyBase64() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { put(HEADER_REE_PUBLIC_KEY, it) }
            serverCertSn().takeIf { it.isNotEmpty() }?.let { put(HEADER_SERVER_CERT_SN, it) }
        }
    }

    // ------------------------------------------------------------------ HTTP

    private data class Resp(val status: Int, val body: String, val headers: Map<String, String>) {
        val jsonBody: com.google.gson.JsonObject?
            get() = runCatching { com.google.gson.JsonParser.parseString(body).asJsonObject }.getOrNull()
    }

    private fun post(path: String, body: String, headers: Map<String, String>): Resp? {
        val conn = try {
            HttpSupport.open(
                url = "https://$HOST$path?${commonQuery()}",
                method = "POST",
                headers = headers,
                body = body.ifEmpty { null },
                connectTimeoutMs = 15_000,
                readTimeoutMs = 20_000,
            )
        } catch (e: Exception) {
            // 网络失败静默降级：不带 guard 头，绝不因此打断发码
            return null
        }
        return try {
            val status = conn.responseCode
            val text = runCatching { HttpSupport.readBody(conn) }.getOrDefault("")
            val hs = LinkedHashMap<String, String>()
            conn.headerFields?.forEach { (k, v) -> if (k != null && v.isNotEmpty()) hs[k] = v.joinToString(", ") }
            Resp(status, text, hs)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    /** 与登录链一致的公共 query（`security` 域同样吃这一套）。 */
    private fun commonQuery(): String {
        val params = linkedMapOf(
            "passport-sdk-version" to "60594",
            "device_platform" to "android",
            "os" to "android",
            "aid" to "482431",
            "app_name" to "nova_ai",
            "version_code" to "15070001",
            "version_name" to "15.7.0",
            "os_type" to "4",
            "multi_login" to "1",
        )
        return params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
}
