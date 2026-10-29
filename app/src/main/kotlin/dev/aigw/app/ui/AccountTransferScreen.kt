package dev.aigw.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
 * ## 安全模型
 *
 * 导出文件是**二进制 `.agw`**（魔数 AGW1），默认用用户口令 AES-256-GCM 加密。
 * 口令可以为空——那样导出的就是头部带魔数的明文，方便临时自用，但界面上明确标了风险。
 *
 * **不再提供「粘贴 JSON」入口**：剪贴板是最容易泄漏 token 的地方
 * （任何应用都能读，输入法也能看到），所以只走文件。
 *
 * ## 流程
 *
 * 导出：勾选账号 → 点「导出」→ 可选设口令 → 存成 .agw
 * 导入：选 .agw → 需要口令就先输 → 预览勾选 → 导入
 */
@Composable
fun AccountTransferScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 导出 ----
    var exportAll by remember { mutableStateOf(true) }
    var exportSelection by remember { mutableStateOf(emptySet<String>()) }
    var showExportPassword by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    // 选完保存位置后暂存的 URI：重组件时不能丢，否则锁屏/旋转后导出就断了
    var pendingExportUri by remember { mutableStateOf<android.net.Uri?>(null) }

    // ---- 导入 ----
    // 选完文件后先暂存字节，等（如需）口令输入再解密。
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingNeedsPassword by remember { mutableStateOf(false) }
    var showImportPassword by remember { mutableStateOf(false) }
    var importPassword by remember { mutableStateOf("") }
    var parsed by remember { mutableStateOf<List<SelectableAccount>?>(null) }
    var parsedJson by remember { mutableStateOf("") }
    var importSelection by remember { mutableStateOf(emptySet<String>()) }
    var overwrite by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AccountImportResult?>(null) }
    var importError by remember { mutableStateOf("") }

    val exportGroups = state.providers
        .map { info ->
            info.id to state.accountsOf(info.id).map { SelectableAccount(it.providerId, it.uid, it.nickname) }
        }
        .filter { it.second.isNotEmpty() }
    val exportCount = if (exportAll) exportGroups.sumOf { it.second.size } else exportSelection.size

    /** 真正写文件：加密与否取决于口令是否为空。 */
    fun writeExport(password: String) {
        scope.launch {
            val payload = withContext(Dispatchers.IO) {
                val json = viewModel.exportAccountsJson(if (exportAll) emptySet() else exportSelection)
                dev.aigw.core.pool.AccountTransferCodec.encode(json, password)
            }
            val uri = pendingExportUri ?: return@launch
            pendingExportUri = null
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(payload) } != null
                }.getOrDefault(false)
            }
            viewModel.notice(
                when {
                    !ok -> "写入文件失败"
                    password.isEmpty() -> "已导出 $exportCount 个账号（未加密）"
                    else -> "已导出 $exportCount 个账号（已加密）"
                },
            )
        }
    }

    /** 解密已选文件并把预览填好。 */
    fun decodePending(password: String) {
        val bytes = pendingBytes ?: return
        scope.launch {
            val decoded = withContext(Dispatchers.IO) {
                runCatching { dev.aigw.core.pool.AccountTransferCodec.decode(bytes, password) }
            }
            decoded.fold(
                onSuccess = { json ->
                    runCatching { viewModel.parseAccountTransfer(json) }
                        .onSuccess { accounts ->
                            val list = accounts.map { it.toSelectable() }
                            parsed = list
                            parsedJson = json
                            importSelection = list.map { it.key }.toSet()
                            importError = ""
                            result = null
                            showImportPassword = false
                            pendingBytes = null
                        }
                        .onFailure { e ->
                            importError = e.message ?: "解析失败"
                        }
                },
                onFailure = { e ->
                    // 口令错时不要清掉 pendingBytes：用户可能只是打错，改一下再试
                    importError = e.message ?: "无法读取文件"
                },
            )
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) {
            showExportPassword = false
            return@rememberLauncherForActivityResult
        }
        pendingExportUri = uri
        writeExport(exportPassword)
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importError = ""
            parsed = null
            result = null
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null || bytes.isEmpty()) {
                importError = "读取文件失败或文件为空"
                return@launch
            }
            pendingBytes = bytes
            if (dev.aigw.core.pool.AccountTransferCodec.isEncrypted(bytes)) {
                // 加密文件：先问口令，问完再解密
                pendingNeedsPassword = true
                showImportPassword = true
                importPassword = ""
            } else {
                decodePending("")
            }
        }
    }

    PageScaffold(
        title = "账号导入导出",
        subtitle = "备份与迁移",
        leading = { BackButton(onBack) },
    ) {
        // ================= 导出 =================
        SectionCard(enterIndex = 0) {
            SectionLabel("导出 · EXPORT")
            Text(
                text = "导出文件是二进制 .agw，默认用你设的口令加密（AES-256-GCM）。" +
                    "口令留空则导为明文——那样任何人都能读出里面的 token，只适合临时自用。",
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
            OutlineActionButton("导出为 .agw 文件") {
                if (exportCount == 0) {
                    viewModel.notice("没有选中任何账号")
                    return@OutlineActionButton
                }
                exportPassword = ""
                showExportPassword = true
            }
        }

        // ================= 导入 =================
        SectionCard(enterIndex = 1) {
            SectionLabel("导入 · IMPORT")
            Text(
                text = "选择一个 .agw 文件。加密文件会先问口令，然后列出账号供你勾选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlineActionButton("选择 .agw 文件") {
                openLauncher.launch(arrayOf("application/octet-stream", "application/*", "*/*"))
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
                Button(
                    onClick = {
                        if (importSelection.isEmpty()) {
                            importError = "没有选中任何账号"
                            return@Button
                        }
                        result = viewModel.importAccountsJson(parsedJson, importSelection, overwrite)
                        importError = ""
                        viewModel.refresh()
                    },
                    enabled = importSelection.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("导入选中的 ${importSelection.size} 个账号")
                }
                AccountTree(
                    groups = grouped,
                    displayName = { id -> state.providerOf(id)?.displayName ?: id },
                    selected = importSelection,
                    onSelectionChange = { importSelection = it },
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

    // ---------------- 导出口令 ----------------
    if (showExportPassword) {
        PasswordDialog(
            title = "设置导出口令",
            description = "口令用于加密这个文件。留空则不加密（明文，能直接读到 token）。",
            confirmText = if (exportPassword.isEmpty()) "不加密导出" else "加密导出",
            password = exportPassword,
            onPasswordChange = { exportPassword = it },
            onConfirm = {
                showExportPassword = false
                saveLauncher.launch("ai-gateway-accounts.agw")
            },
            onDismiss = { showExportPassword = false },
        )
    }

    // ---------------- 导入口令 ----------------
    if (showImportPassword) {
        PasswordDialog(
            title = "输入导出口令",
            description = if (importError.isEmpty()) {
                "这个文件是加密的，请输入导出时设置的口令。"
            } else {
                importError
            },
            confirmText = "解密",
            password = importPassword,
            onPasswordChange = { importPassword = it },
            onConfirm = {
                decodePending(importPassword)
            },
            onDismiss = {
                showImportPassword = false
                pendingBytes = null
                pendingNeedsPassword = false
            },
            confirmEnabled = importPassword.isNotEmpty(),
        )
    }
}

/** 口令输入弹窗（导出/导入共用）。 */
@Composable
private fun PasswordDialog(
    title: String,
    description: String,
    confirmText: String,
    password: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(description, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = { Text("口令") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
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
                    onSelectionChange(if (on) selected + keys else selected - keys.toSet())
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
