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

    // 自定义请求头：用「一行一条 `名字: 值`」的文本框编辑，而不是一堆动态行。
    // 中转站通常一次要配 2~5 条，动态增删行既占屏又难点（手机键盘弹出就看不见了）；
    // 纯文本还有一个好处：用户能直接从文档里整段粘贴。
    var headersText by remember(config.key) { mutableStateOf(formatHeaders(config.headers)) }

    // 余额查询
    var balanceEnabled by remember(config.key) { mutableStateOf(config.balanceQuery != null) }
    var balanceUrl by remember(config.key) { mutableStateOf(config.balanceQuery?.url.orEmpty()) }
    var balancePath by remember(config.key) { mutableStateOf(config.balanceQuery?.path.orEmpty()) }
    var balanceField by remember(config.key) { mutableStateOf(config.balanceQuery?.field ?: "balance") }
    var balanceScale by remember(config.key) {
        mutableStateOf(config.balanceQuery?.scale?.takeIf { it != 1.0 }?.toString().orEmpty())
    }
    var balanceDivide by remember(config.key) { mutableStateOf(config.balanceQuery?.divide ?: false) }
    var balanceUnit by remember(config.key) { mutableStateOf(config.balanceQuery?.unit.orEmpty()) }
    var probing by remember(config.key) { mutableStateOf(false) }

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
            text = "模型列表由上游的 /v1/models 接口在保存后自动获取，不需要手填。" +
                "有些中转站没有这个接口，这时这里显示「无」，但对话照常可用——" +
                "能不能用请到「模型」页实测。",
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
            // 不标红：没有模型列表**不是错误**。中转站不实现 /v1/models、或该 Key
            // 没有模型列表权限都很常见，对话照样能用（Agnes 就是这种）。
            // 标成红色会让人以为配置坏了，跑去反复改地址和 Key。
            Text(
                text = "无",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

    // ---------------- 自定义请求头 ----------------
    SectionCard {
        SectionLabel("自定义请求头")
        Text(
            text = "有些中转站除了 Bearer Key 还要求额外的头（租户号、渠道号、User-Agent 等），" +
                "不配就报 401/403。一行一条，写成「名字: 值」。\n" +
                "Authorization 默认由 API Key 自动生成；在这里写同名的一条会覆盖它（适用于要求 x-api-key 或裸 Key 的站点）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = headersText,
            onValueChange = { headersText = it },
            label = { Text("请求头（一行一条）") },
            placeholder = { Text("User-Agent: MyClient/1.0\nX-Tenant: abc123") },
            supportingText = {
                val parsed = parseHeaders(headersText)
                Text(
                    when {
                        headersText.isBlank() -> "留空表示不添加任何自定义头"
                        parsed.invalid.isNotEmpty() -> "这 ${parsed.invalid.size} 行格式不对，会被忽略：${parsed.invalid.first()}"
                        else -> "将发送 ${parsed.headers.size} 条：${parsed.headers.joinToString("、") { it.name }}"
                    },
                )
            },
            minLines = 2,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // ---------------- 余额查询 ----------------
    SectionCard {
        SectionLabel("余额查询")
        Text(
            text = "OpenAI 协议本身没有余额接口，所以这里要手填一条查询请求。" +
                "填完可以直接点下方「测试余额」验证，不用先保存。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("启用余额查询", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = balanceEnabled, onCheckedChange = { balanceEnabled = it })
        }
        if (balanceEnabled) {
            OutlinedTextField(
                value = balanceUrl,
                onValueChange = { balanceUrl = it.trim() },
                label = { Text("完整地址（可选）") },
                supportingText = { Text("填了就优先用它；留空则用「供应商地址 + 下方的路径」") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = balancePath,
                onValueChange = { balancePath = it.trim() },
                label = { Text("相对路径") },
                supportingText = { Text("例如 /api/user/self 或 /v1/credits") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = balanceField,
                onValueChange = { balanceField = it.trim() },
                label = { Text("取哪个字段") },
                supportingText = { Text("点分写法，支持数组下标：data.quota / data.accounts.0.remain") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = balanceScale,
                    onValueChange = { balanceScale = it.trim() },
                    label = { Text("倍数") },
                    supportingText = { Text("留空=1，原样显示") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = balanceUnit,
                    onValueChange = { balanceUnit = it.trim() },
                    label = { Text("单位") },
                    supportingText = { Text("如 点 / 元，可留空") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "上游数值要除以倍数（不勾则相乘）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = balanceDivide, onCheckedChange = { balanceDivide = it })
            }
            OutlineActionButton(if (probing) "正在查询…" else "测试余额") {
                if (probing) return@OutlineActionButton
                probing = true
                viewModel.probeCustomBalance(
                    config = config.copy(
                        balanceQuery = buildBalanceQuery(
                            enabled = true,
                            url = balanceUrl,
                            path = balancePath,
                            field = balanceField,
                            scaleText = balanceScale,
                            divide = balanceDivide,
                            unit = balanceUnit,
                        ),
                        headers = parseHeaders(headersText).headers,
                    ),
                ) { ok, message ->
                    probing = false
                    viewModel.notice(message)
                    if (ok) {
                        // 查到了就顺手把这个账号的额度刷新一遍，额度中心立刻能看到数
                        viewModel.refreshCredits(providerId, null)
                    }
                }
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
        OutlineActionButton(if (saving) "正在保存…" else "保存") {
            if (saving) return@OutlineActionButton
            saving = true
            // 保存基本信息 + 有 Key 就先落账号，再自动拉模型，最后刷新页面
            viewModel.saveCustomProviderAndSync(
                config = config.copy(
                    name = name.trim().ifEmpty { config.key },
                    baseUrl = baseUrl.trim(),
                    headers = parseHeaders(headersText).headers,
                    balanceQuery = buildBalanceQuery(
                        enabled = balanceEnabled,
                        url = balanceUrl,
                        path = balancePath,
                        field = balanceField,
                        scaleText = balanceScale,
                        divide = balanceDivide,
                        unit = balanceUnit,
                    ),
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
