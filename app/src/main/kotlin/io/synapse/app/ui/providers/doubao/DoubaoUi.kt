@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.synapse.app.ui.providers.doubao

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import io.synapse.app.ui.ChoiceChip
import io.synapse.app.ui.OutlineActionButton
import io.synapse.app.ui.SectionCard
import io.synapse.app.ui.SectionLabel
import io.synapse.app.ui.providers.ProviderUi
import io.synapse.app.ui.providers.ProviderUiActions
import io.synapse.core.gateway.ProviderSettings
import io.synapse.core.pool.AccountStatus
import io.synapse.core.provider.doubao.DoubaoAuthClient
import io.synapse.core.provider.doubao.DoubaoMultiAccountException
import io.synapse.core.provider.doubao.DoubaoProvider

/**
 * 豆包供应商的界面。
 *
 * ## 登录双通道
 *
 * **手机号短信**是主路径（SmsLoginSupport，authKind=SMS_CODE）：UI 与讯飞（Loomy）同构，
 * code 为空先发码，有码则登录。msgid（encodedMobile）由 ViewModel 的 smsMsgIds 自动传递。
 *
 * **Cookie 粘贴**保留为备用路径：某些环境（如代理登录、设备迁移）下用户手里
 * 已经有现成 Cookie，直接贴进来最快。两条路径产出同一种凭证，互不冲突。
 *
 * ## 多账号（1454）
 *
 * 该手机号绑定了多个豆包账号时，Provider 抛 [DoubaoMultiAccountException]，
 * 引擎把它折叠成 `LoginOutcome(error="MULTI_ACCOUNT|...")`。
 * 这里解析出候选列表弹选择框，用户选谁就用 `sel:<sec_uid>` 作为 code 重登
 * （Provider 端按 `sel:` 前缀走二次 one_login，不再要求验证码）。
 */
object DoubaoUi : ProviderUi {

    override val id: String = DoubaoProvider.ID

    override fun description() = "手机号短信登录 / 生文（专家・极速）/ 生图 / 生视频"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var ccode by rememberSaveable { mutableStateOf("86") }
        var phone by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var cookie by remember { mutableStateOf("") }
        var countdown by remember { mutableStateOf(0) }
        var busy by remember { mutableStateOf(false) }
        var lastError by remember { mutableStateOf("") }
        // 多账号选择状态
        var multiCandidates by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
        var multiHint by remember { mutableStateOf("") }
        var multiPhone by remember { mutableStateOf("") }
        var multiCcode by remember { mutableStateOf("86") }
        var showMultiDialog by remember { mutableStateOf(false) }

        LaunchedEffect(countdown) {
            if (countdown > 0) {
                delay(1_000)
                countdown -= 1
            }
        }

