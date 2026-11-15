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

        /** `verify_pub_key` 写死的请求标签（补偿检查用的是 `t=<0/1>;n=1`）。 */
        const val REQUEST_TAG = "t=0;n=0"

        const val DEFAULT_SIGNING_ALIAS = "synapse_device_key"

        private const val KEY_LAST_CHALLENGE = "attest_challenge"
        private const val KEY_REE_PRIVATE = "ree_private_pkcs8"
        private const val KEY_REE_PUBLIC = "ree_public_x509"
        private const val KEY_CLIENT_CERT = "client_cert_pem"
        private const val KEY_ATTEST_CERTS = "attest_certs_header"

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

        val reePublic = reeKeyPair().public.encoded
        val reePublicKey = Base64.getEncoder().encodeToString(reePublic)

        val signing = tee.ensureSigningKeyPair(signingAlias)
        val teePublicKey = Base64.getEncoder().encodeToString(signing.public.encoded)
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

    /** REE 公钥 base64（供请求头用）。 */
    fun reePublicKeyBase64(): String =
        Base64.getEncoder().encodeToString(reeKeyPair().public.encoded)

    /**
     * 走一次 `get_client_cert`：用主 TEE 密钥签的 CSR 换服务端签发的 client-cert。
     *
     * 拿到就缓存；任何失败都返回空串（上层就不带这个头）。
     */
    fun enrollClientCert(force: Boolean = false): String {
        if (!tee.isAvailable()) return ""
        if (!force) cache.get(KEY_CLIENT_CERT)?.takeIf { it.isNotEmpty() }?.let { return it }

        val prepared = prepare() ?: return ""
        val csrPem = runCatching {
            Pkcs10.toPem(Pkcs10.build(tee.ensureSigningKeyPair(signingAlias), signingAlias))
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
        val cert = resp.jsonBody?.let { root ->
            root.getAsJsonObject("data")?.stringOrNull("cert")
        }.orEmpty()
        if (cert.isNotEmpty()) cache.put(KEY_CLIENT_CERT, cert)
        // prepared 只用于触发密钥准备；这里显式引用一下，避免被优化掉语义
        if (prepared.certChainSize < 0) return ""
        return cert
    }

    /**
     * 走一次 `verify_pub_key`：上报证书链，服务端按响应头裁决。
     */
    fun verifyPubKey(): VerifyResult? {
        val prepared = prepare() ?: return null
        val headers = buildVerifyHeaders(
            prepared = prepared,
            reePublicKey = reePublicKeyBase64(),
            clientCert = enrollClientCert(),
        )
        val resp = post(PATH_VERIFY_PUB_KEY, "", headers) ?: return null
        val result = parseVerifyResult(resp.headers)
        cache.put("verify_result", result.errorCode?.toString().orEmpty())
        return result
    }

    // ------------------------------------------------------------------ 请求期：头注入

    /**
     * 装配 `verify_pub_key` / 请求管线用的三个头。
     *
     * | 头 | 值 | 缺失后果 |
     * |---|---|---|
     * | `bd-ticket-guard-attest-certs` | 证书链 | 服务端无法验 TEE |
     * | `bd-ticket-guard-ree-public-key` | REE 公钥 | 无法验读写分离 |
     * | `bd-ticket-guard-client-cert` | `get_client_cert` 下发 | 同上 |
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
     * 拿不到真值（TEE 不可用 / 证书链为空）就返回空 map —— 与接线前的默认实现一致。
     */
    fun asGuardHeaders(): DeviceGuardHeaders = DeviceGuardHeaders {
        val prepared = runCatching { prepare() }.getOrNull() ?: return@DeviceGuardHeaders emptyMap()
        if (prepared.attestCerts.isEmpty()) return@DeviceGuardHeaders emptyMap()
        val cert = runCatching { enrollClientCert() }.getOrDefault("")
        buildVerifyHeaders(
            prepared = prepared,
            reePublicKey = runCatching { reePublicKeyBase64() }.getOrDefault(""),
            clientCert = cert,
        )
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
