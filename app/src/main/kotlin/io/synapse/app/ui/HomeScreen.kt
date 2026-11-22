package io.synapse.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.hazeEffect
import io.synapse.app.ui.theme.DeepInk
import io.synapse.app.ui.theme.LocalAppColors
import io.synapse.app.ui.theme.WarningAmber
import io.synapse.core.provider.RoutedModel
import io.synapse.core.usage.CallRecord
import io.synapse.core.usage.CreditReport
import io.synapse.core.usage.CallStatus
import io.synapse.core.usage.UsageStats
import io.synapse.core.util.startOfDay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 趋势图展示天数。 */
private const val TREND_DAYS = 14

/** 拓扑里统一的正文字号：供应商竖排名与账号名都用它，免得同一张图出现五六种字号。 */
private val TEXT_BODY = 11.sp
/** 拓扑里统一的小字号（状态胶囊）。 */
private val TEXT_TINY = 9.sp

/**
 * 在途链路上那列光点的拖尾：每一段是「相对头部的相位偏移 to 亮度权重」。
 *
 * 头在最前（偏移 0、最亮），后面几段依次往后、变淡变小，
 * 连起来就是一道朝着账号方向流的能量尾迹。
 */
private val FLOW_TAILS = listOf(
    0f to 1.0f,
    -0.09f to 0.62f,
    -0.18f to 0.34f,
    -0.27f to 0.16f,
)

/** 拓扑画布的缩放范围：缩到 0.55 一屏看全，放到 2.2 看清单个账号。 */
private const val MIN_ZOOM = 0.55f
private const val MAX_ZOOM = 2.2f

/** 首页：数据仪表盘（网关控制台 + 消耗趋势 + 遥测指标 + 快捷操作 + 配额池）。 */
@Composable
fun HomeScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    openTaskCenter: () -> Unit,
    onOpenApiSettings: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val gold = LocalAppColors.current.goldStart
    val goldText = LocalAppColors.current.goldText
    val lanUrl = state.lanUrls.firstOrNull()

    // Android 13+ 通知权限被拒时前台服务照常跑，但常驻通知会被系统静默隐藏，
    // 用户看起来就是「服务没起来」；启动前先补上授权请求。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.startGateway() }

    fun startWithNotificationPermission() {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.startGateway()
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    PageScaffold(
        title = "智能中枢",
        subtitle = "本地 OpenAI 兼容网关",
        actions = { StatusChip(state.running) },
    ) {
        ConsoleCard(
            state = state,
            lanUrl = lanUrl,
            onStart = { startWithNotificationPermission() },
            onStop = { viewModel.stopGateway() },
            onRefreshCredits = { viewModel.refreshAllCredits() },
            onCopy = { text, message ->
                clipboard.setText(AnnotatedString(text))
                SystemPrompt.toast(message)
            },
            onOpenApiSettings = onOpenApiSettings,
            onCheckInAll = { viewModel.checkinAll() },
            onOpenTaskCenter = openTaskCenter,
        )

        LiveRoutingTopologyCard(state, Modifier.staggeredAppear(1))

        TelemetryGrid(state, Modifier.staggeredAppear(2))

        TrendCard(state)

        // 「批量签到 / 任务中心」已并入顶部状态卡的四个小按钮区，不再单占一张卡。

        AntigravityQuotaSection(state, viewModel, baseIndex = 5)
    }
}

