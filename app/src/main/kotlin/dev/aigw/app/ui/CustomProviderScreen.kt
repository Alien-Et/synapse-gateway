package dev.aigw.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.core.gateway.CustomProviderConfig

/**
 * 自定义供应商的新建/编辑。
 *
 * 表单按「逐条添加」组织，而不是一次性铺一堆输入框：
 * - **模型列表**：点「＋ 添加模型」一条条加，加过的列出来（可删除）；
 * - **API Key**：点「＋ 添加 Key」一条条加，每个 Key 对应一个账号。
 *
 * 为什么这样改：原先只能填一个 Key、模型要靠后面详情页再维护，
 * 新建时「到底要填到哪一步才算配好」很模糊。现在标签 + 地址填完、
 * 至少加一个模型和一个 Key，就能直接用。
 *
 * [existing] 不为空时是编辑：标签不可改（它是路由前缀，改了就断掉已有客户端配置），
 * 已存在的 Key 只展示不解密回填。
 */
@Composable
fun CustomProviderScreen(
    existing: CustomProviderConfig?,
    /** 校验标签是否合法（由核心层提供：不能与内置标签重名、不能与别家重名等）。返回 null 表示通过。 */
    validateLabel: (String) -> String?,
    onSave: (CustomProviderConfig) -> Unit,
    onDelete: ((String) -> Unit)?,
    onBack: () -> Unit,
) {
    val isNew = existing == null

    var key by remember { mutableStateOf(existing?.key.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }

    // 逐条添加的模型（新建时为空，编辑时回填已有模型）
    val models = remember { mutableStateListOf<String>().apply { addAll(existing?.models.orEmpty()) } }
    var modelDraft by remember { mutableStateOf("") }

    // 逐条添加的 API Key。编辑模式下不放回已有 Key（加密存储里只有摘要），
    // 只允许「追加」新的 Key；已有账号请在详情页查看/删除。
    val newKeys = remember { mutableStateListOf<String>() }
    var keyDraft by remember { mutableStateOf("") }

    fun addModel() {
        val m = modelDraft.trim()
        if (m.isEmpty()) return
        if (models.none { it.equals(m, ignoreCase = true) }) models.add(m)
        modelDraft = ""
    }

    fun addKey() {
        val k = keyDraft.trim()
        if (k.isEmpty()) return
        if (newKeys.none { it == k }) newKeys.add(k)
        keyDraft = ""
    }

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
    ) {
        // ---------------- 基本信息 ----------------
        SectionCard {
            SectionLabel("基本信息")
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.trim() },
                label = { Text("模型标签🏷️") },
                supportingText = { Text("调用时写成「标签/模型名」，例如 ${key.ifEmpty { "mysite" }}/gpt-4o") },
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("启用", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
        }

        // ---------------- 模型：逐条添加 ----------------
        SectionCard {
            SectionLabel("模型列表")
            Text(
                text = "一条条添加这个中转站支持的模型名，调用时就写在标签后面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = modelDraft,
                    onValueChange = { modelDraft = it.trim() },
                    label = { Text("模型名，如 gpt-4o") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { addModel() }, enabled = modelDraft.isNotBlank()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("添加")
                }
            }
            if (models.isEmpty()) {
                Text(
                    text = "还没有模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for ((index, model) in models.toList().withIndex()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "$key/$model",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "删除 $model",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { models.removeAt(index) }.padding(4.dp),
                        )
                    }
                }
            }
        }

        // ---------------- API Key：逐条添加 ----------------
        SectionCard {
            SectionLabel("API Key（账号）")
            Text(
                text = if (isNew) {
                    "一个 Key = 一个账号。可以加多个，网关会在它们之间轮换。"
                } else {
                    "已配置 ${existing?.apiKeys?.size ?: 0} 个 Key（出于安全不回显）。要增删请到供应商详情页的账号列表。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = keyDraft,
                    onValueChange = { keyDraft = it.trim() },
                    label = { Text(if (isNew) "API Key" else "追加一个 API Key") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { addKey() }, enabled = keyDraft.isNotBlank()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("添加")
                }
            }
            if (newKeys.isNotEmpty()) {
                for ((index, k) in newKeys.toList().withIndex()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = maskKey(k),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "删除这个 Key",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { newKeys.removeAt(index) }.padding(4.dp),
                        )
                    }
                }
            }
        }

        // ---------------- 保存 ----------------
        SectionCard {
            // 标签 = 路由前缀本身：空标签、与内置标签重名都会导致「看似能用实际必错」，
            // 核心层还会在写盘前再校一次。
            val keyTrimmed = key.trim()
            val nameTrimmed = name.trim()
            val baseUrlTrimmed = baseUrl.trim()
            val keyError = if (keyTrimmed.isEmpty()) "模型标签不能为空" else validateLabel(keyTrimmed)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (keyError != null) {
                    Text(keyError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (baseUrlTrimmed.isEmpty()) {
                    Text("接口地址不能为空", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                val canSave = keyError == null && baseUrlTrimmed.isNotEmpty()
                OutlineActionButton(if (canSave) "保存" else "请先补全模型标签与接口地址") {
                    if (!canSave) return@OutlineActionButton
                    // 编辑模式下 apiKeys 只追加新加的那几个；模型列表整体替换
                    val mergedKeys = existing?.apiKeys.orEmpty() + newKeys
                    onSave(
                        CustomProviderConfig(
                            key = keyTrimmed,
                            // 显示名留空时用标签兜底：界面上总得有个名字
                            name = nameTrimmed.ifEmpty { keyTrimmed },
                            baseUrl = baseUrlTrimmed,
                            apiKeys = mergedKeys,
                            models = models.toList(),
                            enabled = enabled,
                        ),
                    )
                }
                if (!isNew && onDelete != null) {
                    OutlineActionButton("删除该供应商") { onDelete(existing!!.key) }
                }
            }
        }
    }
}

/** Key 只显示首尾几位，避免录屏/截图泄漏。 */
private fun maskKey(key: String): String {
    if (key.length <= 8) return "••••"
    return key.take(4) + "••••" + key.takeLast(4)
}
