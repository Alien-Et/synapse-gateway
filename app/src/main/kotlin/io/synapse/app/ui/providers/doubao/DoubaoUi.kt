package io.synapse.app.ui.providers.doubao

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import io.synapse.app.ui.ChoiceChip
import io.synapse.app.ui.OutlineActionButton
import io.synapse.app.ui.SectionCard
import io.synapse.app.ui.SectionLabel
import io.synapse.app.ui.providers.ProviderUi
import io.synapse.app.ui.providers.ProviderUiActions
import io.synapse.core.gateway.ProviderSettings
import io.synapse.core.pool.AccountStatus
import io.synapse.core.provider.doubao.DoubaoProvider

/**
 * 豆包供应商的界面。
 *
 * ## 线路由用户决定，不是自动推断
 *
 * 「专家 / 极速」在协议上是两个字段（`need_deep_think` + `model_item_key`），
 * 每个请求自带，所以完全可以做成界面开关。这里做成**供应商设置**，
 * 一次选定、之后所有对话都按它走 —— 而不是让用户每次调用都改模型名。
 *
 * 为什么不放进模型名（像 Cline 的池前缀那样）：
 * 池前缀影响的是「用哪个额度」，属于模型身份；线路影响的是「用哪种推理方式」，
 * 属于用户偏好。把偏好塞进模型名会让模型列表出现一堆同义条目。
 *
 * ## 生视频已实测
 *
 * 请求侧（模型 key `seedance_v2.0_mini`、比例/时长参数、附件块 `block_type 10052`）
 * 与响应侧（`creations[].type == 2`、`video.status == 3`、`video.download_url`）
 * 均已从真实会话解出，见 docs/13.2.3。
 *
 * **唯一的限制是异步**：豆包先回「预计等待 10 分钟」，生成完再 push 结果，
 * 所以本轮对话通常拿不到视频。界面上如实说明，不让用户以为失败了。
 */
object DoubaoUi : ProviderUi {

    override val id: String = DoubaoProvider.ID

    override fun description() = "生文（专家 / 极速）+ 生图 + 生视频"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    /**
     * 登录入口：粘贴 Cookie。
     *
     * 豆包的鉴权全在 Cookie 里，没有标准的 OAuth/设备授权流程可走，
     * 所以这里就是「把抓到的 Cookie 贴进来」。
     */
    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var cookie by remember { mutableStateOf("") }
        SectionCard(enterIndex = 0) {
            SectionLabel("添加账号")
            OutlinedTextField(
                value = cookie,
                onValueChange = { cookie = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Cookie") },
                placeholder = { Text("d_ticket=...; ttwid=...; bd_sso_hi3jfd=...") },
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
                text = "豆包的鉴权全在 Cookie 里（主凭证 d_ticket）。用抓包工具取到整串 Cookie 后" +
                    "原样贴进来即可，不用手工拆字段 —— 上游以后加新 Cookie 也不会失效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            Text(
                text = "固定窗口：开启后所有对话复用同一个 conversation_id，" +
                    "上下文连续、首字更快；关掉则每轮都是新会话。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /**
     * 专属设置：线路 + 固定窗口。
     *
     * 这两项都写进 `ProviderSettings.options`（不写凭证），
     * 所以换线路不会触发重新登录，也不会被上游覆盖。
     */
    @Composable
    override fun SettingsSection(settings: ProviderSettings, onUpdate: (ProviderSettings) -> Unit) {
        val currentLine = settings.options["line"].orEmpty().ifEmpty { DoubaoProvider.LINE_FAST }
        val fixedWindow = settings.options["fixedWindow"]?.let { it != "false" } ?: true

        SectionCard {
            SectionLabel("对话线路")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceChip(
                    text = "专家模式",
                    selected = currentLine == DoubaoProvider.LINE_EXPERT,
                ) {
                    onUpdate(settings.copy(options = settings.options + ("line" to DoubaoProvider.LINE_EXPERT)))
                }
                ChoiceChip(
                    text = "极速模式",
                    selected = currentLine == DoubaoProvider.LINE_FAST,
                ) {
                    onUpdate(settings.copy(options = settings.options + ("line" to DoubaoProvider.LINE_FAST)))
                }
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
                    onCheckedChange = { checked ->
                        onUpdate(
                            settings.copy(
                                options = settings.options + ("fixedWindow" to if (checked) "true" else "false"),
                            ),
                        )
                    },
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
