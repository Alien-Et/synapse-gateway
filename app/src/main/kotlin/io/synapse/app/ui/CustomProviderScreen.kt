@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.synapse.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.synapse.core.gateway.CustomProviderConfig

/**
 * 新建自定义供应商。
 *
 * **标签 + 地址 + 一个 API Key 填完就能保存**，模型在保存后由后台自动从
 * `/v1/models` 拉取 —— 不需要手填模型，也不需要先进到详情页再保存一次。
 *
 * 与编辑页的分工：
 * - 这里只做「一次性创建」，保存即完成；
 * - 之后想改名称/地址/账号，到供应商详情页（它本身就是编辑页）。
 *
 * 两边的字段刻意保持一致，避免「新建能填、编辑不能改」这种割裂感。
 */
@Composable
fun CustomProviderScreen(
    existing: CustomProviderConfig?,
    /** 校验标签是否合法（由核心层提供：不能与内置标签重名、不能与别家重名等）。返回 null 表示通过。 */
    validateLabel: (String) -> String?,
    /** 保存：核心层负责落盘 + 落账号 + 后台拉模型。 */
    onSave: (config: CustomProviderConfig, apiKey: String?) -> Unit,
    onDelete: ((String) -> Unit)?,
    onBack: () -> Unit,
    /**
     * 传入 ViewModel 时启用「已创建供应商」专属区：请求头编辑、模型列表、
     * 账号管理、余额测试。
     *
     * 新建与编辑**共用这一个 Composable**，卡片一模一样、功能一模一样，
     * 不会再出现「新建能选协议/预设，创建后全没了」——那是因为以前
     * 点开已创建项走的是 CustomProviderDetailBody 另一套界面。
     */
    viewModel: AppViewModel? = null,
    /** true 时只渲染表单内容（供已存在的详情页复用，避免嵌套 PageScaffold）。 */
    embedded: Boolean = false,
) {
    val isNew = existing == null

    var key by remember { mutableStateOf(existing?.key.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }

    // 上游协议：决定对话/模型端点路径与鉴权头。默认 OpenAI 兼容（兼容老配置）。
    var protocol by remember { mutableStateOf(existing?.protocol.orEmpty().ifEmpty { "openai" }) }
    // 总是向上游要流式：老配置没有这个字段，默认跟随协议自身的默认值
    var forceStreaming by remember {
        mutableStateOf(
            existing?.forceStreaming
                ?: io.synapse.core.gateway.ProviderProtocols.of(protocol).forceStreaming,
        )
    }
    // 余额查询：选一个预设就自动补全路径/字段，也可以完全自定义。
    var balancePreset by remember { mutableStateOf(existing?.balanceQuery?.preset.orEmpty()) }
    var balancePath by remember { mutableStateOf(existing?.balanceQuery?.path.orEmpty()) }
    var balanceField by remember { mutableStateOf(existing?.balanceQuery?.field.orEmpty()) }
    var balanceMethod by remember { mutableStateOf(existing?.balanceQuery?.method.orEmpty().ifEmpty { "GET" }) }
    var balanceUnit by remember { mutableStateOf(existing?.balanceQuery?.unit.orEmpty()) }
    var balanceUrl by remember { mutableStateOf(existing?.balanceQuery?.url.orEmpty()) }
    var balanceScale by remember {
        mutableStateOf(
            existing?.balanceQuery?.scale?.takeIf { it != 1.0 }?.toString().orEmpty(),
        )
    }
    var balanceDivide by remember { mutableStateOf(existing?.balanceQuery?.divide ?: false) }
    var probing by remember { mutableStateOf(false) }

    // 自定义请求头：一行一条「名字: 值」，能整段粘贴文档里的头
    var headersText by remember {
        mutableStateOf(existing?.headers.orEmpty().joinToString("\n") { "${it.name}: ${it.value}" })
    }

    // 编辑态从 state 实时读（模型列表由后台拉取后写回配置，这里要跟着变）。
    // 只 collect 一次：Compose 里 ?.value 链式写法既难读又容易触发类型推断错误。
    val uiState = viewModel?.state?.collectAsStateWithLifecycle()?.value
    // 已有账号数要读**账号池**，不能读 config.apiKeys：
    // 后者是「待同步的 Key」载体，保存成功后就清空了，读它永远显示 0，
    // 与下面「API Key（N 个）」卡片自相矛盾。
    val savedAccountCount = existing?.let { viewModel?.customAccountsWithKeys(it.providerId)?.size } ?: 0
    val liveConfig = uiState?.customProviders?.firstOrNull { it.key == existing?.key }
    val models = liveConfig?.models ?: existing?.models.orEmpty()
    val fetchingModels = existing?.providerId.orEmpty() in (uiState?.customFetchingModels.orEmpty())

    // API Key：一个输入框搞定，**点保存即生效**，不需要先点「添加」再保存。
    //
    // 之前是「输入框 + 添加按钮 → 列表 → 保存」的三段式，等于把同一件事做两遍，
    // 少点一次就白填。现在一个框、一行一个 Key，保存时直接收走。
    //
    // 编辑模式不回显旧 Key（加密存储里只有摘要），留空即「不动现有账号」。
    var keyInput by remember { mutableStateOf("") }

    val parsedKeys = parseApiKeys(keyInput)

    // 表单主体抽成 lambda：独立页用 PageScaffold 包一层，详情页直接把内容放进
    // 自己的 PageScaffold，避免两层标题栏叠在一起。
    val formContent: @Composable ColumnScope.() -> Unit = {
        // ---------------- 基本信息 ----------------
        SectionCard {
            SectionLabel("基本信息")
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text("模型标签🏷️") },
                supportingText = {
                    Text("调用时写成「标签/模型名」，例如 ${key.ifEmpty { "mysite" }}/gpt-4o；创建后不可修改")
                },
                singleLine = true,
                enabled = isNew,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("显示名称") },
                supportingText = { Text("只影响界面显示，可留空（默认用标签）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it.trim() },
                label = { Text("接口地址") },
                supportingText = { Text("填到 /v1，例如 https://api.example.com/v1") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---------------- 上游协议 ----------------
        SectionCard {
            SectionLabel("接入协议")
            val choices = io.synapse.core.gateway.ProviderProtocols.choices()
            var expanded by remember { mutableStateOf(false) }
            val current = io.synapse.core.gateway.ProviderProtocols.of(protocol)
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = current.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("协议") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    choices.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                protocol = id
                                // 换协议时把「总是流式」跟到新协议的默认值，
                                // 否则从 Responses 切回 OpenAI 会带着「强制流式」不放
                                forceStreaming =
                                    io.synapse.core.gateway.ProviderProtocols.of(id).forceStreaming
                                expanded = false
                            },
                        )
                    }
                }
            }
            Text(
                text = current.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "对话端点：${current.chatPath}　鉴权：" +
                    when (current.auth) {
                        io.synapse.core.gateway.ProtocolAuth.BEARER -> "Bearer"
                        io.synapse.core.gateway.ProtocolAuth.X_API_KEY -> "x-api-key"
                        io.synapse.core.gateway.ProtocolAuth.QUERY_KEY -> "?key="
                    },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 请求体形状：Chat Completions（messages）还是 Responses（input）。
            // 选错的话上游会回 400，所以把它显式写出来，用户不用去猜。
            Text(
                text = when (current.requestShape) {
                    io.synapse.core.gateway.RequestShape.CHAT -> "请求体：messages（Chat Completions）"
                    io.synapse.core.gateway.RequestShape.RESPONSES -> "请求体：input（Responses）"
                } + if (current.forceStreaming) "　· 上游只吃流式，非流式请求由网关聚合" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------------- 流式行为 ----------------
        SectionCard {
            // 协议对象在这里重新取一次：上面那张卡片的 `current` 出了作用域就不可见，
            // 直接引用会编译失败（也是提醒：这里读的是最新选中的协议，不是旧快照）。
            val current = io.synapse.core.gateway.ProviderProtocols.of(protocol)
            SectionLabel("流式")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("总是向上游要流式", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "作用：客户端要非流式时，仍按 stream:true 请求上游，由网关在本地聚合成普通 JSON。" +
                            "有些中转站和 Codex 类后端只接受流式，关着会直接 400。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = forceStreaming,
                    onCheckedChange = { forceStreaming = it },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (forceStreaming) {
                    "已开启：上游一律按流式请求" + if (current.forceStreaming) "（该协议默认如此，可关）" else ""
                } else {
                    "已关闭：客户端要什么就发什么（推荐，除非上游只吃流式）"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ---------------- 余额查询 ----------------
        SectionCard {
            SectionLabel("余额查询（可选）")
            val presets = io.synapse.core.gateway.BalancePresets.choices()
            var presetExpanded by remember { mutableStateOf(false) }
            val presetLabel = io.synapse.core.gateway.BalancePresets.of(balancePreset)?.label
                ?: if (balancePreset.isEmpty()) "自定义 / 不查余额" else balancePreset
            ExposedDropdownMenuBox(
                expanded = presetExpanded,
                onExpandedChange = { presetExpanded = it },
            ) {
                OutlinedTextField(
                    value = presetLabel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("余额接口预设") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                ExposedDropdownMenu(expanded = presetExpanded, onDismissRequest = { presetExpanded = false }) {
                    presets.forEach { (id, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                balancePreset = id
                                // 选了预设就把已知形状预填进输入框（用户仍可改）
                                io.synapse.core.gateway.BalancePresets.of(id)?.let { p ->
                                    balancePath = p.path
                                    balanceField = p.field
                                    balanceMethod = p.method
                                    balanceUnit = p.unit
                                }
                                presetExpanded = false
                            },
                        )
                    }
                }
            }
            // 选「自定义」或选了预设后想微调，都能直接改这些字段
            if (balancePreset.isNotEmpty()) {
                OutlinedTextField(
                    value = balancePath,
                    onValueChange = { balancePath = it },
                    label = { Text("相对路径（可改）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = balanceField,
                    onValueChange = { balanceField = it },
                    label = { Text("取值字段（可用逗号列多个）") },
                    supportingText = { Text("点分路径，支持数组下标；留空表示响应就是数字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = balanceUnit,
                    onValueChange = { balanceUnit = it },
                    label = { Text("单位（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // ---------------- API Key ----------------
        SectionCard {
            SectionLabel("API Key（账号）")
            Text(
                text = if (isNew) {
                    "填一个就能保存，保存后会自动拿它去上游获取模型列表。" +
                        "要多个 Key 就一行一个，一个 Key = 一个账号，网关会轮换使用。"
                } else {
                    "留空则不动现有账号；要加新账号就直接填，一行一个。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = keyInput,
                // 不 trim 每一击：用户粘贴多行时中间的空行/缩进要能看见，保存时再清
                onValueChange = { keyInput = it },
                label = { Text("API Key") },
                placeholder = { Text(if (isNew) "sk-…" else "留空则不改动") },
                supportingText = {
                    Text(
                        when {
                            parsedKeys.isEmpty() && isNew -> "一行一个，可填多个"
                            parsedKeys.isEmpty() -> "留空 = 保留现有 $savedAccountCount 个账号"
                            parsedKeys.size == 1 -> "将添加 1 个账号：${maskKey(parsedKeys.first())}"
                            else -> "将添加 ${parsedKeys.size} 个账号"
                        },
                    )
                },
                minLines = 1,
                maxLines = 5,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---------------- 模型（自动获取，编辑态列出来） ----------------
        SectionCard {
            SectionLabel("模型（自动获取）")
            Text(
                text = "保存后会自动向上游获取模型列表，不需要手填。有些中转站没有这个接口，" +
                    "这时显示「无」，但对话照常可用——能不能用请到「模型」页实测。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                fetchingModels -> Text(
                    "正在从上游获取模型，稍等一下…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                models.isEmpty() -> Text(
                    "无",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Text(
                        text = "共 ${models.size} 个模型，调用时写成 ${key.ifEmpty { "标签" }}/模型名：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (m in models.take(30)) {
                        Text(
                            "· ${key.ifEmpty { "标签" }}/$m",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    if (models.size > 30) {
                        Text(
                            "…… 还有 ${models.size - 30} 个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }
        }

        // ---------------- 自定义请求头（与旧详情页同款，一行一条） ----------------
        SectionCard {
            SectionLabel("自定义请求头")
            Text(
                text = "有些中转站除了 Bearer Key 还要求额外的头（租户号、渠道号、User-Agent 等）。" +
                    "一行一条，写成「名字: 值」，可直接整段粘贴。\n" +
                    "Authorization 默认由 API Key 自动生成；写同名的一条会覆盖它。",
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
                            parsed.invalid.isNotEmpty() ->
                                "这 ${parsed.invalid.size} 行格式不对，会被忽略：${parsed.invalid.first()}"
                            else -> "将发送 ${parsed.headers.size} 条：${parsed.headers.joinToString("、") { it.name }}"
                        },
                    )
                },
                minLines = 2,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ---------------- 余额：完整字段 + 测试（两页都可用） ----------------
        if (balancePreset.isNotEmpty() || existing?.balanceQuery != null) {
            SectionCard {
                SectionLabel("余额高级设置")
                OutlinedTextField(
                    value = balanceUrl,
                    onValueChange = { balanceUrl = it },
                    label = { Text("完整地址（可选）") },
                    supportingText = { Text("填了就优先用它；留空则用「接口地址 + 相对路径」") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = balanceScale,
                        onValueChange = { balanceScale = it },
                        label = { Text("倍数") },
                        supportingText = { Text("留空=1") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = balanceMethod,
                        onValueChange = { balanceMethod = it.uppercase() },
                        label = { Text("方法") },
                        supportingText = { Text("GET / POST") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    Text(
                        "上游数值要除以倍数（不勾则相乘）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.Switch(
                        checked = balanceDivide,
                        onCheckedChange = { balanceDivide = it },
                    )
                }
                // 「测试余额」与旧详情页一致：不用先保存就能验证配置对不对
                if (viewModel != null && existing != null) {
                    OutlineActionButton(if (probing) "正在查询…" else "测试余额") {
                        if (probing) return@OutlineActionButton
                        probing = true
                        viewModel.probeCustomBalance(
                            config = existing.copy(
                                baseUrl = baseUrl,
                                headers = parseHeaders(headersText).headers,
                                balanceQuery = buildBalanceQuery(
                                    url = balanceUrl,
                                    path = balancePath,
                                    field = balanceField,
                                    method = balanceMethod,
                                    scaleText = balanceScale,
                                    divide = balanceDivide,
                                    unit = balanceUnit,
                                    preset = balancePreset,
                                ),
                            ),
                        ) { ok, message ->
                            probing = false
                            viewModel.notice(message)
                            if (ok) viewModel.refreshCredits(existing.providerId, null)
                        }
                    }
                }
            }
        }

        // ---------------- 已有账号 + 统一查看 Key（编辑态） ----------------
        //
        // 这一块在**已保存的供应商**里才出现（新建时还没有账号可看）。
        // 之前只显示 uid 与开关：配了多个 Key 之后根本认不出哪个是哪个，
        // 想核对/复制某一个还得去翻存储。现在把明文 Key 摊开，并支持整组复制。
        if (viewModel != null && existing != null) {
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            val rows = viewModel.customAccountsWithKeys(existing.providerId)
            // 默认全部遮住：截屏/录屏时不会顺带把 Key 泄出去，要看再点
            var revealed by remember(existing.key) { mutableStateOf(emptySet<String>()) }

            SectionCard {
                SectionLabel("API Key（${rows.size} 个）")
                if (rows.isEmpty()) {
                    Text(
                        "还没有账号。在上面 API Key 处填一个即可添加。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = "点「显示」看完整 Key，点 Key 本身可复制；多个 Key 时一个 Key 对应一个账号，网关按余额与失败情况轮换。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    for (row in rows) {
                        val shown = row.uid in revealed
                        val text = if (shown) row.apiKey.ifEmpty { "（读不到 Key）" } else maskKey(row.apiKey)
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = text,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            if (row.apiKey.isNotEmpty()) {
                                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(row.apiKey))
                                                viewModel.notice("Key 已复制")
                                            }
                                        },
                                )
                                Text(
                                    text = if (shown) "隐藏" else "显示",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable {
                                            revealed = if (shown) revealed - row.uid else revealed + row.uid
                                        }
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                                androidx.compose.material3.Switch(
                                    checked = row.enabled,
                                    onCheckedChange = { on ->
                                        viewModel.setAccountEnabled(existing.providerId, row.uid, on)
                                    },
                                )
                            }
                            Text(
                                text = "${row.displayName} · 当前" + if (row.enabled) "启用中" else "已停用",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // 整组复制：换机器/贴到别处时不用一个个点
                    OutlineActionButton("复制全部 Key（一行一个）") {
                        val all = rows.mapNotNull { it.apiKey.takeIf { key -> key.isNotEmpty() } }
                        if (all.isEmpty()) {
                            viewModel.notice("没有可复制的 Key")
                        } else {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(all.joinToString("\n")))
                            viewModel.notice("已复制 ${all.size} 个 Key")
                        }
                    }
                }
            }
        }

        // ---------------- 保存 ----------------
        SectionCard {
            val keyTrimmed = key.trim()
            val baseUrlTrimmed = baseUrl.trim()
            val keyError = if (keyTrimmed.isEmpty()) "模型标签不能为空" else validateLabel(keyTrimmed)
            // 只有新建才强制要有 Key：编辑页不回显旧 Key，留空是「不改动」，不是「填漏了」
            val keyRequired = isNew && parsedKeys.isEmpty()

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    keyError != null -> Text(
                        keyError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    baseUrlTrimmed.isEmpty() -> Text(
                        "接口地址不能为空",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    keyRequired -> Text(
                        "至少填一个 API Key（否则拿不到模型）",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                val canSave = keyError == null && baseUrlTrimmed.isNotEmpty() && !keyRequired
                OutlineActionButton(if (canSave) "保存" else "请先补全标签、地址与 Key") {
                    if (!canSave) return@OutlineActionButton
                    onSave(
                        CustomProviderConfig(
                            key = keyTrimmed,
                            // 显示名留空时用标签兜底：界面上总得有个名字
                            name = name.trim().ifEmpty { keyTrimmed },
                            baseUrl = baseUrlTrimmed,
                            // 本次要落成账号的 Key（已按行拆开、去重）。留空则不动账号池。
                            apiKeys = parsedKeys,
                            // 模型留空：保存后由后台拉取并写回
                            models = existing?.models.orEmpty(),
                            // 启用状态不在这一页管理（账号级的开关在详情页），保持原值
                            enabled = existing?.enabled ?: true,
                            protocol = protocol,
                            // 流式行为：用户在本页显式改过就按他的；没改过则跟随协议默认
                            forceStreaming = forceStreaming,
                            // ★ 必须把已有的自定义请求头带回去：这一页重建了整个
                            // CustomProviderConfig，漏掉 headers 会让用户在详情页
                            // 配好的头一点保存就全丢（界面与引擎当场不同步）。
                            headers = parseHeaders(headersText).headers,
                            // 未选预设且字段全空 = 不查余额，别落一条空配置
                            balanceQuery = if (
                                balancePreset.isEmpty() && balancePath.isBlank() && balanceField.isBlank()
                            ) {
                                null
                            } else {
                                buildBalanceQuery(
                                    url = balanceUrl,
                                    path = balancePath,
                                    field = balanceField,
                                    method = balanceMethod,
                                    scaleText = balanceScale,
                                    divide = balanceDivide,
                                    unit = balanceUnit,
                                    preset = balancePreset,
                                )!!
                            },
                        ),
                        // 只用第一个 Key 去拉模型（拉目录不需要多账号）；留空则用账号池里已有的
                        parsedKeys.firstOrNull(),
                    )
                }
                if (!isNew && onDelete != null) {
                    OutlineActionButton("删除该供应商") { onDelete(existing!!.key) }
                }
            }
        }
    }

    if (embedded) {
        // 外层 PageScaffold 已提供 ColumnScope，这里补一层 Column 给 lambda 当接收者
        Column(modifier = Modifier.fillMaxWidth()) { formContent() }
    } else {
        PageScaffold(
            title = if (isNew) "新建供应商" else "编辑供应商",
            subtitle = "OpenAI 兼容接口",
            leading = {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.clickable(onClick = onBack).padding(6.dp),
                )
            },
            content = formContent,
        )
    }
}

/**
 * 把 Key 输入框的内容拆成 Key 列表：容忍换行、逗号、分号（中英文都算）。
 *
 * 用户从别处复制 Key 时经常带进分隔符或空行，让他自己去删是没必要的刁难。
 */
private fun parseApiKeys(raw: String): List<String> =
    raw.split('\n', '\r', ',', '，', ';', '；')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

/** Key 只显示首尾几位，避免录屏/截图泄漏。 */
private fun maskKey(key: String): String {
    if (key.length <= 8) return "••••"
    return key.take(4) + "••••" + key.takeLast(4)
}

/**
 * 解析「一行一条 `名字: 值`」的文本。
 *
 * 分隔用**第一个冒号**：值里常带 `://` 或 `Bearer xxx:yyy`，按所有冒号切会碎掉。
 * 非法行单独返回，界面会提示「这几行不会被发送」，不静默丢弃。
 */
private data class ParsedHeaders(
    val headers: List<io.synapse.core.gateway.HttpHeader>,
    val invalid: List<String>,
)

private fun parseHeaders(raw: String): ParsedHeaders {
    val valid = mutableListOf<io.synapse.core.gateway.HttpHeader>()
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
        if (!io.synapse.core.gateway.HttpHeader.isValidName(name) ||
            !io.synapse.core.gateway.HttpHeader.isValidValue(value)
        ) {
            invalid += text
            continue
        }
        valid += io.synapse.core.gateway.HttpHeader(name, value)
    }
    return ParsedHeaders(valid, invalid)
}

/** 组装余额配置；全空返回 null（= 不查余额，别落一条空配置占地方）。 */
private fun buildBalanceQuery(
    url: String,
    path: String,
    field: String,
    method: String,
    scaleText: String,
    divide: Boolean,
    unit: String,
    preset: String,
): io.synapse.core.gateway.BalanceQueryConfig? {
    if (url.isBlank() && path.isBlank() && field.isBlank() && preset.isBlank()) return null
    return io.synapse.core.gateway.BalanceQueryConfig(
        url = url.trim(),
        path = path.trim(),
        field = field.trim().ifEmpty { "balance" },
        method = method.trim().uppercase().ifEmpty { "GET" },
        scale = scaleText.trim().toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0,
        divide = divide,
        unit = unit.trim(),
        preset = preset,
    )
}
