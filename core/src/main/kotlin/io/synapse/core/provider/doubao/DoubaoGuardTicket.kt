package io.synapse.core.provider.doubao

import com.google.gson.JsonParser
import java.io.File
import java.util.Base64

/**
 * 真机票据（从豆包 App 的 SharedPreferences 导出）→ 业务请求的 `bd-ticket-guard-*` 头。
 *
 * ## 为什么需要它
 *
 * 真机 `send_code/v1` 带 6 个 guard 头（tee-status / version / iteration-version /
 * display-os-version / client-cert / ree-public-key / server-cert-sn）。其中**只有
 * `client-data` / `key-sign` 是每请求 TEE 私钥签名**（登录后才带），其余都是**设备静态属性**：
 *
 * | 头 | 来源（豆包 SP） |
 * |---|---|
 * | `client-cert` | `bd_ticket_guard_tee_static_sign_cache.tee_pub_key`（TEE 公钥裸点） |
 * | `ree-public-key` | `sp_key_public_key_ree_TicketGuardManager`（REE 公钥裸点 hex） |
 * | `server-cert-sn` | `sp_key_server_cert.serial_number`（服务端证书序列号） |
 * | `tee-status` / `iteration-version` / `version` | 常量 1 / 2 / 3 |
 * | `display-os-version` | `Build.DISPLAY` |
 *
 * 所以「补上真机 guard 头」不需要 TEE 私钥，只需要把这几个静态值搬过来。
 * 自建 TEE 链（[DoubaoDeviceAttestation]）是另一条路，两条路互不干扰：
 * 票据文件存在就用真机票据，不存在就回退自建链。
 *
 * ## 文件格式（`/sdcard/Android/data/io.synapse.app/files/doubao_guard.json`）
 *
 * ```json
 * {
 *   "clientCertTeePoint": "BCmY...（88 字符 base64，TEE 公钥裸点）",
 *   "reePublicHex": "04A5...（130 字符 hex，REE 公钥裸点）",
 *   "serverCertSn": "533240336124694022040808462028007165443034493949",
 *   "displayOs": "PJE110_16.0.5.1301(CN01)"
 * }
 * ```
 *
 * 该文件由外部工具（root 读取豆包 SP）生成；**过期就重新导出**，
 * 换设备 / 服务端证书轮换后 SN 会变。
 */
internal object DoubaoGuardTicket {

    /** 网关自己的外部私有目录，App 无需任何存储权限即可读写。 */
    const val DEFAULT_PATH = "/sdcard/Android/data/io.synapse.app/files/doubao_guard.json"

    const val HEADER_TEE_STATUS = "bd-ticket-guard-tee-status"
    const val HEADER_VERSION = "bd-ticket-guard-version"
    const val HEADER_ITERATION_VERSION = "bd-ticket-guard-iteration-version"
    const val HEADER_DISPLAY_OS_VERSION = "bd-ticket-guard-display-os-version"
    const val HEADER_CLIENT_CERT = "bd-ticket-guard-client-cert"
    const val HEADER_REE_PUBLIC_KEY = "bd-ticket-guard-ree-public-key"
    const val HEADER_SERVER_CERT_SN = "bd-ticket-guard-server-cert-sn"

    data class Ticket(
        /** TEE 公钥裸点（65 字节）的 base64，88 字符。 */
        val teePoint: String,
        /** REE 公钥裸点（65 字节）的 hex，130 字符。 */
        val reePublicHex: String,
        val serverCertSn: String,
        val displayOs: String = "",
        val teeStatus: String = "1",
        val iterationVersion: String = "2",
        val version: String = "3",
    )

    /** 解析票据 JSON；缺关键字段（TEE 公钥 / REE 公钥）视为无效。 */
    fun parse(text: String): Ticket? = runCatching {
        val o = JsonParser.parseString(text).asJsonObject
        fun s(key: String): String =
            o.get(key)?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        Ticket(
            teePoint = s("clientCertTeePoint"),
            reePublicHex = s("reePublicHex"),
            serverCertSn = s("serverCertSn"),
            displayOs = s("displayOs"),
            teeStatus = s("teeStatus").ifEmpty { "1" },
            iterationVersion = s("iterationVersion").ifEmpty { "2" },
            version = s("version").ifEmpty { "3" },
        )
    }.getOrNull()?.takeIf { it.teePoint.isNotEmpty() && it.reePublicHex.isNotEmpty() }

    /** 读默认路径的票据；文件不存在/格式不对返回 null（调用方回退自建链）。 */
    fun read(file: File = File(DEFAULT_PATH)): Ticket? =
        if (!file.isFile) null
        else parse(runCatching { file.readText() }.getOrDefault(""))

    /** REE 公钥：hex（130 字符）→ 65 字节 → base64（88 字符）。 */
    fun reePublicKeyBase64(hex: String): String = runCatching {
        Base64.getEncoder().encodeToString(hexToBytes(hex))
    }.getOrDefault("")

    /** 装配业务请求的 guard 头（与真机 `send_code/v1` 逐项对齐）。 */
    fun headers(t: Ticket): Map<String, String> = buildMap {
        put(HEADER_TEE_STATUS, t.teeStatus)
        put(HEADER_VERSION, t.version)
        put(HEADER_ITERATION_VERSION, t.iterationVersion)
        t.displayOs.takeIf { it.isNotEmpty() }?.let { put(HEADER_DISPLAY_OS_VERSION, it) }
        // client-cert 不是证书，是「TEE 公钥裸点」的 base64("pub." + 裸点) 封装（外层 124 字符）
        put(HEADER_CLIENT_CERT, DoubaoDeviceAttestation.publicBlobHeader(t.teePoint))
        reePublicKeyBase64(t.reePublicHex).takeIf { it.isNotEmpty() }
            ?.let { put(HEADER_REE_PUBLIC_KEY, it) }
        t.serverCertSn.takeIf { it.isNotEmpty() }?.let { put(HEADER_SERVER_CERT_SN, it) }
    }
}

/** 十六进制文本 → 字节数组（非法字符返回空数组，由调用方降级）。 */
internal fun hexToBytes(hex: String): ByteArray {
    val clean = hex.filterNot { it.isWhitespace() }
    if (clean.isEmpty() || clean.length % 2 != 0) return ByteArray(0)
    return ByteArray(clean.length / 2) { i ->
        val hi = Character.digit(clean[i * 2], 16)
        val lo = Character.digit(clean[i * 2 + 1], 16)
        if (hi < 0 || lo < 0) return ByteArray(0)
        ((hi shl 4) or lo).toByte()
    }
}