/** 控制台卡：状态行 + 接入地址 + 启停与刷额度。 */
@Composable
private fun ConsoleCard(
    state: AppUiState,
    lanUrl: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRefreshCredits: () -> Unit,
    onCopy: (String, String) -> Unit,
    onOpenApiSettings: () -> Unit,
    onCheckInAll: () -> Unit,
    onOpenTaskCenter: () -> Unit,
) {
    val gold = LocalAppColors.current.goldStart
    SectionCard(modifier = Modifier.staggeredAppear(0)) {
        SectionHeader("网关状态 · CORE CONSOLE")
        // 状态收成一行：圆点 + 状态 + 端口 + 可达范围。
        // 原本这里是「SERVICE ACTIVE 大字 + LISTEN PORT 两行」，和顶栏状态胶囊、
        // 拓扑卡表头三处都在说「运行中」，信息重复且字号层次太重。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            StatusDot(state.running)
            Text(
                text = if (state.running) "运行中" else "已停止",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = ":${state.port}",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = when {
                    !state.running -> "服务未启动"
                    state.settings.exposeLan && lanUrl != null -> "本机 + 局域网可访问"
                    !state.settings.exposeLan -> "仅本机可访问"
                    else -> "本机可访问 · 未连 Wi-Fi"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        val localUrl = state.localUrl.ifEmpty { "http://127.0.0.1:${state.port}/v1" }
        AddressRow("本机", localUrl, onCopy = { onCopy(localUrl, "已复制本机地址") })
        // 局域网有两种「不可用」：开关没开 / 开了但没连 Wi-Fi。
        // 前者是用户能自己解决的设置问题，必须给出「去哪开」的指引并支持点击直达；
        // 后者是网络环境问题，照实说未连 Wi-Fi 就行。混成一句会让人白等。
        val lanExposed = state.settings.exposeLan
        AddressRow(
            label = "局域网",
            value = when {
                !lanExposed -> "监听未开启 · 点此到设置打开"
                lanUrl == null -> "未连接 Wi-Fi（局域网不可用）"
                else -> lanUrl
            },
            onCopy = lanUrl?.let { url -> { onCopy(url, "已复制局域网地址") } },
            onClick = if (!lanExposed) onOpenApiSettings else null,
            valueTint = if (!lanExposed) MaterialTheme.colorScheme.primary else null,
        )

        // 四个小按钮一行：服务启停 / 刷新额度 / 批量签到 / 任务中心。
        // 原本「快捷操作」是首页底部一张独立卡，合并进来后少一个区块；
        // 按钮采用统一的玻璃小胶囊，与整站 UI 语言一致。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            GlassAction(
                label = if (state.running) "停止" else "启动",
                primary = true,
                modifier = Modifier.weight(1f),
                onClick = { if (state.running) onStop() else onStart() },
            )
            GlassAction("刷额度", modifier = Modifier.weight(1f), onClick = onRefreshCredits)
            GlassAction("签到", modifier = Modifier.weight(1f), onClick = onCheckInAll)
            GlassAction("任务", modifier = Modifier.weight(1f), onClick = onOpenTaskCenter)
        }
    }
}

/** 玻璃小按钮：统一大圆角胶囊，主按钮淡金底、次按钮灰白描边。 */
@Composable
private fun GlassAction(
    label: String,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val gold = LocalAppColors.current.goldStart
    val goldText = LocalAppColors.current.goldText
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(
                if (primary) gold.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            )
            .border(
                1.dp,
                if (primary) gold.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                MaterialTheme.shapes.extraSmall,
            )
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) goldText else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * 块 2：最近一个请求的路由流水。
 * 三行流水（小圆点 + 文案）：
 * 1. 客户端请求 <model>
 * 2. 路由到 <PROVIDER·大写> · <accountNickname 或 accountUid>
 * 3. 结果 <成功/失败/中断> · HTTP <httpStatus> · 耗时 <x.x s> · tokens <in>→<out>
 */
@Composable
internal fun RecentCallRoutingFlow(
    call: CallRecord?,
    providerLabel: (String) -> String = { it },
    /**
     * 账号展示名解析。
     *
     * 默认用记录里的历史快照，但首页会传一个「查当前账号」的实现——
     * 账号改名后历史记录里的 `accountNickname` 不会跟着变，
     * 直接显示快照就会出现「改了名这里还是老名字」。
     */
    accountLabel: (CallRecord) -> String = {
        it.accountNickname.ifEmpty { it.accountUid.ifEmpty { "默认账号" } }
    },
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "最近路由记录 · LATEST DISPATCH",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (call == null) {
            Text(
                text = "暂无请求记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            return@Column
        }

        val dotNeutral = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        val resultColor = when (call.status) {
            CallStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
            CallStatus.FAILED -> MaterialTheme.colorScheme.error
            CallStatus.ABORTED -> WarningAmber
        }
        val resultText = when (call.status) {
            CallStatus.SUCCESS -> "成功"
            CallStatus.FAILED -> "失败"
            CallStatus.ABORTED -> "客户端中断"
        }

        val modelName = call.model.ifEmpty { "未记录" }
        val accountDisplay = accountLabel(call)
        val durationText = formatDuration(call.durationMillis)
        val promptTokensStr = formatTokens(call.promptTokens)
        val completionTokensStr = formatTokens(call.completionTokens)

        // 行 1：客户端请求
        RoutingFlowRow(
            dotColor = dotNeutral,
            label = "客户端请求",
            detail = modelName,
        )

        // 行 2：路由去向
        RoutingFlowRow(
            dotColor = LocalAppColors.current.goldStart,
            label = "路由到",
            detail = "${providerLabel(call.providerId)} · $accountDisplay",
        )

        // 行 3：结果与耗时
        RoutingFlowRow(
            dotColor = resultColor,
            label = "结果",
            detail = "$resultText · HTTP ${call.httpStatus} · 耗时 $durationText",
        )

        // 行 4：开销 tokens
        val tokenDetail = buildString {
            append("输入 $promptTokensStr · 输出 $completionTokensStr")
            if (call.cachedTokens > 0) {
                append(" · 缓存 ${formatTokens(call.cachedTokens)}")
            }
        }
        RoutingFlowRow(
            dotColor = dotNeutral,
            label = "Token",
            detail = tokenDetail,
        )
    }
}

