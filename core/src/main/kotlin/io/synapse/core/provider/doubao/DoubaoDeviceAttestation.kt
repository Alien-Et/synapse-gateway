package io.synapse.core.provider.doubao

import java.security.KeyPair
import java.security.MessageDigest
import java.util.Base64

/** attestation 结果的轻量持久化（challenge / 服务端裁决的落盘口）。 */
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
 * 豆包设备证明链（TEE Key Attestation）—— 客户端侧一比一复刻。
 *
 * ## 完整链路（全部来自反编译 + 运行时埋点，见 docs/18）
 *
 * ```text
 * ① 生成/复用主 TEE 密钥（KeyGenParameterSpec，见 TeeKeyStore 文档）
 * ② 生成 REE 软密钥（普通 EC P-256，公钥可导出 → 用于每请求签名）
 * ③ challenge = SHA-256( teePubBase64 + "#" + reePubBase64 )
 * ④ 用 challenge 生成 attestation 密钥（alias bd_ticket_guard_key_attestation）
 *    → getCertificateChain() 拿到含 Key Attestation 扩展的链
 * ⑤ POST /passport/ticket_guard/get_client_cert/   用 CSR 换 client-cert
 * ⑥ POST /passport/ticket_guard/verify_pub_key/    上报证书链，服务端登记公钥
 *    Header: bd-ticket-guard-attest-certs / -ree-public-key / -client-cert
 *    响应头: bd-ticket-guard-verify-public-key-result = <整数 errorCode>
 * ⑦ 后续每次请求带 bd-ticket-guard-client-data（REE 私钥签名）
 * ```
 *
 * ## 为什么这是可以复刻的（而不是「硬件绑定到对方」）
 *
 * 私钥不可导出 ≠ 不可复刻：我们要用的是**本机自己的 TEE 密钥**，服务端登记的也是
 * 本机上报的公钥。目标应用的私钥当然拿不到，但也根本不需要 —— 需要的是同一套
 * *机制*：`AndroidKeyStore` 是公开 API，任何安卓应用都能用。
 *
 * ## 已知缺口（必须实测才能补齐，不要凭空填值）
 *
 * - `get_client_cert` 的**请求参数**（body/query）还没抓到 → 用 [ClientCertProvider] 注入；
 * - 服务端是否校验证书链里的 `attestationApplicationId`
 *   （内含**包名 + 签名证书摘要**，由系统写入、应用无法伪造）→ 这决定本项目的
 *   `io.synapse.app` 能否通过，只能实测。
 */
