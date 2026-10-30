package io.synapse.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
) {
    val isNew = existing == null

    var key by remember { mutableStateOf(existing?.key.orEmpty()) }
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }

    // API Key：一个输入框搞定，**点保存即生效**，不需要先点「添加」再保存。
    //
    // 之前是「输入框 + 添加按钮 → 列表 → 保存」的三段式，等于把同一件事做两遍，
    // 少点一次就白填。现在一个框、一行一个 Key，保存时直接收走。
    //
    // 编辑模式不回显旧 Key（加密存储里只有摘要），留空即「不动现有账号」。
    var keyInput by remember { mutableStateOf("") }

    val parsedKeys = parseApiKeys(keyInput)

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
                            parsedKeys.isEmpty() -> "留空 = 保留现有 ${existing?.apiKeys?.size ?: 0} 个账号"
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

        // ---------------- 模型说明（不用填） ----------------
        SectionCard {
            SectionLabel("模型")
            Text(
                text = "不需要在这里填。点「保存」后会自动向上游 /v1/models 获取，" +
                    "拿到后可在供应商详情页里看到完整列表。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                OutlineActionButton(if (canSave) "保存并获取模型" else "请先补全标签、地址与 Key") {
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
