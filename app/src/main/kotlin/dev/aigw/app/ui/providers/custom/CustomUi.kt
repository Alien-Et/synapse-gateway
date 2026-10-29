package dev.aigw.app.ui.providers.custom

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aigw.core.pool.AccountStatus
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions

/**
 * 自定义供应商（OpenAI 兼容中转站）的界面定义。
 *
 * 这里**只保留品牌符号**（图标 + 描述）。
 *
 * 账号列表、模型获取、基本信息编辑全部由
 * [dev.aigw.app.ui.providers.custom.CustomProviderDetailBody] 统一渲染——
 * 因为自定义供应商的详情页本身就是编辑页，不走通用的「登录入口 + 账号卡」那套。
 *
 * 之前这里还有一整套手填模型、手动拉取、账号折叠卡片的实现，
 * 那些在详情页接管之后就没有任何调用方了，已删除。
 */
class CustomUi(override val id: String) : ProviderUi {

    /**
     * 账号由详情页自己管：通用的「账号」卡片不再重复渲染。
     */
    override val managesOwnAccounts: Boolean
        get() = true

    override fun description() = "OpenAI 兼容中转站（模型自动获取）"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.Dns,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    /**
     * 空实现：自定义供应商的详情页在 [dev.aigw.app.ui.ProviderDetailScreen] 里被提前接管，
     * 永远不会走到这里。留着是为了满足接口（其它内置供应商要用）。
     */
    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) = Unit
}
