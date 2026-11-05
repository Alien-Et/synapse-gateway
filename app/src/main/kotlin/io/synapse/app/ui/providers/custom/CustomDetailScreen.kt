package io.synapse.app.ui.providers.custom

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.synapse.app.ui.AppViewModel
import io.synapse.app.ui.ConfirmDestructiveDialog
import io.synapse.app.ui.OutlineActionButton
import io.synapse.app.ui.PageScaffold
import io.synapse.app.ui.SectionCard
import io.synapse.app.ui.SectionLabel
import io.synapse.app.ui.providers.ProviderUiActions
import io.synapse.core.gateway.BalanceQueryConfig
import io.synapse.core.gateway.CustomProviderConfig
import io.synapse.core.gateway.HttpHeader

/**
 * 自定义供应商的**编辑页**（点进供应商列表里的自定义项就是这里）。
 *
 * 与内置供应商的详情页不同，它是一个真正可编辑的表单：
 * - 标签、显示名、接口地址直接在页内改；
 * - **模型由上游自动获取**，不手工输入（手打容易拼错，调用时才炸，很难查）；
 * - 账号（API Key）在下方单独管理。
 *
 * 为什么不再单独做「新建页 → 详情页」两跳：新建完之后落在一个只有
 * 「添加账号」按钮的空白页上，让人不知道下一步该做什么。现在新建完直接进这里，
 * 所有该配的东西都在同一屏。
 */
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

/** 账号池里的账号：(uid, 是否启用)。 */
private fun payloadAccounts(viewModel: AppViewModel, providerId: String): List<Pair<String, Boolean>> =
    viewModel.customAccountRows(providerId)

/** 把头列表渲染成编辑框里的文本（一行一条）。 */
private fun formatHeaders(headers: List<HttpHeader>): String =
    headers.joinToString("\n") { "${it.name}: ${it.value}" }

/** 解析结果：有效的头 + 格式不对的行（给用户当场指出来，而不是默默丢掉）。 */
private data class ParsedHeaders(
    val headers: List<HttpHeader>,
    val invalid: List<String>,
)

/**
 * 解析「一行一条 `名字: 值`」的文本。
 *
 * 分隔用**第一个冒号**而不是 split(":")：值里经常带 `://` 或 `Bearer xxx:yyy`，
 * 按所有冒号切会把值切碎。
 *
 * 名字不合法（含空格等）或值里带换行的行会被剔出来放进 invalid，
 * 由界面提示「这几行不会被发送」——静默丢弃会让人以为配好了却不生效。
 */
private fun parseHeaders(raw: String): ParsedHeaders {
    val valid = mutableListOf<HttpHeader>()
    val invalid = mutableListOf<String>()
    for (line in raw.split('\n')) {
        val text = line.trim()
        if (text.isEmpty()) continue
        val index = text.indexOf(':')
        if (index <= 0) {
            invalid += text
            continue
        }
        val name = text.substring(0, index).trim()
        val value = text.substring(index + 1).trim()
        if (!HttpHeader.isValidName(name) || !HttpHeader.isValidValue(value)) {
            invalid += text
            continue
        }
        valid += HttpHeader(name, value)
    }
    return ParsedHeaders(valid, invalid)
}

/**
 * 组装余额查询配置。未启用返回 null（= 这家不查余额）。
 *
 * 倍数为空或不是正数时回退 1：0 会让换算结果全变 0，负数更没意义，
 * 与其让用户拿到一堆 0，不如按「不换算」处理。
 */
private fun buildBalanceQuery(
    enabled: Boolean,
    url: String,
    path: String,
    field: String,
    scaleText: String,
    divide: Boolean,
    unit: String,
): BalanceQueryConfig? {
    if (!enabled) return null
    return BalanceQueryConfig(
        url = url.trim(),
        path = path.trim(),
        field = field.trim().ifEmpty { "balance" },
        scale = scaleText.trim().toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0,
        divide = divide,
        unit = unit.trim(),
    )
}