@Composable
private fun RoutingFlowRow(dotColor: Color, label: String, detail: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 单个模型的近 50 次请求聚合统计。 */
private data class ModelTokenAggregation(
    val model: String,
    val requests: Int,
    val promptTokens: Long,
)

/**
 * 在 state.models 里匹配 contextWindow：
 * 1. 用 providerId + model (完全匹配 ProviderModel.id 或 name，并支持剥离 routePrefix)；
 * 2. 退化为任意 provider 下匹配模型名；
 * 3. 0 或未匹配返回 0L (表示未知)。
 */
private fun resolveContextWindow(
    providerId: String,
    modelName: String,
    models: List<RoutedModel>,
): Long {
    if (modelName.isEmpty() || models.isEmpty()) return 0L
    val cleanName = modelName.substringAfterLast('/')

    // 1. 精确匹配同 providerId
    val matched = models.firstOrNull { routed ->
        routed.providerId.equals(providerId, ignoreCase = true) &&
            (routed.model.id.equals(modelName, ignoreCase = true) ||
                routed.model.id.equals(cleanName, ignoreCase = true) ||
                routed.model.name.equals(cleanName, ignoreCase = true) ||
                routed.fullId.equals(modelName, ignoreCase = true))
    }
    if (matched != null && matched.model.contextWindow > 0L) {
        return matched.model.contextWindow
    }

    // 2. 任意 provider 跨域同名匹配
    val fallback = models.firstOrNull { routed ->
        routed.model.id.equals(cleanName, ignoreCase = true) ||
            routed.model.name.equals(cleanName, ignoreCase = true)
    }
    if (fallback != null && fallback.model.contextWindow > 0L) {
        return fallback.model.contextWindow
    }
    return 0L
}

/**
 * 块 3 与 块 4：上下文容量监控 + 明细列表（近 50 次请求按模型聚合）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContextCapacityAndModelBreakdown(state: AppUiState) {
    val recentCalls = remember(state.calls) { state.calls.take(50) }
    val latestCall = recentCalls.firstOrNull()

    // 块 3：大数字计算
    val promptTokens = latestCall?.promptTokens ?: 0L
    val cachedTokens = latestCall?.cachedTokens ?: 0L
    val contextWindow = remember(latestCall, state.models) {
        if (latestCall != null) {
            resolveContextWindow(latestCall.providerId, latestCall.model, state.models)
        } else {
            0L
        }
    }

    // 近 50 次聚合列表（按 promptTokens 倒序）
    val aggregated = remember(recentCalls) {
        recentCalls
            .groupBy { it.model.ifEmpty { "未命名模型" } }
            .map { (model, calls) ->
                ModelTokenAggregation(
                    model = model,
                    requests = calls.size,
                    promptTokens = calls.sumOf { it.promptTokens },
                )
            }
            .sortedByDescending { it.promptTokens }
    }
    val totalPromptTokens = remember(aggregated) { aggregated.sumOf { it.promptTokens } }

    val palette = LocalAppColors.current.providerPalette
    var expanded by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 标题说明
        Text(
            text = "上下文容量与分布 · CONTEXT & MODELS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 块 3：左右双指标
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左列：输入 tokens，下方整合成一行小字说明上限与剩余
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (latestCall != null) formatTokens(promptTokens) else "—",
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                val remainingText = if (contextWindow > 0L && contextWindow >= promptTokens) {
                    "剩余 ${formatTokens(contextWindow - promptTokens)}"
                } else if (contextWindow > 0L) {
                    "超出上限"
                } else {
                    "剩余未知"
                }
                val limitText = if (contextWindow > 0L) "上限 ${formatTokens(contextWindow)}" else "上限未知"
                Text(
                    text = "$limitText · $remainingText",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 右列：缓存命中率
            Column(
                horizontalAlignment = Alignment.End,
            ) {
                val hitRateText = if (promptTokens > 0L) {
                    String.format(Locale.US, "%.1f%%", (cachedTokens.toDouble() / promptTokens) * 100.0)
                } else {
                    "—"
                }
                Text(
                    text = hitRateText,
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
                    fontWeight = FontWeight.Bold,
                    color = if (cachedTokens > 0L) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "缓存命中",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 分段条标题：严格口径
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "近 50 次请求的输入 token 按模型",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (totalPromptTokens > 0L) formatTokens(totalPromptTokens) else "暂无 token 数据",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 分段进度条与图例：无数据不画条；只有 1 个模型时省去分段条与图例（直接看下方明细，消除重复冗余）；≥2 个模型时才画条和图例
        if (totalPromptTokens > 0L && aggregated.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                aggregated.forEachIndexed { index, item ->
                    val fraction = item.promptTokens.toFloat() / totalPromptTokens
                    val animatedFraction by animateFloatAsState(
                        targetValue = fraction,
                        animationSpec = tween(Motion.BAR_FILL_MILLIS, easing = FastOutSlowInEasing),
                        label = "modelTokenBar$index",
                    )
                    val color = palette[index % palette.size]
                    Box(
                        modifier = Modifier
                            .weight(animatedFraction.coerceAtLeast(0.001f))
                            .fillMaxSize()
                            .background(color),
                    )
                }
            }

            // 图例一览（FlowRow 自适应换行，每项完整展示）
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                aggregated.take(4).forEachIndexed { index, item ->
                    val color = palette[index % palette.size]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                        Text(
                            text = "${item.model.substringAfterLast('/')} (${formatTokens(item.promptTokens)})",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                if (aggregated.size > 4) {
                    Text(
                        text = "+${aggregated.size - 4}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (totalPromptTokens <= 0L) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
        }

        // 块 4：明细列表（可折叠）
        if (aggregated.isNotEmpty()) {
            val visibleCount = if (expanded) aggregated.size else 5
            val displayItems = aggregated.take(visibleCount)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                displayItems.forEachIndexed { index, item ->
                    val color = palette[index % palette.size]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                        Text(
                            text = item.model,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${item.requests} 次",
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatTokens(item.promptTokens),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // 展开/收起按钮
                if (aggregated.size > 5) {
                    Text(
                        text = if (expanded) "收起" else "显示全部 ${aggregated.size} 项",
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAppColors.current.goldText,
                        modifier = Modifier
                            .clickable { expanded = !expanded }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** 状态圆点：运行中主题金带浅光环（与顶栏 StatusChip 同一套语义色），停止灰色。 */
@Composable
private fun StatusDot(running: Boolean) {
    val gold = LocalAppColors.current.goldStart
    Box(contentAlignment = Alignment.Center) {
        if (running) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(gold.copy(alpha = 0.18f)))
        }
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (running) gold else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
        )
    }
}