        fun handleSmsError(raw: String) {
            val parsed = DoubaoMultiAccountException.parse(raw)
            if (parsed != null) {
                val (hint, subs) = parsed
                multiHint = hint
                multiCandidates = subs.map { it.secUid to it.name }
                multiPhone = phone.trim()
                multiCcode = ccode.trim().ifBlank { "86" }
                showMultiDialog = true
                lastError = ""
            } else {
                lastError = raw
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---------------- 短信登录（主路径） ----------------
            SectionCard(enterIndex = 0) {
                SectionLabel("手机号登录")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = ccode,
                        onValueChange = { value -> ccode = value.filter(Char::isDigit).take(4) },
                        modifier = Modifier.fillMaxWidth(0.24f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("国家码") },
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it.filter(Char::isDigit).take(15) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        label = { Text("手机号") },
                    )
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit).take(6) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("验证码（6 位）") },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            countdown = 60
                            busy = true
                            lastError = ""
                            actions.onSmsLogin(id, phone.trim(), "", ccode.trim().ifBlank { "86" }) { err ->
                                busy = false
                                if (err.isNotEmpty()) {
                                    countdown = 0
                                    handleSmsError(err)
                                }
                            }
                        },
                        enabled = phone.isNotBlank() && countdown == 0 && !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (countdown > 0) "${countdown} 秒后重发" else "发送验证码")
                    }
                    Button(
                        onClick = {
                            busy = true
                            lastError = ""
                            actions.onSmsLogin(id, phone.trim(), code.trim(), ccode.trim().ifBlank { "86" }) { err ->
                                busy = false
                                if (err.isNotEmpty()) handleSmsError(err)
                            }
                        },
                        enabled = phone.isNotBlank() && code.isNotBlank() && !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (busy) "登录中…" else "登录")
                    }
                }
                if (lastError.isNotEmpty()) {
                    Text(
                        text = lastError,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // ---------------- Cookie 导入（备用路径） ----------------
            SectionCard(enterIndex = 1) {
                SectionLabel("Cookie 导入（备用）")
                OutlinedTextField(
                    value = cookie,
                    onValueChange = { cookie = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Cookie") },
                    placeholder = { Text("d_ticket=...; ttwid=...; 整串原样粘贴") },
                    minLines = 3,
                    maxLines = 6,
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                OutlineActionButton("导入凭证") {
                    if (cookie.isNotBlank()) {
                        actions.onImport(id, cookie.trim())
                        cookie = ""
                    }
                }
                Text(
                    text = "手机号登录是主路径；Cookie 留给「手里已经有现成凭证」的场景。" +
                        "从抓包工具整串复制，不用手工拆字段。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (accounts.isNotEmpty()) {
                SectionCard {
                    SectionLabel("账号")
                    accounts.forEach { status ->
                        Text(
                            text = "${status.displayName} · ${status.uid}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        // ---------------- 多账号选择对话框 ----------------
        if (showMultiDialog && multiCandidates.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { showMultiDialog = false },
                confirmButton = {},
                title = { Text("选择要登录的账号") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "该手机号绑定了 ${multiCandidates.size} 个豆包账号：",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        multiCandidates.forEach { (secUid, name) ->
                            OutlinedButton(
                                onClick = {
                                    showMultiDialog = false
                                    val selMsgid = DoubaoAuthClient.SEL_PREFIX + secUid
                                    busy = true
                                    // code 传 sel:<sec_uid>：Provider 识别前缀后走二次 one_login（无需验证码）
                                    actions.onSmsLogin(id, multiPhone, selMsgid, multiCcode) { err ->
                                        busy = false
                                        if (err.isNotEmpty()) lastError = err
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(name)
                            }
                        }
                    }
                },
            )
        }
    }

    /** 账号详情：uid 与会话窗口状态。 */
    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        SectionCard {
            SectionLabel("账号")
            Text(
                text = "uid：${status.uid.ifEmpty { "—" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (status.detail.isNotEmpty()) {
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    /**
     * 专属设置：线路 + 固定窗口。
     *
     * 这两项都写进 `ProviderSettings.options`（不写凭证），换线路不触发重新登录。
     */
    @Composable
    override fun SettingsSection(settings: ProviderSettings, onUpdate: (ProviderSettings) -> Unit) {
        // 本地镜像状态：引擎的 providerSettings 是普通方法调用（非 Compose state），
        // 点击后只写引擎 + refresh() 不会触发本屏 recompose —— 界面会卡在旧值，
        // 表现为「点切换没反应 / 过很久才变」。这里用 remember(settings.options)
        // 镜像一份，点击先本地反转立即刷新 UI，再异步落库（对齐 CodeBuddy 的
        // RegionSelector 做法）。options 变化（引擎侧写入后 refresh 回来）也会重镜像。
        var currentLine by remember(settings.options) {
            mutableStateOf(settings.options["line"].orEmpty().ifEmpty { DoubaoProvider.LINE_FAST })
        }
        var fixedWindow by remember(settings.options) {
            mutableStateOf(settings.options["fixedWindow"]?.let { it != "false" } ?: true)
        }

        fun applyLine(line: String) {
            if (currentLine == line) return
            currentLine = line
            onUpdate(settings.copy(options = settings.options + ("line" to line)))
        }

        fun applyFixedWindow(checked: Boolean) {
            if (fixedWindow == checked) return
            fixedWindow = checked
            onUpdate(
                settings.copy(
                    options = settings.options + ("fixedWindow" to if (checked) "true" else "false"),
                ),
            )
        }

        SectionCard {
            SectionLabel("对话线路")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceChip(
                    text = "专家模式",
                    selected = currentLine == DoubaoProvider.LINE_EXPERT,
                ) { applyLine(DoubaoProvider.LINE_EXPERT) }
                ChoiceChip(
                    text = "极速模式",
                    selected = currentLine == DoubaoProvider.LINE_FAST,
                ) { applyLine(DoubaoProvider.LINE_FAST) }
            }
            Text(
                text = if (currentLine == DoubaoProvider.LINE_EXPERT) {
                    "专家模式：深度思考作答（need_deep_think=3），更准但更慢，会显示思考过程。"
                } else {
                    "极速模式：快速直答（need_deep_think=0），更快，不产出思考过程。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard {
            SectionLabel("会话")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("固定在一个对话窗口", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = "复用同一个 conversation_id，上下文连续、首字更快",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = fixedWindow,
                    onCheckedChange = { checked -> applyFixedWindow(checked) },
                )
            }
        }

        SectionCard {
            SectionLabel("生图 / 生视频")
            Text(
                text = "模型名里带 seedream 走生图、带 seedance 走生视频。\n" +
                    "生图结果以 Markdown 图片链接出现；生视频结果以视频链接 + 封面出现。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "⏳ 视频是异步生成的：豆包会先回「预计等待 10 分钟」，生成完再推送结果。" +
                    "所以这一轮对话里通常拿不到视频，属正常现象，不是失败。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
