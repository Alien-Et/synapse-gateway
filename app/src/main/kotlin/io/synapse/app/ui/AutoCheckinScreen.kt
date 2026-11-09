package io.synapse.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.synapse.core.gateway.AutoCheckinSettings

/**
 * 自动签到设置页。
 *
 * ## 为什么两个开关默认是关的
 *
 * 本项目最硬的一条纪律是「不替用户做决定」。按天主动向上游发起签到属于同一类事 ——
 * 没打招呼就替他去签到，和他自己点「一键签到」是两回事，所以默认关、由用户自己打开。
 *
 * ## 两条触发方式为什么共用一次执行
 *
 * 「当天第一次打开 App」与「挂后台到点」是两个入口，但**同一天只跑一次**。
 * 界面上要把这句话说清楚，否则用户会以为打开两次 App 就签到两次。
 */
@Composable
fun AutoCheckinScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    var editingSchedule by remember { mutableStateOf(false) }
    val cfg = state.autoCheckin

    PageScaffold(
        title = "自动签到",
        subtitle = "启动时与到点自动签到",
        leading = { BackButton(onBack) },
    ) {
        SectionCard(enterIndex = 0) {
            SectionLabel("触发方式")
            AutoCheckinSwitchRow(
                title = "当天第一次打开 App 时",
                subtitle = "打开 App 就补一次，每天最多一次",
                checked = cfg.onLaunchEnabled,
                onCheckedChange = { viewModel.setAutoCheckinOnLaunch(it) },
            )
            AutoCheckinSwitchRow(
                title = "App 挂后台到点时",
                subtitle = "需要网关处于运行中；进程被杀或手机重启后不会触发",
                checked = cfg.scheduledEnabled,
                onCheckedChange = { viewModel.setAutoCheckinScheduled(it) },
            )
            Text(
                text = "两条触发方式共用同一个「今天已跑过」记录，同一天不会被签两次。" +
                    "每次签到成功后会自动刷新一次额度。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(enterIndex = 1) {
            SectionLabel("定时时刻")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = cfg.timeLabel(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "默认 00:00 · 抖动 ${cfg.jitterMinutes} 分钟",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { editingSchedule = true }) { Text("修改") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = "实际执行落在「${cfg.timeLabel()} 之后的 0–${cfg.jitterMinutes} 分钟内」的某一刻，" +
                    "当天固定不变。保留这段随机是为了避开整点 —— 每天准点批量签到是最容易被" +
                    "识别的机器特征。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(enterIndex = 2) {
            SectionLabel("登录新账号后")
            AutoCheckinSwitchRow(
                title = "自动做任务",
                subtitle = "默认关。任务要模拟真实对话埋点，风控等级明显高于签到",
                checked = cfg.autoCompleteTasksOnLogin,
                onCheckedChange = { viewModel.setAutoCheckinAutoTasks(it) },
            )
            Text(
                text = "这个开关关着时：登录后照常补一次签到，并**检查**任务列表，还有没做完的就提示你，" +
                    "不替你动手。打开后才会自动把任务一次做完。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionCard(enterIndex = 3) {
            SectionLabel("关于自动签到")
            Text(
                text = "· 只作用于「声明支持签到」且**当前可用**的账号：手动停用的号不会被绕过去签到。\n" +
                    "· 国内／国外两个站点的账号各自签各自的，与手动「一键签到」完全同一套动作。\n" +
                    "· 提醒与手动签到一致：成功一条轻提示，失败会列出是哪个账号、什么原因。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (editingSchedule) {
        ScheduleEditDialog(
            current = cfg,
            onDismiss = { editingSchedule = false },
            onConfirm = { hour, minute, jitter ->
                viewModel.setAutoCheckinSchedule(hour, minute, jitter)
                editingSchedule = false
            },
        )
    }
}

/** 一行开关。与「我的」页的 EditorialSwitchRow 同一版式，但这里是公开页面内的私有实现。 */
@Composable
private fun AutoCheckinSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 修改定时时刻与抖动。
 *
 * 输入用「时 / 分 / 抖动」三个数字框而不是系统 TimePicker：项目里所有数值编辑
 * （限速、熔断、重试）都用同一种弹窗，保持一致比多一个控件类型更重要。
 */
@Composable
private fun ScheduleEditDialog(
    current: AutoCheckinSettings,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int, jitterMinutes: Int) -> Unit,
) {
    var hourText by remember { mutableStateOf(current.hour.toString()) }
    var minuteText by remember { mutableStateOf(current.minute.toString()) }
    var jitterText by remember { mutableStateOf(current.jitterMinutes.toString()) }

    AppDialog(
        title = "修改自动签到时刻",
        onDismiss = onDismiss,
        buttons = {
            AppDialogButton("取消", onDismiss)
            AppDialogButton(
                "保存",
                onClick = {
                    val hour = (hourText.toIntOrNull() ?: current.hour).coerceIn(0, 23)
                    val minute = (minuteText.toIntOrNull() ?: current.minute).coerceIn(0, 59)
                    val jitter = (jitterText.toIntOrNull() ?: current.jitterMinutes)
                        .coerceIn(0, AutoCheckinSettings.MAX_JITTER_MINUTES)
                    onConfirm(hour, minute, jitter)
                },
                emphasis = true,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = hourText,
                    onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
                    label = { Text("时") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = minuteText,
                    onValueChange = { minuteText = it.filter(Char::isDigit).take(2) },
                    label = { Text("分") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = jitterText,
                onValueChange = { jitterText = it.filter(Char::isDigit).take(3) },
                label = { Text("抖动（分钟，0 表示整点）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "抖动上限 ${AutoCheckinSettings.MAX_JITTER_MINUTES} 分钟。建议保留 30 —— " +
                    "整点执行最容易和人工操作区分开。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}