/** 一行接入地址：浅灰底 8dp 圆角 + 等宽地址 + 黑色复制小块。 */
@Composable
private fun AddressRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)?,
    /** 整行可点击（如「局域网监听未开启」时点击直达设置页）；为空表示纯展示。 */
    onClick: (() -> Unit)? = null,
    /** 值文本的自定义颜色；null 用默认前景色。 */
    valueTint: Color? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(40.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = valueTint ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onCopy != null) {
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.onSurface)
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    "复制",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.surface,
                )
            }
        }
    }
}

/** 消耗趋势卡：近 14 天输入/输出 tokens 折线。 */
@Composable
private fun TrendCard(state: AppUiState) {
    val trend = remember(state.daily) { state.daily.takeLast(TREND_DAYS) }
    val drawable = remember(trend) {
        trend.size >= 2 && trend.any { it.promptTokens > 0 || it.completionTokens > 0 }
    }
    val gold = LocalAppColors.current.goldStart
    SectionCard(modifier = Modifier.staggeredAppear(3)) {
        SectionHeader("消耗趋势 · TREND", action = if (trend.isEmpty()) null else "近 ${trend.size} 天")
        if (drawable) {
            TrendChart(trend, startOfDay(System.currentTimeMillis()))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                LegendDot(gold, "输入")
                LegendDot(DeepInk, "输出")
            }
        } else {
            Text(
                text = "暂无足够历史，累积几天后显示趋势",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp),
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrendChart(daily: List<UsageStats>, todayStart: Long) {
    val gold = LocalAppColors.current.goldStart
    val gridColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val n = daily.size
    val maxValue = remember(daily) {
        daily.maxOf { maxOf(it.promptTokens, it.completionTokens) }.coerceAtLeast(1L)
    }
    val labels = remember(daily, todayStart) {
        List(n) { index -> dayLabel(todayStart - (n - 1 - index) * 86_400_000L) }
    }

    // 复用同一支画笔：绘制阶段只改对齐方式，不再每次重绘都新建
    val axisPaint = remember { android.graphics.Paint() }
    val inFractions = remember(daily, maxValue) {
        FloatArray(n) { daily[it].promptTokens.toFloat() / maxValue }
    }
    val outFractions = remember(daily, maxValue) {
        FloatArray(n) { daily[it].completionTokens.toFloat() / maxValue }
    }
    val xs = remember(n) { FloatArray(n) }
    val inY = remember(n) { FloatArray(n) }
    val outY = remember(n) { FloatArray(n) }
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val gutterLeft = 40.dp.toPx()
        val gutterBottom = 22.dp.toPx()
        val padTop = 6.dp.toPx()
        val padRight = 6.dp.toPx()
        val chartWidth = (size.width - gutterLeft - padRight).coerceAtLeast(1f)
        val chartHeight = (size.height - gutterBottom - padTop).coerceAtLeast(1f)
        val originY = padTop + chartHeight
        val stepX = chartWidth / (n - 1)
        for (i in 0 until n) {
            xs[i] = gutterLeft + i * stepX
            inY[i] = originY - inFractions[i] * chartHeight
            outY[i] = originY - outFractions[i] * chartHeight
        }

        drawLine(gridColor, Offset(gutterLeft, originY), Offset(gutterLeft + chartWidth, originY), strokeWidth = 1f)
        drawSeries(xs, inY, originY, gold)
        drawSeries(xs, outY, originY, DeepInk)

        drawIntoCanvas { canvas ->
            axisPaint.isAntiAlias = true
            axisPaint.color = labelColor.toArgb()
            axisPaint.textSize = 9.sp.toPx()
            axisPaint.textAlign = android.graphics.Paint.Align.RIGHT
            canvas.nativeCanvas.drawText(formatAxis(maxValue), gutterLeft - 6.dp.toPx(), originY, axisPaint)
            axisPaint.textAlign = android.graphics.Paint.Align.CENTER
            val labelY = originY + 14.dp.toPx()
            for (index in listOf(0, (n - 1) / 2, n - 1).distinct()) {
                canvas.nativeCanvas.drawText(labels[index], xs[index], labelY, axisPaint)
            }
        }
    }
}

/** 一条序列：先画半透明填充，再画主线。 */
private fun DrawScope.drawSeries(xs: FloatArray, ys: FloatArray, baseline: Float, color: Color) {
    if (xs.size < 2) return
    val fillPath = Path().apply {
        moveTo(xs[0], baseline)
        for (i in xs.indices) lineTo(xs[i], ys[i])
        lineTo(xs.last(), baseline)
        close()
    }
    drawPath(fillPath, color = color.copy(alpha = 0.12f))
    val linePath = Path().apply {
        moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) lineTo(xs[i], ys[i])
    }
    drawPath(
        linePath,
        color = color,
        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * 遥测指标网格：**两列**（原来是三列）。
 *
 * 改两列的原因：三列时每张卡只有 ~1/3 屏宽，
 * 「今日 TOKENS」和「已知额度」这种要显示「12.3万 · 输入 8.1万 / 输出 4.2万」的长内容
 * 必然被 ellipsis 截断，用户看不到关键数字。
 * 两列后每张卡宽了近一半，这两个数字终于能完整露出来。
 *
 * 布局：
 * ```
 * 供应商      账号就绪
 * 模型        今日请求
 * 今日TOKENS  ← 独占一行（横排：大数字在左，输入/输出在右）
 * 已知额度    ← 独占一行（横排：大数字在左，换算说明在右）
 * ```
 * 后两张是这次重点，所以让它们横跨整行、内容左右分布，而不是挤在窄卡里。
 */
@Composable
private fun TelemetryGrid(state: AppUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProvidersTile(state, Modifier.weight(1f))
            ReadinessTile(state, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModelsTile(state, Modifier.weight(1f))
            RequestsTile(state, Modifier.weight(1f))
        }
        // 下面两张独占整行：内容长，需要横向铺开
        TodayTokensTile(state, Modifier.fillMaxWidth())
        CreditsTile(state, Modifier.fillMaxWidth())
    }
}

@Composable
private fun Tile(modifier: Modifier, label: String, content: @Composable ColumnScope.() -> Unit) {
    val hazeState = LocalHazeState.current
    val tint = LocalAppColors.current.goldStart
    val shape = MaterialTheme.shapes.large
    val glass = remember(hazeState, tint) {
        if (hazeState != null) Modifier.hazeEffect(hazeState, style = glassStyle(tint)) else Modifier
    }
    Column(
        modifier = modifier
            .height(112.dp)
            .then(glass)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 0.8.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        content()
    }
}

/** 大号细体数字 + 小号后缀。 */
@Composable
private fun BigNumber(
    value: String,
    suffix: String? = null,
    color: Color = Color.Unspecified,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Light,
            color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            // 让它能在 Row 里按权重收缩：不加 weight 时 Text 会按内容撑满，
            // 右侧的说明文字就被挤出屏幕（这正是之前「位置不够」的直接原因）。
            modifier = Modifier.weight(1f, fill = false),
        )
        if (suffix != null) {
            Text(
                text = suffix,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

/** 指标卡底部灰色小备注。 */
@Composable
private fun TileNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ProvidersTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "供应商 · PROVIDERS") {
        BigNumber(state.providers.size.toString())
        TileNote(
            if (state.providers.isEmpty()) "暂无供应商" else state.providers.joinToString(" · ") { it.displayName },
        )
    }
}

@Composable
private fun ReadinessTile(state: AppUiState, modifier: Modifier) {
    val total = state.pool.total
    val usable = state.pool.usable
    val percent = if (total > 0) (usable.toFloat() / total * 100).toInt() else 0
    Tile(modifier, "账号就绪 · READINESS") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BigNumber(usable.toString(), suffix = "/ $total")
                TileNote("池内可用账号")
            }
            ReadinessRing(percent, if (total > 0) usable.toFloat() / total else 0f)
        }
    }
}

