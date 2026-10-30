package io.synapse.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString

/** 接入设置：客户端要用的地址、端口与网关鉴权 Key。 */
@Composable
fun ApiSettingsScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    val settings = state.settings
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }
    var apiKey by remember(settings.apiKey) { mutableStateOf(settings.apiKey) }

    PageScaffold(
        title = "接入设置",
        subtitle = "端口与 API Key",
        leading = { BackButton(onBack) },
    ) {
        SectionCard(enterIndex = 0) {
            SectionLabel("接入地址")
            AddressTile(state.localUrl.ifEmpty { "http://127.0.0.1:${settings.port}/v1" }) {
                clipboard.setText(AnnotatedString(state.localUrl))
                SystemPrompt.toast("已复制本机地址")
            }
            state.lanUrls.forEach { url ->
                AddressTile(url) {
                    clipboard.setText(AnnotatedString(url))
                    SystemPrompt.toast("已复制局域网地址")
                }
            }
        }

        SectionCard(enterIndex = 1) {
            SectionLabel("端口")
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text("监听端口") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("同时监听局域网", modifier = Modifier.weight(1f))
                Switch(
                    checked = settings.exposeLan,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(exposeLan = it)) },
                )
            }
        }

        SectionCard(enterIndex = 2) {
            SectionLabel("API Key")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("无 Key 调用")
                    Text(
                        if (settings.allowNoKey) "客户端不必填 Key，任何设备都能直接调用" else "客户端必须带上下面的 Key",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.allowNoKey,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(allowNoKey = it)) },
                )
            }
            // 允许无 Key 调用时，Key 填了也不会被校验 —— 与其留一个误导人的输入框，
            // 不如直接藏起来；关掉开关再显示。
            if (!settings.allowNoKey) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("网关鉴权 Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "留空则任何 Key 都能通过校验，建议填写一个",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(enterIndex = 3) {
            OutlineActionButton("保存") {
                val parsed = port.toIntOrNull()
                if (parsed == null || parsed !in 1024..65535) {
                    viewModel.notice("端口需要在 1024~65535 之间")
                } else {
                    viewModel.updateSettings(settings.copy(port = parsed, apiKey = apiKey.trim()))
                    viewModel.notice("已保存")
                }
            }
        }
    }
}
