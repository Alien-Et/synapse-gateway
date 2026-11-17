package io.synapse.core.gateway

import io.synapse.core.InMemoryKeyValueStore
import io.synapse.core.provider.AuthKind
import io.synapse.core.provider.ChatCall
import io.synapse.core.provider.CredentialVerdict
import io.synapse.core.provider.ErrorKind
import io.synapse.core.provider.Provider
import io.synapse.core.provider.ProviderAccount
import io.synapse.core.provider.ProviderModelCatalogView
import io.synapse.core.provider.UpstreamError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 「凭证失效」是上游明确说这份凭证不行时的**硬禁用**，不是普通的手动停用。
 *
 * 所以用户手动打开这个开关时，必须先鉴权：
 * - 凭证其实恢复了（上次只是一次瞬时故障）→ 解除硬禁用，别逼用户重新登录；
 * - 凭证确实失效 → 保持停用并说明原因，不能让坏账号重新混进选号池；
 * - 网络不通无法确认 → 保持停用，绝不因为一次超时就把坏凭证当好凭证放回去。
 */
class AccountRevalidateTest {

    private fun stub(id: String, verdict: CredentialVerdict, onVerify: () -> Unit = {}) = object : Provider {
        override val id = id
        override val displayName = id
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            throw UnsupportedOperationException()

        override fun verifyCredential(account: ProviderAccount): CredentialVerdict {
            onVerify()
            return verdict
        }
    }

    private fun engine(verdict: CredentialVerdict, onVerify: () -> Unit = {}): GatewayEngine {
        val e = GatewayEngine(InMemoryKeyValueStore())
        e.registry.register(stub("trae", verdict, onVerify))
        e.pool.upsert(ProviderAccount("trae", "u1", "账号", "{}"))
        return e
    }

    @Test
    fun `凭证已恢复时手动启用会解除硬禁用`() {
        val e = engine(CredentialVerdict.VALID)
        e.pool.disable("trae", "u1", "登录已失效")
        assertTrue(e.pool.status("trae", "u1")!!.disabled)

        val result = e.setAccountEnabled("trae", "u1", true)

        assertEquals(AccountToggleResult.Enabled, result)
        val status = e.pool.status("trae", "u1")!!
        assertFalse(status.disabled, "鉴权通过后不该再是硬禁用")
        assertTrue(status.enabled)
        assertTrue(status.usable)
        assertEquals("", status.reason, "失效原因应一并清掉")
    }

    @Test
    fun `鉴权确认仍然失效时保持停用`() {
        val e = engine(CredentialVerdict.INVALID)
        e.pool.disable("trae", "u1", "登录已失效")

        val result = e.setAccountEnabled("trae", "u1", true)

        assertIs<AccountToggleResult.StillInvalid>(result)
        val status = e.pool.status("trae", "u1")!!
        assertTrue(status.disabled, "明确失效的凭证不能被点开")
        assertFalse(status.usable)
    }

    @Test
    fun `无法确认凭证状态时保持停用`() {
        val e = engine(CredentialVerdict.UNKNOWN)
        e.pool.disable("trae", "u1", "登录已失效")

        assertIs<AccountToggleResult.Unverified>(e.setAccountEnabled("trae", "u1", true))
        assertTrue(e.pool.status("trae", "u1")!!.disabled)
    }

    @Test
    fun `本来正常的账号启用时不做鉴权探测`() {
        var verified = 0
        val e = engine(CredentialVerdict.INVALID) { verified++ }
        e.pool.setEnabled("trae", "u1", false, reason = "手动停用")

        val result = e.setAccountEnabled("trae", "u1", true)

        assertEquals(AccountToggleResult.Enabled, result)
        assertEquals(0, verified, "手动停用的账号不是凭证失效，启用时不该多打一次上游")
        assertTrue(e.pool.status("trae", "u1")!!.usable)
    }

    @Test
    fun `关闭开关只写手动停用、不动硬禁用标记`() {
        val e = engine(CredentialVerdict.VALID)

        val result = e.setAccountEnabled("trae", "u1", false)

        assertEquals(AccountToggleResult.Disabled, result)
        val status = e.pool.status("trae", "u1")!!
        assertFalse(status.enabled)
        assertEquals("手动停用", status.reason)
        assertFalse(status.disabled, "手动停用不是凭证失效")
    }

    @Test
    fun `账号不存在时如实返回`() {
        val e = engine(CredentialVerdict.VALID)
        assertEquals(AccountToggleResult.NotFound, e.setAccountEnabled("trae", "nope", true))
    }
}
