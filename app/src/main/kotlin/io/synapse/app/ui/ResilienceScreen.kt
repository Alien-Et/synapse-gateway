package io.synapse.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import io.synapse.core.gateway.GatewaySettings
import io.synapse.core.failover.CircuitBreakerConfig
import io.synapse.core.failover.ConcurrencyConfig
import io.synapse.core.failover.ModelCooldownConfig
import io.synapse.core.failover.RetryConfig

/**
 * 并发与容错设置页：
 * - 并发闸门：全局/单账号在途上限 + 排队超时（新能力，作用于上游请求全过程）；
 * - 重试退避：单供应商内换号重试次数与指数退避（已有能力，此前无 UI）；
 * - 熔断器：连续失败阈值、静默时长、半开恢复成功数（已有能力，此前无 UI）。
 *
 * 全部参数即时落盘、即时生效，无需重启网关。
 */
@Composable
fun ResilienceScreen(state: AppUiState, viewModel: AppViewModel, onBack: () -> Unit) {
    var editTarget by remember { mutableStateOf<ResilienceEditTarget?>(null) }
    // 跨供应商候选配置弹窗（模型勾选 + 路由策略）
    var showCrossProviderDialog by remember { mutableStateOf(false) }

    PageScaffold(
        title = "并发与容错",
        subtitle = "并发闸门、重试退避与熔断",
        leading = { BackButton(onBack) },
    ) {
        val failover = state.failover

        ConcurrencyComponent(
            config = failover.concurrency,
            onOpenEdit = { editTarget = ResilienceEditTarget.Concurrency },
        )
        Spacer(Modifier.height(4.dp))
        // 重试与退避仍然有效（同一供应商内换号重试），
        // 但「启用故障转移」开关已删除：跨供应商转移的逻辑已不存在，
        // 留着开关只会让人以为还能切到别家。
        RetryComponent(
            config = failover.retry,
            onOpenEdit = { editTarget = ResilienceEditTarget.Retry },
        )
        Spacer(Modifier.height(4.dp))
        CircuitBreakerComponent(
            config = failover.circuitBreaker,
            onOpenEdit = { editTarget = ResilienceEditTarget.CircuitBreaker },
        )
        Spacer(Modifier.height(4.dp))
        ModelCooldownComponent(
            config = failover.modelCooldown,
            onOpenEdit = { editTarget = ResilienceEditTarget.ModelCooldown },
        )
        Spacer(Modifier.height(4.dp))
        CrossRegionComponent(
            enabled = failover.crossRegionFailover,
            onToggle = { on ->
                viewModel.updateFailoverSettings(state.failover.copy(crossRegionFailover = on))
            },
        )
        Spacer(Modifier.height(4.dp))
        CrossProviderComponent(
            config = failover.crossProvider,
            onToggle = { on ->
                viewModel.updateFailoverSettings(
                    state.failover.copy(crossProvider = failover.crossProvider.copy(enabled = on)),
                )
            },
            onOpenConfig = { showCrossProviderDialog = true },
        )

        Spacer(Modifier.height(4.dp))
        LargeBodyComponent(
            enabled = state.settings.largeBodyEnabled,
            maxBodyMb = state.settings.maxBodyMb,
            concurrency = state.settings.maxBodyConcurrency,
            onToggle = { on ->
                viewModel.updateSettings(state.settings.copy(largeBodyEnabled = on))
            },
            onLooseChange = { mb, concurrency ->
                viewModel.updateSettings(
                    state.settings.copy(maxBodyMb = mb, maxBodyConcurrency = concurrency),
                )
            },
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "全部参数保存后立即生效，无需重启网关。并发在途指「已向上游发起、尚未返回」的请求；" +
                "排队超时后该次尝试换下一个账号。请求只会发往标签指定的供应商，不会自动切到其它家。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // 跨供应商候选配置：勾模型 + 选策略
    if (showCrossProviderDialog) {
        CrossProviderDialog(
            models = state.models,
            initialCandidates = state.failover.crossProvider.candidates,
            initialStrategy = state.failover.crossProvider.strategy,
            onDismiss = { showCrossProviderDialog = false },
            onConfirm = { candidates, strategy ->
                viewModel.updateFailoverSettings(
                    state.failover.copy(
                        crossProvider = state.failover.crossProvider.copy(
                            candidates = candidates,
                            strategy = strategy,
                            // 这里**不改** enabled：跨供应商默认关闭是用户明确要求的行为，
                            // 选完候选就偷偷打开会让「关着」变成一句空话——用户以为没生效，
                            // 实际已经绕过了「写哪家发哪家」的语义。要开就自己拨开关。
                        ),
                    ),
                )
                showCrossProviderDialog = false
            },
        )
    }

    editTarget?.let { target ->
        when (target) {
            ResilienceEditTarget.Concurrency -> ConcurrencyEditDialog(
                current = state.failover.concurrency,
                onDismiss = { editTarget = null },
                onConfirm = { next ->
                    viewModel.updateFailoverSettings(state.failover.copy(concurrency = next))
                    editTarget = null
                },
            )
            ResilienceEditTarget.Retry -> RetryEditDialog(
                current = state.failover.retry,
                onDismiss = { editTarget = null },
                onConfirm = { next ->
                    viewModel.updateFailoverSettings(state.failover.copy(retry = next))
                    editTarget = null
                },
            )
            ResilienceEditTarget.CircuitBreaker -> CircuitBreakerEditDialog(
                current = state.failover.circuitBreaker,
                onDismiss = { editTarget = null },
                onConfirm = { next ->
                    viewModel.updateFailoverSettings(state.failover.copy(circuitBreaker = next))
                    editTarget = null
                },
            )
            ResilienceEditTarget.ModelCooldown -> ModelCooldownEditDialog(
                current = state.failover.modelCooldown,
                onDismiss = { editTarget = null },
                onConfirm = { next ->
                    viewModel.updateFailoverSettings(state.failover.copy(modelCooldown = next))
                    editTarget = null
                },
            )
        }
    }
}

private enum class ResilienceEditTarget { Concurrency, Retry, CircuitBreaker, ModelCooldown }

@Composable
private fun ConcurrencyComponent(config: ConcurrencyConfig, onOpenEdit: () -> Unit) {
    SectionLabel("并发闸门")
    SectionCard(enterIndex = 0) {
        ParamRow("单账号在途上限", if (config.maxPerAccount == 0) "不限" else "${config.maxPerAccount} 条")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("全局在途上限", if (config.maxGlobal == 0) "不限" else "${config.maxGlobal} 条")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("排队超时", "${config.queueTimeoutMs} 毫秒")
        Spacer(Modifier.height(4.dp))
        OutlineActionButton("修改并发参数") { onOpenEdit() }
    }
}

@Composable
private fun RetryComponent(
    config: RetryConfig,
    onOpenEdit: () -> Unit,
) {
    // 「启用故障转移」开关已删除：跨供应商转移的逻辑已不存在。
    // 这里只剩下「同一供应商内换号重试」的退避参数，那是真实生效的。
    SectionLabel("换号重试与退避（同一供应商内）")
    SectionCard(enterIndex = 1) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("最大尝试次数", "${config.maxAttempts} 次（含首次）")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("初始退避", "${config.initialBackoffMs} 毫秒")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("退避上限", "${config.maxBackoffMs} 毫秒")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("退避倍率", "×${config.backoffMultiplier}")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("随机抖动", "±${(config.jitterRatio * 100).toInt()}%")
        Spacer(Modifier.height(4.dp))
        OutlineActionButton("修改重试参数") { onOpenEdit() }
    }
}

@Composable
private fun CircuitBreakerComponent(config: CircuitBreakerConfig, onOpenEdit: () -> Unit) {
    SectionLabel("熔断器（按供应商）")
    SectionCard(enterIndex = 2) {
        ParamRow("连续失败阈值", "${config.failureThreshold} 次")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("熔断静默时长", "${config.openDurationMs / 1000} 秒")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("半开恢复成功数", "${config.halfOpenSuccessCount} 次")
        Spacer(Modifier.height(4.dp))
        OutlineActionButton("修改熔断参数") { onOpenEdit() }
    }
}

@Composable
private fun ModelCooldownComponent(config: ModelCooldownConfig, onOpenEdit: () -> Unit) {
    SectionLabel("模型级冷却")
    SectionCard(enterIndex = 3) {
        ParamRow("冷却时长", "${config.cooldownMs / 1000} 秒")
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ParamRow("冷却上限", "${config.maxCooldownMs / 1000} 秒")
        Spacer(Modifier.height(4.dp))
        OutlineActionButton("修改模型级冷却") { onOpenEdit() }
    }
}

/**
 * 跨区域开关（原名「国内外互通」）。
 *
 * 有些供应商在国内外各有一个站点（腾讯、字节），每个站点一池账号。
 * 默认**开启**：用户指定的区域账号全部不可用时，会顺延到另一个区域继续跑——
 * 它们本来就是同一家供应商、能力相同，只是站点不同。
 *
 * 关闭后回到「区域是硬约束」：国内请求只在国内账号里换号，绝不跨到国外。
 *
 * 改名的原因：「国内外互通」听起来像网络/线路层面的打通，实际做的是
 * 「故障转移时可以落到另一个区域的账号」，叫「跨区域」更贴行为本身。
 */
/**
 * 跨供应商故障转移开关（默认关闭）。
 *
 * 与前两项是**并列的第三套机制**：同供应商换号（始终生效）→ 跨区域（可关）→ 跨供应商（默认关）。
 * 只有本家所有区域、所有账号都不可用时才会走到它。
 *
 * 这个开关会直接改变「请求发往哪家」的语义，所以默认关闭，且开启后必须配置候选模型。
 */
@Composable
private fun CrossProviderComponent(
    config: io.synapse.core.failover.CrossProviderFailoverConfig,
    onToggle: (Boolean) -> Unit,
    onOpenConfig: () -> Unit,
) {
    SectionLabel("跨供应商")
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("允许换到其它供应商", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "作用：当前供应商熔断、账号全不可用或持续失败时，改发你下面勾选的候选模型，" +
                        "让请求不至于直接失败（会打破「写哪家发哪家」的预期，默认关闭）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = config.enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                config.candidates.isEmpty() -> "尚未选择候选模型（开启后也不会生效）"
                else -> "已选 ${config.candidates.size} 个候选 · 策略：${config.strategy.label}"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        OutlineActionButton(
            text = if (config.candidates.isEmpty()) "选择候选模型" else "修改候选与策略",
            onClick = onOpenConfig,
        )
    }
}

@Composable
private fun CrossRegionComponent(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    SectionLabel("跨区域")
    SectionCard(enterIndex = 4) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("故障转移可跨区域", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "作用：某区域的账号全用不了时，自动改用它另一个区域的账号继续请求，" +
                        "不中断对话（只影响有国内外两套站点的供应商）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = if (enabled) "已开启：本区域账号不可用时，会顺延到另一区域的账号" else "已关闭：只在本区域账号内换号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "仅对区分国内 / 国外的供应商有效（腾讯、字节）。" +
                "优先仍然使用你指定的区域，只有该区域一个可用账号都没有时才跨区。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LargeBodyComponent(
    enabled: Boolean,
    maxBodyMb: Int,
    concurrency: Int,
    onToggle: (Boolean) -> Unit,
    onLooseChange: (Int, Int) -> Unit,
) {
    // 输入框的原始文本单独存：用户删空的瞬间不能立刻落盘成 0，
    // 只在能解析出合法数字时回写设置（失焦/输入完成时由 clamp 兜住范围）。
    var mbText by remember(maxBodyMb) { mutableStateOf(maxBodyMb.toString()) }
    var concurrencyText by remember(concurrency) { mutableStateOf(concurrency.toString()) }

    SectionLabel("请求体大小")
    SectionCard(enterIndex = 6) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("限制请求体大小", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = if (enabled) {
                        "已开启：单条上限 ${maxBodyMb}MB，同时最多收 $concurrency 条"
                    } else {
                        "已关闭：不限大小，任意大的请求体都照收"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }

        // 只有打开开关才出现下面两项：关闭时连参数都不显示，
        // 避免「关了但看起来还能调」的误解。
        if (enabled) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            NumberField(
                label = "单条请求体上限（${GatewaySettings.MIN_MAX_BODY_MB}-${GatewaySettings.MAX_MAX_BODY_MB} MB）",
                value = mbText,
                maxLen = 3,
            ) { input ->
                mbText = input
                val parsed = input.toIntOrNull() ?: return@NumberField
                onLooseChange(GatewaySettings.clampMaxBodyMb(parsed), concurrency)
            }

            Spacer(Modifier.height(8.dp))

            NumberField(
                label = "同时接收上限（${GatewaySettings.MIN_MAX_BODY_CONCURRENCY}-${GatewaySettings.MAX_MAX_BODY_CONCURRENCY} 条）",
                value = concurrencyText,
                maxLen = 1,
            ) { input ->
                concurrencyText = input
                val parsed = input.toIntOrNull() ?: return@NumberField
                onLooseChange(maxBodyMb, GatewaySettings.clampMaxBodyConcurrency(parsed))
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = "内存峰值大约是「上限 × 同时接收数」的两三倍（要读原始字节、转字符串、再解析 JSON）。" +
                    "例如 32MB × 2 约需 200MB 堆内存；调得太高会让整个 App 被系统杀掉。" +
                    "超出同时接收数的请求会直接返回 429，不会排队干等。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "不限大小时请求体会被完整读进内存（原始字节 + 字符串 + 解析树，约 3 倍开销）。" +
                    "一条 100MB 的请求可能占掉 600MB 以上，超过单个 App 的堆上限就会崩掉整个网关，" +
                    "正在进行的对话也会中断。需要防护时打开上面的开关。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ParamRow(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ConcurrencyEditDialog(
    current: ConcurrencyConfig,
    onDismiss: () -> Unit,
    onConfirm: (ConcurrencyConfig) -> Unit,
) {
    var perAccount by remember { mutableStateOf(current.maxPerAccount.toString()) }
    var global by remember { mutableStateOf(current.maxGlobal.toString()) }
    var timeout by remember { mutableStateOf(current.queueTimeoutMs.toString()) }

    // 走统一弹窗：从前是 Material3 AlertDialog，和系统原生、自绘三种并存
    AppDialog(
        title = "修改并发参数",
        onDismiss = onDismiss,
        buttons = {
            AppDialogButton("取消", onDismiss)
            AppDialogButton("保存", onClick = {
                                // 解析失败回落当前值（与重试/熔断对话框一致），不能静默变成「0=不限」
                                onConfirm(
                                    ConcurrencyConfig(
                                        maxPerAccount = current.maxPerAccount,
                                        maxGlobal = current.maxGlobal,
                                        queueTimeoutMs = current.queueTimeoutMs,
                                    ).copy(
                                        maxPerAccount = ConcurrencyConfig.clampMaxPerAccount(perAccount.toIntOrNull() ?: current.maxPerAccount),
                                        maxGlobal = ConcurrencyConfig.clampMaxGlobal(global.toIntOrNull() ?: current.maxGlobal),
                                        queueTimeoutMs = ConcurrencyConfig.clampQueueTimeoutMs(timeout.toLongOrNull() ?: current.queueTimeoutMs),
                                    ),
                                )
            }, emphasis = true)
        },
    ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("单账号在途上限（条，0=不限）", perAccount, 2) { perAccount = it }
                    NumberField("全局在途上限（条，0=不限）", global, 3) { global = it }
                    NumberField("排队超时（毫秒）", timeout, 6) { timeout = it }
                    Text(
                        text = "风控敏感的账号建议设 1；上游稳定时可放宽。全局上限建议 ≥ 单账号上限 × 账号数。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
    }
}

@Composable
private fun RetryEditDialog(
    current: RetryConfig,
    onDismiss: () -> Unit,
    onConfirm: (RetryConfig) -> Unit,
) {
    var attempts by remember { mutableStateOf(current.maxAttempts.toString()) }
    var initial by remember { mutableStateOf(current.initialBackoffMs.toString()) }
    var maxBackoff by remember { mutableStateOf(current.maxBackoffMs.toString()) }
    var multiplier by remember { mutableStateOf(current.backoffMultiplier.toString()) }
    var jitter by remember { mutableStateOf((current.jitterRatio * 100).toInt().toString()) }

    // 走统一弹窗：从前是 Material3 AlertDialog，和系统原生、自绘三种并存
    AppDialog(
        title = "修改重试参数",
        onDismiss = onDismiss,
        buttons = {
            AppDialogButton("取消", onDismiss)
            AppDialogButton("保存", onClick = {
                                val attemptsN = (attempts.toIntOrNull() ?: current.maxAttempts).coerceIn(1, 10)
                                val initialN = (initial.toLongOrNull() ?: current.initialBackoffMs).coerceIn(0L, 10_000L)
                                val maxBackoffN = (maxBackoff.toLongOrNull() ?: current.maxBackoffMs).coerceIn(0L, 60_000L)
                                val multiplierN = (multiplier.toDoubleOrNull() ?: current.backoffMultiplier).coerceIn(1.0, 5.0)
                                val jitterN = (jitter.toIntOrNull() ?: (current.jitterRatio * 100).toInt()).coerceIn(0, 100)
                                onConfirm(
                                    RetryConfig(
                                        maxAttempts = attemptsN,
                                        initialBackoffMs = initialN,
                                        maxBackoffMs = maxBackoffN,
                                        backoffMultiplier = multiplierN,
                                        jitterRatio = jitterN / 100.0,
                                    ),
                                )
            }, emphasis = true)
        },
    ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("最大尝试次数（1-10）", attempts, 2) { attempts = it }
                    NumberField("初始退避（毫秒）", initial, 5) { initial = it }
                    NumberField("退避上限（毫秒）", maxBackoff, 6) { maxBackoff = it }
                    NumberField("退避倍率（1.0-5.0）", multiplier, 4, allowDecimal = true) { multiplier = it }
                    NumberField("随机抖动（0-100%）", jitter, 3) { jitter = it }
                }
    }
}

@Composable
private fun CircuitBreakerEditDialog(
    current: CircuitBreakerConfig,
    onDismiss: () -> Unit,
    onConfirm: (CircuitBreakerConfig) -> Unit,
) {
    var threshold by remember { mutableStateOf(current.failureThreshold.toString()) }
    var openSeconds by remember { mutableStateOf((current.openDurationMs / 1000).toString()) }
    var halfOpen by remember { mutableStateOf(current.halfOpenSuccessCount.toString()) }

    // 走统一弹窗：从前是 Material3 AlertDialog，和系统原生、自绘三种并存
    AppDialog(
        title = "修改熔断参数",
        onDismiss = onDismiss,
        buttons = {
            AppDialogButton("取消", onDismiss)
            AppDialogButton("保存", onClick = {
                                onConfirm(
                                    CircuitBreakerConfig(
                                        failureThreshold = (threshold.toIntOrNull() ?: current.failureThreshold).coerceIn(1, 100),
                                        openDurationMs = ((openSeconds.toLongOrNull() ?: (current.openDurationMs / 1000)).coerceIn(1L, 3600L)) * 1000,
                                        halfOpenSuccessCount = (halfOpen.toIntOrNull() ?: current.halfOpenSuccessCount).coerceIn(1, 10),
                                    ),
                                )
            }, emphasis = true)
        },
    ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("连续失败阈值（1-100）", threshold, 3) { threshold = it }
                    NumberField("熔断静默时长（秒，1-3600）", openSeconds, 4) { openSeconds = it }
                    NumberField("半开恢复成功数（1-10）", halfOpen, 2) { halfOpen = it }
                    Text(
                        text = "客户端 400 类错误不计入失败；熔断只统计上游侧错误。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
    }
}

@Composable
private fun ModelCooldownEditDialog(
    current: ModelCooldownConfig,
    onDismiss: () -> Unit,
    onConfirm: (ModelCooldownConfig) -> Unit,
) {
    var cooldownSeconds by remember { mutableStateOf((current.cooldownMs / 1000).toString()) }
    var maxSeconds by remember { mutableStateOf((current.maxCooldownMs / 1000).toString()) }

    // 走统一弹窗：从前是 Material3 AlertDialog，和系统原生、自绘三种并存
    AppDialog(
        title = "修改模型级冷却",
        onDismiss = onDismiss,
        buttons = {
            AppDialogButton("取消", onDismiss)
            AppDialogButton("保存", onClick = {
                                onConfirm(
                                    ModelCooldownConfig(
                                        cooldownMs = (cooldownSeconds.toLongOrNull() ?: (current.cooldownMs / 1000)).coerceIn(1L, 7200L) * 1000,
                                        maxCooldownMs = (maxSeconds.toLongOrNull() ?: (current.maxCooldownMs / 1000)).coerceIn(1L, 7200L) * 1000,
                                    ),
                                )
            }, emphasis = true)
        },
    ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField("冷却时长（秒）", cooldownSeconds, 5) { cooldownSeconds = it }
                    NumberField("冷却上限（秒）", maxSeconds, 5) { maxSeconds = it }
                    Text(
                        text = "上游报「该模型已超限」类错误时，只冷却该模型：不换账号、不计熔断，冷却期内该模型直接跳过。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    maxLen: Int,
    allowDecimal: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            // 整数字段不放行小数点：避免「1.5」这类输入被 toIntOrNull 静默丢弃后落回默认值
            val filtered = input.filter { it.isDigit() || (allowDecimal && it == '.') }
            onChange(filtered.take(maxLen))
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
