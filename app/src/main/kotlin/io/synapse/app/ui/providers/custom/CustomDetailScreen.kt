package io.synapse.app.ui.providers.custom

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.synapse.app.ui.AppViewModel
import io.synapse.app.ui.SectionCard
import io.synapse.app.ui.providers.ProviderUiActions

/**
 * 自定义供应商的编辑入口。
 *
 * ## 为什么这里不再自己渲染一套表单
 *
 * 以前这里有一整套与「新建页」不同的卡片（请求头、模型列表、账号、余额高级设置），
 * 而新建页有协议下拉与余额预设——两边各自缺另一半，用户看到的就是
 * 「新建时能选的功能，创建后就没了」。
 *
 * 现在**直接复用 [io.synapse.app.ui.CustomProviderScreen] 这一个 Composable**：
 * 两个入口渲染同一份卡片、同一套状态、同一段保存逻辑，功能天然一致。
 * 该表单在 `viewModel != null` 时会额外显示「已创建账号 / 模型列表 / 测试余额」，
 * 所以详情页该有的东西一样不少。
 *
 * 之前这个文件尾部还留着一整套私有 helper（payloadAccounts / formatHeaders /
 * parseHeaders / buildBalanceQuery…），主体删掉后它们全项目零调用，属纯死代码，已清理。
 */
@Composable
fun CustomProviderDetailBody(
    providerId: String,
    viewModel: AppViewModel,
    actions: ProviderUiActions,
    onDeleted: () -> Unit,
) {
    val uiState = viewModel.state.collectAsStateWithLifecycle().value
    val config = uiState.customProviders.firstOrNull { it.providerId == providerId }
    if (config == null) {
        SectionCard {
            Text("供应商配置不存在（可能已被删除）", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    io.synapse.app.ui.CustomProviderScreen(
        existing = config,
        validateLabel = { viewModel.validateCustomLabel(it, config.key) },
        onSave = { updated, apiKey ->
            viewModel.saveCustomProviderAndSync(updated, apiKey) { _, message ->
                viewModel.notice(message)
            }
        },
        onDelete = {
            viewModel.removeCustomProvider(config.key)
            onDeleted()
        },
        onBack = onDeleted,
        viewModel = viewModel,
        embedded = true,
    )
}