class DoubaoDeviceAttestation(
    private val tee: TeeKeyStore = TeeKeyStore(),
    private val cache: AttestationCache = AttestationCache.Memory(),
    /** 主签名密钥别名（目标应用由调用方传入，这里给一个本项目的固定值）。 */
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
        const val HEADER_ATTEST_RESULT = "bd-ticket-guard-verify-public-key-result"
        const val HEADER_LOG_ID = "X-Tt-Logid"

        /** `verify_pub_key` 写死的请求标签（补偿检查用的是 `t=<0/1>;n=1`）。 */
        const val REQUEST_TAG = "t=0;n=0"

        const val DEFAULT_SIGNING_ALIAS = "synapse_device_key"

        private const val KEY_LAST_CHALLENGE = "attest_challenge"

        /** `bd-ticket-guard-client-cert` 的取值来源（`get_client_cert` 响应）。 */
        fun interface ClientCertProvider {
            /** 没有真值就返回空串 —— 宁可不带这个头，也不发假头。 */
            fun clientCert(): String
        }

        /**
         * challenge = SHA-256(`teePub + "#" + reePub`)。
         *
         * 注意目标应用传进 `setAttestationChallenge()` 的是**这个摘要的原始字节**，
         * 而它另存了一份 base64 文本用于比对是否为同一会话（`d.e(base64)`）。
         */
        fun computeChallenge(teePublicKey: String, reePublicKey: String): ByteArray =
            MessageDigest.getInstance("SHA-256")
                .digest("$teePublicKey#$reePublicKey".toByteArray(Charsets.UTF_8))
    }

    /** 一次设备证明的本地产物。 */
    data class Prepared(
        /** 主 TEE 公钥（base64，X.509 SubjectPublicKeyInfo）。 */
        val teePublicKey: String,
        /** `bd-ticket-guard-attest-certs` 的值（base64 了的 PEM 证书链）。 */
        val attestCerts: String,
        /** 证书链长度，用于判断拿到的是不是「带 attestation 的链」。 */
        val certChainSize: Int,
        /** 叶子证书是否带 Key Attestation 扩展。 */
        val hasKeyAttestation: Boolean,
        /** 本次使用的 challenge（base64），用于持久化比对。 */
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

    /** TEE 是否可用；不可用时整个证明链会被跳过（行为与「没有 guard 头」一致）。 */
    fun available(): Boolean = tee.isAvailable()

    // ------------------------------------------------------------------ 本地准备

    /**
     * 生成/复用两把 TEE 密钥并产出上报材料。
     *
     * [reePublicKey] 是 REE 软密钥公钥的 base64 文本（由上层提供；REE 私钥是软密钥，
     * 可导出、由本项目自持，不属于 TEE 边界）。
     */
    fun prepare(reePublicKey: String): Prepared? {
        if (!tee.isAvailable()) return null

        val signing = tee.ensureSigningKeyPair(signingAlias)
        val teePublicKey = Base64.getEncoder().encodeToString(signing.public.encoded)
        val challenge = computeChallenge(teePublicKey, reePublicKey)

        // challenge 与上次一致 → 复用密钥；不一致 → 重建（避免被服务端判重放）
        val cached = cache.get(KEY_LAST_CHALLENGE)
        val challengeBase64 = Base64.getEncoder().encodeToString(challenge)
        if (cached != challengeBase64 || !tee.containsAlias(TeeKeyStore.ATTESTATION_ALIAS)) {
            tee.ensureAttestationKeyPair(challenge)
            cache.put(KEY_LAST_CHALLENGE, challengeBase64)
        }

        val chain = tee.certificateChain(TeeKeyStore.ATTESTATION_ALIAS)
        return Prepared(
            teePublicKey = teePublicKey,
            attestCerts = tee.certificateChainHeaderValue(),
            certChainSize = chain.size,
            hasKeyAttestation = tee.keyAttestationExtension() != null,
            challengeBase64 = challengeBase64,
        )
    }

    // ------------------------------------------------------------------ 协议装配

    /**
     * 装配 `verify_pub_key` 的请求头。
     *
     * 三个头的语义（`doAttestation$8` 装配顺序、`doAttestation$7` 构造请求体）：
     *
     * | 头 | 值 | 缺失后果 |
     * |---|---|---|
     * | `bd-ticket-guard-attest-certs` | 证书链 | 服务端无法验 TEE |
     * | `bd-ticket-guard-ree-public-key` | REE 公钥 | 无法验读-写分离 |
     * | `bd-ticket-guard-client-cert` | `get_client_cert` 下发 | 同上 |
     *
     * 另外固定带 `x-tt-request-tag: t=0;n=0`（写死，不是随机值）。
     */
    fun buildVerifyHeaders(
        prepared: Prepared,
        reePublicKey: String,
        clientCert: String,
    ): Map<String, String> = buildMap {
        put(HEADER_ATTEST_CERTS, prepared.attestCerts)
        if (reePublicKey.isNotEmpty()) put(HEADER_REE_PUBLIC_KEY, reePublicKey)
        if (clientCert.isNotEmpty()) put(HEADER_CLIENT_CERT, clientCert)
        put("x-tt-request-tag", REQUEST_TAG)
    }

    /**
     * 解析 `verify_pub_key` 的响应。
     *
     * 关键：**裁决只看响应头，不看 body**（`doAttestation$8` 里 `t0.c` 自始至终没被读）。
     * 所以 HTTP 200 + 空 body 完全可能是「失败」。
     *
     * [headers] 用大小写不敏感匹配（HTTP 头名不区分大小写，实测两端写法都出现过）。
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
     * 接进现有的 [DeviceGuardHeaders] 注入口。
     *
     * 这就是「不破坏产品纪律」的落地方式：**拿不到真值就返回空 map**，
     * 与今天的默认实现行为完全一致；真机链路一通，发码逻辑一行都不用改。
     */
    fun asGuardHeaders(
        reePublicKey: () -> String,
        clientCert: ClientCertProvider = ClientCertProvider { "" },
    ): DeviceGuardHeaders = DeviceGuardHeaders {
        val ree = reePublicKey()
        val prepared = runCatching { prepare(ree) }.getOrNull() ?: return@DeviceGuardHeaders emptyMap()
        buildVerifyHeaders(prepared, ree, clientCert.clientCert())
    }
}