/** 环形进度：金色弧 + 灰底环，中间百分比。 */
@Composable
private fun ReadinessRing(percent: Int, fraction: Float) {
    val ringColor = LocalAppColors.current.goldStart
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val strokeWidth = 4.dp.toPx()
            val diameter = size.minDimension - strokeWidth
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = Size(diameter, diameter),
                style = Stroke(strokeWidth),
            )
            if (fraction > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = Size(diameter, diameter),
                    style = Stroke(strokeWidth, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ModelsTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "聚合模型 · MODELS") {
        BigNumber(state.models.size.toString())
        TileNote(
            when {
                state.modelsError.isNotEmpty() -> state.modelsError
                state.models.isEmpty() -> "尚未拉取模型目录"
                else -> "各供应商模型目录合计"
            },
        )
    }
}

@Composable
private fun RequestsTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "今日请求 · REQUESTS") {
        BigNumber(formatGroupedCount(state.today.requests))
        // 增长需要真实昨日数据：daily 末尾是今天，倒数第二个才是昨天。
        val yesterday = if (state.daily.size >= 2) state.daily[state.daily.size - 2] else null
        if (yesterday != null && yesterday.requests > 0) {
            val delta = (state.today.requests - yesterday.requests).toDouble() / yesterday.requests * 100
            Text(
                text = (if (delta >= 0) "↑ " else "↓ ") + String.format(Locale.US, "%.1f%%", kotlin.math.abs(delta)) + " 较昨日",
                style = MaterialTheme.typography.labelSmall,
                color = if (delta >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TodayTokensTile(state: AppUiState, modifier: Modifier) {
    Tile(modifier, "今日 TOKENS · TOKENS") {
        // 横排：左边大数字，右边输入/输出分两行。
        // 之前是竖排（大数字 + 一行备注），三列宽时那行备注必被截断，
        // 「输入 8.1万 · 输出 4.2万」只能看到一半。
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BigNumber(formatTokens(state.today.totalTokens), modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "输入 ${formatTokens(state.today.promptTokens)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = "输出 ${formatTokens(state.today.completionTokens)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 「已知额度」：token 与积分**两个大字并排**，各自带单位。
 *
 * 井水不犯河水：
 * - token 余额（灵犀）→ 左边大字，单位 token
 * - 积分余额（其余家）→ 右边大字，单位 积分
 *
 * **绝不换算**。缺哪一种就只显示那一种：
 * - 只有积分账户 → 只显示积分
 * - 只有 token 账户 → 只显示 token
 * - 都没有 → 显示「—」
 */
@Composable
private fun CreditsTile(state: AppUiState, modifier: Modifier) {
    val report = state.creditReport
    val goldText = LocalAppColors.current.goldText
    Tile(modifier, "已知额度 · TOTAL CREDITS") {
        when {
            // 两种都有：并排两个大字
            report.hasTokens && report.hasPoints -> CreditSummaryTwo(report, goldText, state)
            // 只有 token / 只有积分：都走单个大字
            report.known -> CreditSummaryOne(report, goldText)
            else -> {
                // 一个都没查到：不编「0 额度」，显示「—」
                Text(
                    text = "—",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/** 两种量纲都有：并排两个大字，各自带单位。 */
@Composable
private fun CreditSummaryTwo(report: CreditReport, goldText: Color, state: AppUiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BigNumber(
            value = formatTokens(report.tokensBalance),
            suffix = "token",
            color = goldText,
            modifier = Modifier.weight(1f),
        )
        BigNumber(
            value = formatGroupedCount(report.pointsBalance),
            suffix = "积分",
            color = goldText,
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        text = "已同步 ${state.pool.creditsKnown} 个账号",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** 只有一种量纲：单个大字。 */
@Composable
private fun CreditSummaryOne(report: CreditReport, goldText: Color) {
    val isToken = report.tokensBalance > 0
    BigNumber(
        value = if (isToken) formatTokens(report.tokensBalance) else formatGroupedCount(report.pointsBalance),
        suffix = if (isToken) "token" else "积分",
        color = goldText,
    )
}

/** Antigravity 的模型组配额窗口（每组 × 每窗口一条），首页与账号详情共用。 */
@Composable
private fun AntigravityQuotaSection(state: AppUiState, viewModel: AppViewModel, baseIndex: Int) {
    val accounts = state.accountsOf("antigravity")
    if (accounts.isEmpty()) return

    // 进首页拉一次；账号集合变化时重拉
    LaunchedEffect(accounts.map { it.uid }) {
        accounts.forEach { viewModel.loadCreditPacks("antigravity", it.uid) }
    }

    SectionCard(modifier = Modifier.staggeredAppear(baseIndex)) {
        SectionHeader("上游配额池 · ANTIGRAVITY QUOTA")
        for (account in accounts) {
            val packs = state.creditPacks["antigravity/${account.uid}"].orEmpty()
            if (accounts.size > 1) {
                Text(
                    text = account.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (packs.isEmpty()) {
                Text(
                    text = if (account.detail.isNotEmpty()) account.detail else "尚未拉取配额",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (pack in packs) {
                        QuotaPackBar(pack)
                    }
                }
            }
        }
    }
}

/** 坐标轴上限缩写：1.2k / 3.4M。 */
private fun formatAxis(value: Long): String = when {
    value >= 1_000_000L -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000L -> String.format(Locale.US, "%.1fk", value / 1_000.0)
    else -> value.toString()
}

private val DAY_LABEL_FORMAT = SimpleDateFormat("M/d", Locale.US)

private fun dayLabel(millis: Long): String = DAY_LABEL_FORMAT.format(Date(millis))
