package io.synapse.core.provider.doubao

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 「TEE 不可用 → 软密钥兜底」这条降级路径的单测。
 *
 * 纯 JVM 上没有 AndroidKeyStore，正好等价于「设备 TEE 生成密钥失败」那一档 ——
 * 也就是真机上实测到的 `ProviderException: Failed to generate key pair`。
 *
 * 历史 bug：`prepare()` 在 TEE 不可用时直接返回 null，于是 `asGuardHeaders()` 一个头都不发，
 * 服务端看到的就是「没有任何设备证明的裸请求」。现在必须降级成软密钥，把
 * `client-cert` / `ree-public-key` 这几件**不需要 TEE 也能给**的东西带齐。
 */
class DoubaoDeviceAttestationSoftKeyTest {

    private fun jvmAttestation() = DoubaoDeviceAttestation(
        tee = TeeKeyStore(),
        cache = AttestationCache.Memory(),
        verbose = {},
    )

    @Test
    fun `TEE 不可用时 prepare 仍然产出软密钥公钥`() {
        val attestation = jvmAttestation()
        assertFalse(attestation.available(), "纯 JVM 不应有 AndroidKeyStore")

        val prepared = assertNotNull(attestation.prepare(), "TEE 不可用也必须降级出软密钥材料")
        // 裸点 65 字节 → base64 88 字符，与真机 bd-ticket-guard-client-cert 的取值形状一致
        assertEquals(88, prepared.teePublicKey.length)
        // 软密钥给不出带 Key Attestation 的证书链，这两项必须为空/0，而不是编造
        assertTrue(prepared.attestCerts.isEmpty())
        assertEquals(0, prepared.certChainSize)
        assertFalse(prepared.hasKeyAttestation)
    }

    @Test
    fun `软密钥是持久的：两次 prepare 得到同一把公钥`() {
        val cache = AttestationCache.Memory()
        val a = DoubaoDeviceAttestation(tee = TeeKeyStore(), cache = cache)
        val first = a.prepare(force = true)!!.teePublicKey
        val second = a.prepare(force = true)!!.teePublicKey
        assertEquals(first, second, "重建实例也不该换密钥（换 key 等于换设备）")
    }

    @Test
    fun `软密钥公钥是合法的 P-256 裸点`() {
        val prepared = jvmAttestation().prepare(force = true)!!
        val raw = java.util.Base64.getDecoder().decode(prepared.teePublicKey)
        assertEquals(65, raw.size)
        assertEquals(0x04.toByte(), raw[0])
    }
}
