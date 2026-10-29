package dev.aigw.app.ui.providers.custom

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
import dev.aigw.app.ui.AppViewModel
import dev.aigw.app.ui.ConfirmDestructiveDialog
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.PageScaffold
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.gateway.CustomProviderConfig

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
@Composable
fun CustomProviderDetailBody(
    providerId: String,
    viewModel: AppViewModel,
    actions: ProviderUiActions,
    onDeleted: () -> Unit,
) {
    // 从 state 读配置，而不是一次性抓取：
    //
    // 之前用 `remember(providerId) { viewModel.customConfig(providerId) }`，
    // 切换开关只改了存储，这个快照永远不变，所以界面看不到「一开一关」。
    // state.customProviders 是 ViewModel 在每次 refresh 后重建的，切开关会跟着变。
    val uiState = viewModel.state.collectAsStateWithLifecycle().value
    val config = uiState.customProviders.firstOrNull { it.providerId == providerId }
    if (config == null) {
        SectionCard {
            Text("供应商配置不存在（可能已被删除）", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    // 保存后跳到这一页时模型还在路上，用它显示「正在获取…」而不是空列表
    val fetchingModels = providerId in uiState.customFetchingModels

    var name by remember(config.key) { mutableStateOf(config.name) }
    var baseUrl by remember(config.key) { mutableStateOf(config.baseUrl) }
    var newKey by remember(config.key) { mutableStateOf("") }
    var saving by remember(config.key) { mutableStateOf(false) }
    // 模型以配置为准：拉取成功后 ViewModel 会写回配置，这里跟着重组
    val models = remember(config) { config.models }

    // ---------------- 基本信息 ----------------
    SectionCard {
        SectionLabel("基本信息")
        Text(
            text = "模型标签🏷️ ${config.key}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "调用时写成「${config.key}/模型名」，标签创建后不可修改（改了会断掉已有客户端的配置）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("显示名称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it.trim() },
            label = { Text("接口地址") },
            supportingText = {
                Text(
                    if (config.baseUrl.isBlank()) {
                        "还没填地址（从导入的账号里补建出来的），补上才能正常调用"
                    } else {
                        "填到 /v1，例如 https://api.example.com/v1"
                    },
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // ---------------- 模型：自动获取 ----------------
    SectionCard {
        SectionLabel("模型（自动获取）")
        Text(
            text = "模型列表由上游的 /v1/models 接口自动获取，不需要手填。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = newKey,
            onValueChange = { newKey = it.trim() },
            label = { Text("API Key（填了就一并保存为账号）") },
            supportingText = { Text("保存时会自动向上游获取模型，不需要手动点按钮") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (fetchingModels) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    text = "正在从上游获取模型，稍等一下…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else if (models.isEmpty()) {
            Text(
                text = "还没有模型——点下方「保存」后会自动从上游获取。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = "共 ${models.size} 个模型，调用时写成 ${config.key}/模型名：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (m in models.take(30)) {
                Text(
                    text = "· ${config.key}/$m",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (models.size > 30) {
                Text(
                    text = "…… 还有 ${models.size - 30} 个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }

    // ---------------- 账号 ----------------
    SectionCard {
        SectionLabel("账号（API Key）")

        // 已有的 Key 以账号池为准（配置里的 apiKeys 是摘要，不回显）
        val accounts = payloadAccounts(viewModel, providerId)
        if (accounts.isEmpty()) {
            Text(
                text = "还没有账号。在上面「获取模型」处填一个 Key 即可自动添加。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            for (acc in accounts) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(acc.first, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (acc.second) "启用中" else "已停用",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (acc.second) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    // 账号开关同样直接读写账号池，切了立刻落盘
                    Switch(
                        checked = acc.second,
                        onCheckedChange = { on -> viewModel.setAccountEnabled(providerId, acc.first, on) },
                    )
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除账号",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable { viewModel.removeAccount(providerId, acc.first) }
                            .padding(4.dp),
                    )
                }
            }
        }
    }

    // ---------------- 保存（放最底部） ----------------
    SectionCard {
        OutlineActionButton(if (saving) "正在保存并获取模型…" else "保存") {
            if (saving) return@OutlineActionButton
            saving = true
            // 保存基本信息 + 有 Key 就先落账号，再自动拉模型，最后刷新页面
            viewModel.saveCustomProviderAndSync(
                config = config.copy(
                    name = name.trim().ifEmpty { config.key },
                    baseUrl = baseUrl.trim(),
                ),
                newApiKey = newKey.takeIf { it.isNotBlank() },
            ) { _, message ->
                saving = false
                newKey = ""
                viewModel.notice(message)
            }
        }
    }

    // ---------------- 危险操作 ----------------
    SectionCard {
        var confirm by remember { mutableStateOf(false) }
        OutlineActionButton("删除该供应商") { confirm = true }
        if (confirm) {
            ConfirmDestructiveDialog(
                title = "删除「${config.name}」？",
                body = "它的账号（API Key）与模型列表都会被一并删除，无法恢复。",
                onConfirm = {
                    viewModel.removeCustomProvider(config.key)
                    confirm = false
                    onDeleted()
                },
                onDismiss = { confirm = false },
            )
        }
    }
}

/** 账号池里的账号：(uid, 是否启用)。 */
private fun payloadAccounts(viewModel: AppViewModel, providerId: String): List<Pair<String, Boolean>> =
    viewModel.customAccountRows(providerId)
