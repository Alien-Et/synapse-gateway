package dev.aigw.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.core.pool.AccountImportResult
import dev.aigw.core.provider.ProviderAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 勾选树里的一行账号；导出（AccountStatus）与导入（ProviderAccount）共用。 */
private data class SelectableAccount(val providerId: String, val uid: String, val nickname: String) {
    val key: String get() = "$providerId/$uid"
}

private fun ProviderAccount.toSelectable() = SelectableAccount(providerId, uid, nickname)

/**
 * 账号导入导出。
 *
 * 粒度是「供应商 → 账号」两级：默认全部，也可以只导出某个供应商，或者只勾它下面的一两个账号；
 * 导入时同样先勾再导。
 *
 * 凭证是明文：界面上明确写着含 token，别外传。导出不走网络，只经剪贴板或用户自己选的文件。
 */
@Composable
fun AccountTransferScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // 导出范围：全部 / 按勾选
    var exportAll by remember { mutableStateOf(true) }
    var exportSelection by remember { mutableStateOf(emptySet<String>()) }
    var exportJson by remember { mutableStateOf("") }

    // 导入
    var importText by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<List<SelectableAccount>?>(null) }
    var importSelection by remember { mutableStateOf(emptySet<String>()) }
    var overwrite by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AccountImportResult?>(null) }
    var importError by remember { mutableStateOf("") }

    // 导出账号（用于按钮文案里的数量）
    val exportGroups = state.providers
        .map { info ->
            info.id to state.accountsOf(info.id).map { SelectableAccount(it.providerId, it.uid, it.nickname) }
        }
        .filter { it.second.isNotEmpty() }
    val exportCount = if (exportAll) exportGroups.sumOf { it.second.size } else exportSelection.size

    fun currentExportJson(): String =
        viewModel.exportAccountsJson(if (exportAll) emptySet() else exportSelection)

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = withContext(Dispatchers.IO) { currentExportJson() }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) } != null
                }.getOrDefault(false)
            }
            viewModel.notice(if (ok) "已导出 $exportCount 个账号" else "写入文件失败")
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
            }
            if (text.isNullOrBlank()) {
                importError = "读取文件失败或文件为空"
                return@launch
            }
            importText = text
            parsed = null
            importError = ""
            result = null
        }
    }

    PageScaffold(
        title = "账号导入导出",
        subtitle = "备份与迁移",
        leading = { BackButton(onBack) },
    ) {
        SectionCard(enterIndex = 0) {
            SectionLabel("导出 · EXPORT")
            Text(
                text = "导出的内容含各账号的登录凭证（token），请勿外传。走剪贴板或你选定的文件，全程不联网。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("导出全部账号", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "关掉后可按供应商与账号逐个勾选",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = exportAll, onCheckedChange = { exportAll = it })
            }
            if (!exportAll) {
                AccountTree(
                    groups = exportGroups,
                    displayName = { id -> state.providerOf(id)?.displayName ?: id },
                    selected = exportSelection,
                    onSelectionChange = { exportSelection = it },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlineActionButton(
                    text = "复制到剪贴板",
                    onClick = {
                        if (exportCount == 0) {
                            viewModel.notice("没有选中任何账号")
                            return@OutlineActionButton
                        }
                        val json = currentExportJson()
                        exportJson = json
                        clipboard.setText(AnnotatedString(json))
                        viewModel.notice("已复制 $exportCount 个账号到剪贴板")
                    },
                )
                OutlineActionButton(
                    text = "保存为文件",
                    onClick = {
                        if (exportCount == 0) {
                            viewModel.notice("没有选中任何账号")
                            return@OutlineActionButton
                        }
                        saveLauncher.launch("ai-gateway-accounts.json")
                    },
                )
            }
            if (exportJson.isNotEmpty()) {
                // 让用户能核对到底导出了什么（凭证打码，只回显规模与账号名）
                Text(
                    text = "最近一次导出：${exportJson.length} 字符",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(enterIndex = 1) {
            SectionLabel("导入 · IMPORT")
            Text(
                text = "粘贴导出内容，或选择一个 .json 文件；解析后会列出账号，勾选哪些要导入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = importText,
                onValueChange = {
                    importText = it
                    parsed = null
                    importError = ""
                    result = null
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("粘贴导出内容") },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlineActionButton(
                    text = "从文件选择",
                    onClick = { openLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                )
                OutlineActionButton(
                    text = "解析",
                    onClick = {
                        importError = ""
                        result = null
                        val text = importText.trim()
                        if (text.isEmpty()) {
                            importError = "先粘贴导出内容或选择一个文件"
                            return@OutlineActionButton
                        }
                        runCatching { viewModel.parseAccountTransfer(text) }
                            .onSuccess { accounts ->
                                parsed = accounts.map { it.toSelectable() }
                                importSelection = accounts.map { it.toSelectable().key }.toSet()
                            }
                            .onFailure { importError = it.message ?: "解析失败" }
                    },
                )
            }

            if (importError.isNotEmpty()) {
                Text(
                    text = importError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            val accounts = parsed
            if (accounts != null) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val grouped = state.providers
                    .map { info -> info.id to accounts.filter { it.providerId == info.id } }
                    .filter { it.second.isNotEmpty() }
                val unknown = accounts.filter { account -> state.providerOf(account.providerId) == null }
                Text(
                    text = "文件里有 ${accounts.size} 个账号，已选 ${importSelection.size} 个",
                    style = MaterialTheme.typography.bodyMedium,
                )
                AccountTree(
                    groups = grouped,
                    displayName = { id -> state.providerOf(id)?.displayName ?: id },
                    selected = importSelection,
                    onSelectionChange = { importSelection = it },
                )
                if (unknown.isNotEmpty()) {
                    Text(
                        text = "本机没有这些供应商，将被跳过：" +
                            unknown.map { it.providerId }.distinct().joinToString("、"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("覆盖同名账号", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "关闭时，已存在的账号保持原样（跳过）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = overwrite, onCheckedChange = { overwrite = it })
                }
                OutlineActionButton(
                    text = "导入选中的 ${importSelection.size} 个账号",
                    onClick = {
                        if (importSelection.isEmpty()) {
                            importError = "没有选中任何账号"
                            return@OutlineActionButton
                        }
                        viewModel.importAccountsJson(importText, importSelection, overwrite)
                            .let { outcome ->
                                result = outcome
                                importError = ""
                                viewModel.refresh()
                            }
                    },
                )
            }

            result?.let { outcome ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = "导入完成：新增 ${outcome.added.size}、覆盖 ${outcome.updated.size}、" +
                        "跳过 ${outcome.skipped.size}、未知供应商 ${outcome.unknownProviders.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (outcome.skipped.isNotEmpty()) {
                    Text(
                        text = "跳过的账号：" + outcome.skipped.take(8).joinToString("、") +
                            if (outcome.skipped.size > 8) " 等 ${outcome.skipped.size} 个" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (outcome.unknownProviders.isNotEmpty()) {
                    Text(
                        text = "未知供应商：" + outcome.unknownProviders.distinct().joinToString("、"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * 供应商 → 账号两级勾选。
 *
 * 供应商那一行是「整组开关」：勾上/取消会把该供应商下的账号一起带上；
 * 部分勾选时显示为半选（Compose 的 Checkbox 没有三态，这里用文案提示数量）。
 */
@Composable
private fun AccountTree(
    groups: List<Pair<String, List<SelectableAccount>>>,
    displayName: (String) -> String,
    selected: Set<String>,
    onSelectionChange: (Set<String>) -> Unit,
) {
    if (groups.isEmpty()) {
        Text(
            text = "没有可选的账号",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    for ((providerId, accounts) in groups) {
        val keys = accounts.map { it.key }
        val allSelected = keys.all { it in selected }
        val selectedCount = keys.count { it in selected }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = allSelected,
                onCheckedChange = { on ->
                    onSelectionChange(
                        if (on) selected + keys else selected - keys.toSet(),
                    )
                },
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(displayName(providerId), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "已选 $selectedCount / ${accounts.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        for (account in accounts) {
            val key = account.key
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable {
                        onSelectionChange(if (key in selected) selected - key else selected + key)
                    }
                    .padding(start = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = key in selected,
                    onCheckedChange = { on ->
                        onSelectionChange(if (on) selected + key else selected - key)
                    },
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = account.nickname.ifEmpty { account.uid },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
