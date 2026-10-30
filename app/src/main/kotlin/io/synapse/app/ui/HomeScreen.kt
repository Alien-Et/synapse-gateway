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
import io.synapse.app.ui.theme.DeepInk
import io.synapse.app.ui.theme.LocalAppColors
import io.synapse.app.ui.theme.WarningAmber
import io.synapse.core.provider.RoutedModel
import io.synapse.core.usage.CallRecord
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
        )

        LiveRoutingTopologyCard(state, Modifier.staggeredAppear(1))

        TelemetryGrid(state, Modifier.staggeredAppear(2))

        // 批量动作进行中/结束：首页也要看得到进度与失败原因（签到按钮就在本页）
        BatchProgressHost(state.batchProgress)
        BatchOutcomeHost(state.batchOutcome) { viewModel.dismissBatchOutcome() }

        TrendCard(state)

        SectionCard(modifier = Modifier.staggeredAppear(4)) {
            SectionHeader("快捷操作 · ACTIONS")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.onSurface)
                        .clickable { viewModel.checkinAll() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(gold))
                        Text(
                            "批量签到",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.surface,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.medium)
                        .border(1.dp, gold, MaterialTheme.shapes.medium)
                        .clickable { openTaskCenter() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "任务中心",
                        style = MaterialTheme.typography.labelLarge,
                        color = goldText,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

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
) {
    val gold = LocalAppColors.current.goldStart
    SectionCard(modifier = Modifier.staggeredAppear(0)) {
        SectionHeader("网关状态 · CORE CONSOLE")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(state.running)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (state.running) "SERVICE ACTIVE" else "SERVICE STOPPED",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Light,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "LISTEN PORT",
                    style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 0.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = ":${state.port}",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        val localUrl = state.localUrl.ifEmpty { "http://127.0.0.1:${state.port}/v1" }
        AddressRow("本机", localUrl) { onCopy(localUrl, "已复制本机地址") }
        AddressRow(
            label = "局域网",
            value = lanUrl ?: "未连接 Wi-Fi（局域网不可用）",
            onCopy = lanUrl?.let { url -> { onCopy(url, "已复制局域网地址") } },
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.onSurface)
                    .clickable { if (state.running) onStop() else onStart() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(gold))
                    Text(
                        text = if (state.running) "停止网关服务" else "启动网关服务",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.surface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.medium)
                    .clickable(onClick = onRefreshCredits)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "刷新全部额度",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------- 拓扑结构数据模型

/**
 * 拓扑里的一个账号节点。
 *
 * 账号就是要「看清楚正在用谁」，所以这里把界面需要的都不再压缩：
 * 显示名、状态、余额、在途请求数、以及它属于哪个账号槽位。
 */
private data class TopologyAccountNode(
    val uid: String,
    val label: String,
    /** 是否可用（未被手动停用、凭证也没失效）。 */
    val usable: Boolean,
    /** 凭证失效，需要重新登录——比「手动停用」更需要被看见。 */
    val dead: Boolean,
    /** 余额；[creditsKnown] 为 false 时无意义（不显示）。 */
    val credits: Long,
    val creditsKnown: Boolean,
    /** 该账号当前的在途请求数，>0 时节点描边变金、连线流光。 */
    val activeCount: Int,
    /** 手动置顶的账号标一个「优先」。 */
    val raised: Boolean,
)

/** 拓扑里的一个供应商节点（挂在网的右侧）。 */
private data class TopologyProviderNode(
    val providerId: String,
    val displayName: String,
    val weight: Float,
    val colorIndex: Int,
    val usableAccounts: Int,
    val totalAccounts: Int,
    val activeCount: Int,
    val accounts: List<TopologyAccountNode>,
)

/**
 * 实时调度拓扑卡（数据驱动的三级分发视图）：
 *
 * ```
 *        ┌─────────┐        ┌──── 字节国内 ────┬─ 账号A
 * LAN ───┤ AI-GW  ├────────┤                 └─ 账号B
 *        │ :8080  │        └──── 腾讯国内 ────┬─ 主号
 *        └────────┘                          ├─ 小号
 *                                            └─ 备用
 * ```
 *
 * - 中枢节点：网关（显示 :port，运行中呼吸发光，停止时静止置灰）
 * - 二级节点：真实有账号的供应商，权重决定线宽/亮度
 * - 三级节点：**每个账号一枚节点**，按供应商竖排扇出
 *
 * 画布是**可拖动**的：账号一多必然溢出屏幕，固定宽度会把它挤成看不见的小点。
 * 拖动手势自己做（不用 horizontalScroll），因为卡片整体在一个纵向滚动容器里，
 * 用 `awaitPointerEventScope` 判定「横向意图更大」再吃事件，才能既横拖又不夺走竖滚。
 */
@Composable
private fun LiveRoutingTopologyCard(state: AppUiState, modifier: Modifier = Modifier) {
    val gold = LocalAppColors.current.goldStart
    val goldEnd = LocalAppColors.current.goldEnd
    val palette = LocalAppColors.current.providerPalette
    val isRunning = state.running

    val recentCalls = remember(state.calls) { state.calls.take(50) }
    val activeRequests = state.activeRequests
    val hasActive = isRunning && activeRequests.isNotEmpty()

    // 统计最近 50 次请求中各供应商的请求数
    val callCountsByProvider = remember(recentCalls) {
        recentCalls.groupBy { it.providerId.lowercase() }.mapValues { it.value.size }
    }
    val totalRecentCalls = remember(callCountsByProvider) { callCountsByProvider.values.sum() }

    // 在途请求按「供应商 + 账号」两级统计：账号节点要知道自己是不是正在干活
    val activeByProvider = remember(activeRequests) {
        activeRequests.groupBy { it.providerId.lowercase() }
    }
    val activeByAccount = remember(activeRequests) {
        activeRequests.groupBy { "${it.providerId.lowercase()}/${it.uid}" }
            .mapValues { it.value.size }
    }

    // 真实有账号的供应商
    val providers = remember(
        state.accounts, state.providers, callCountsByProvider, activeByProvider, activeByAccount, totalRecentCalls,
    ) {
        val groupedAccounts = state.accounts.groupBy { it.providerId.lowercase() }
        if (groupedAccounts.isEmpty()) {
            emptyList()
        } else {
            val totalUsableOverall = state.accounts.count { it.usable }.coerceAtLeast(1)
            val sortedProviderIds = groupedAccounts.keys.sortedByDescending { pid ->
                val callCount = callCountsByProvider[pid] ?: 0
                val usable = groupedAccounts[pid]?.count { it.usable } ?: 0
                // 优先按请求数排序，相同时按可用账号数
                (callCount.toLong() shl 16) + usable
            }

            sortedProviderIds.mapIndexed { index, pid ->
                val accs = groupedAccounts[pid] ?: emptyList()
                val usable = accs.count { it.usable }
                val provInfo = state.providers.firstOrNull { it.id.equals(pid, ignoreCase = true) }
                val dName = provInfo?.displayName?.ifEmpty { pid } ?: pid

                val weight = if (totalRecentCalls > 0) {
                    ((callCountsByProvider[pid] ?: 0).toFloat() / totalRecentCalls).coerceIn(0.08f, 1f)
                } else {
                    (usable.toFloat() / totalUsableOverall).coerceIn(0.08f, 1f)
                }

                // 账号节点：在途的排前面，然后可用的、再按余额高的
                val accountNodes = accs
                    .map { acc ->
                        TopologyAccountNode(
                            uid = acc.uid,
                            label = acc.nickname.ifEmpty {
                                if (acc.uid.length > 8) acc.uid.take(6) + "…" else acc.uid
                            },
                            usable = acc.usable,
                            dead = acc.disabled,
                            credits = acc.credits,
                            creditsKnown = acc.creditsKnown,
                            activeCount = activeByAccount["$pid/${acc.uid}"] ?: 0,
                            raised = acc.raised,
                        )
                    }
                    .sortedWith(
                        compareByDescending<TopologyAccountNode> { it.activeCount > 0 }
                            .thenByDescending { it.usable }
                            .thenByDescending { if (it.creditsKnown) it.credits else Long.MIN_VALUE },
                    )

                TopologyProviderNode(
                    providerId = pid,
                    displayName = dName,
                    weight = weight,
                    colorIndex = index,
                    usableAccounts = usable,
                    totalAccounts = accs.size,
                    activeCount = activeByProvider[pid]?.size ?: 0,
                    accounts = accountNodes,
                )
            }
        }
    }

    val totalAccounts = providers.sumOf { it.totalAccounts }

    val basisCaption = when {
        totalRecentCalls > 0 -> "按近 50 次请求分布 · $totalAccounts 个账号"
        state.accounts.isNotEmpty() -> "暂无请求，按可用账号分布 · $totalAccounts 个账号"
        else -> "暂无配置账号"
    }

    // 在途流光：曲线上的光斑进度 0f→1f，只在绘制阶段读取，不逐帧重组。
    // 1.6s 一趟比原来的 1s 慢一些，长曲线上的光斑才不会显得在跳。
    val flowPhase: State<Float> = rememberInfiniteTransition(label = "topologyFlow").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "topologyFlowPhase",
    )

    // 画布拖动位置：账号一多必然溢出屏幕，拖动负责「看被挤出视口的部分」。
    // 横竖都要能拖：窄屏时账号列本身就可能超出宽度，只给纵向拖动照样看不全。
    // 直接在状态里存 px 偏移，两个方向分别夹到内容尺寸范围内——各自留 32dp 余量，
    // 足够把最后一排/最右一列拖进视野，同时不至于拖出一片空白。
    val density = LocalDensity.current
    // 不给额外余量：画布高度已经跟着内容走了，装得下就完全不该让画布内部可拖
    // —— 否则第一次滑动会被画布吃掉（只在里面挪一点点），用户得滑两次才能把
    // 画布下半部分滚进视野，手感就是「拖不动」。装不下时（账号很多）自然就有遗憾可拖。
    val overflowMarginPx = 0f
    var contentSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var viewportSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    // 画布高度**跟随内容**：账号就这么几家时，一屏直接看全，根本不用拖；
    // 只有账号多到超过上限才需要拖动。之前固定 320dp，而 9 个账号的内容有 500dp+，
    // 于是「拖到小红书就没了、除非缩小才看得到下面」—— 那是物理上装不下。
    var topologyHeight by remember { mutableStateOf(300.dp) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    // 缩放后的内容尺寸才算「真实占用」：放大后可拖范围变大，缩小后可拖范围跟着收
    val scaledContentW = contentSize.width * zoomScale
    val scaledContentH = contentSize.height * zoomScale
    val maxDragX = (scaledContentW - viewportSize.width + overflowMarginPx).coerceAtLeast(0f)
    val maxDragY = (scaledContentH - viewportSize.height + overflowMarginPx).coerceAtLeast(0f)
    val clampedOffset = Offset(
        dragOffset.x.coerceIn(-maxDragX, 0f),
        dragOffset.y.coerceIn(-maxDragY, 0f),
    )
    // 内容变化（账号增删）或缩放后重新夹一次，避免停在越界位置
    LaunchedEffect(maxDragX, maxDragY) {
        dragOffset = Offset(
            dragOffset.x.coerceIn(-maxDragX, 0f),
            dragOffset.y.coerceIn(-maxDragY, 0f),
        )
    }

    SectionCard(modifier = modifier) {
        // 顶部小节头 + 呼吸指示灯 + TODAY 大数字
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier.breathingDot(
                    color = if (isRunning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    size = 7.dp,
                    breathing = isRunning,
                    haloSize = 15.dp,
                ),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "实时调度拓扑 · LIVE TOPOLOGY",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = basisCaption,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }

            // 右侧在途/今日计数
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (activeRequests.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(gold.copy(alpha = 0.16f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "${activeRequests.size} 在途",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = goldEnd,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "TODAY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                    AnimatedContent(
                        targetState = state.today.requests,
                        transitionSpec = {
                            slideInVertically(
                                animationSpec = tween(Motion.TAB_MILLIS, easing = FastOutSlowInEasing),
                            ) { it / 2 } + fadeIn() togetherWith
                                slideOutVertically(
                                    animationSpec = tween(Motion.TAB_MILLIS, easing = FastOutSlowInEasing),
                                ) { -it / 2 } + fadeOut()
                        },
                        label = "todayRequests",
                    ) { reqCount ->
                        Text(
                            text = if (reqCount > 0) formatCount(reqCount) else "—",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            fontWeight = FontWeight.Bold,
                            color = if (reqCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        if (providers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "暂无可用供应商账号",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            TopologyCanvas(
                providers = providers,
                palette = palette,
                gold = gold,
                goldEnd = goldEnd,
                isRunning = isRunning,
                hasActive = hasActive,
                viewportHeight = topologyHeight,
                phase = flowPhase,
                dragOffset = clampedOffset,
                maxDragX = maxDragX,
                maxDragY = maxDragY,
                scale = zoomScale,
                onContentSize = { size ->
                    contentSize = size
                    if (size.height > 0) {
                        // 留 16dp 呼吸位，并给一个上下限：太矮看不清、太高把页面撑爆
                        val wanted = with(density) { size.height.toDp() } + 16.dp
                        topologyHeight = wanted.coerceIn(240.dp, 520.dp)
                    }
                },
                onViewportSize = { viewportSize = it },
                onDrag = { delta ->
                    val nextX = (dragOffset.x + delta.x).coerceIn(-maxDragX, 0f)
                    val nextY = (dragOffset.y + delta.y).coerceIn(-maxDragY, 0f)
                    // 真的动了才算「消费了这次手势」；已经顶到边界时返回 false，
                    // 让事件继续往下走、由外层页面接管滚动，而不是就地卡死。
                    val moved = nextX != dragOffset.x || nextY != dragOffset.y
                    dragOffset = Offset(nextX, nextY)
                    moved
                },
                onZoom = { factor, focus ->
                    // 围绕手指中点缩放：只改 scale，同时把焦点往「保持不动」的方向补偿偏移
                    val next = (zoomScale * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    val applied = next / zoomScale
                    zoomScale = next
                    // 缩放后内容尺寸变了，夹取范围也会变；这里只做粗略补偿，
                    // 精确夹取由 LaunchedEffect(maxDrag) 兜底
                    dragOffset = Offset(
                        (focus.x - (focus.x - dragOffset.x) * applied).coerceIn(-maxDragX, 0f),
                        (focus.y - (focus.y - dragOffset.y) * applied).coerceIn(-maxDragY, 0f),
                    )
                },
            )
        }


        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )

        // 块 2：最近一个请求的路由流水
        RecentCallRoutingFlow(state.calls.firstOrNull()) { id -> state.providerOf(id)?.displayName ?: id }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )

        // 块 3 与 块 4：上下文容量监控 + 明细列表（按模型聚合近 50 次请求）
        ContextCapacityAndModelBreakdown(state)
    }
}
/**
 * 拓扑画布：网关 → 供应商 → 账号 三级节点的连线视图，可纵向拖动。
 *
 * 为什么不用 `horizontalScroll` / `LazyColumn`：
 * 整张卡在 [PageScaffold] 的纵向滚动容器里，横向滚动容器会把竖直方向的拖动也吞掉一部分；
 * 而且连线必须知道**所有**节点的真实矩形才能画准，惰性布局只会组合可见项，线就断在半空。
 * 所以这里用普通布局 + 自己接管手势：在 `awaitPointerEventScope` 里先看方向再决定吃不吃事件，
 * 横向意图大就吃、纵向留给外层滚动 —— 两者都能用，谁也不会卡住。
 */
@Composable
private fun TopologyCanvas(
    providers: List<TopologyProviderNode>,
    palette: List<Color>,
    gold: Color,
    goldEnd: Color,
    isRunning: Boolean,
    hasActive: Boolean,
    viewportHeight: Dp,
    phase: State<Float>,
    dragOffset: Offset,
    maxDragX: Float,
    maxDragY: Float,
    scale: Float,
    onContentSize: (androidx.compose.ui.unit.IntSize) -> Unit,
    onViewportSize: (androidx.compose.ui.unit.IntSize) -> Unit,
    /** 返回 true 表示画布确实移动了；false 表示已到边界，手势应让给外层滚动。 */
    onDrag: (Offset) -> Boolean,
    onZoom: (factor: Float, focus: Offset) -> Unit,
) {
    val density = LocalDensity.current

    // 可视区放大到 320dp：原来 232dp 只够看两三家，账号一多就得频繁拖。
    // 画布仍可自由平移 + 双指缩放，宁可少看几行也别一开始就让你找不到方向。

    // 不随屏幕变的几何量。
    //
    // 中枢只装竖排「网关」两个字，38×50 就够——之前给到 46×68 明显偏大，
    // 看着像个空盒子；供应商列要放下横排的四字名（腾讯国内），62dp 配 11sp 刚好。
    val edgePad = 8.dp
    val hubWidth = 30.dp
    val hubHeight = 40.dp
    val providerColumnWidth = 62.dp
    val corridor = 30.dp
    val minAccountWidth = 132.dp
    val rowGapPx = with(density) { 7.dp.toPx() }
    val providerNodeHeightPx = with(density) { 44.dp.toPx() }
    val accountNodeHeightPx = with(density) { 46.dp.toPx() }

    // 手势：单指**自由**拖动（斜着拖也跟手），双指捏合缩放。
    //
    // 早先按方向分轴锁定 —— 横向、纵向各自判定「意图」再决定吃哪个轴。
    // 结果是斜着推时两个轴都没单独超过阈值，图一动不动；勉强判定出方向后又只能
    // 沿一根轴走，手感很"卡"。现在只看合位移：一旦超过 touchSlop 就进入拖动，
    // 并且 X、Y **一起跟手**，任何位置按下去都能顺滑拖走。
    //
    // 回调用 rememberUpdatedState 取最新值：`pointerInput(Unit)` 只在首次组合时
    // 建立检测器，闭包会一直捕获那时的 maxDrag 等局部量，拖动范围一变就对不上了。
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val gesture = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = false)
                var total = Offset.Zero
                var dragging = false
                var zooming = false
                var lastDistance = 0f
                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        // 双指：以两指中点为中心缩放
                        zooming = true
                        val a = pressed[0].position
                        val b = pressed[1].position
                        val distance = kotlin.math.hypot(b.x - a.x, b.y - a.y)
                        event.changes.forEach { it.consume() }
                        if (lastDistance > 0f && distance > 0f) {
                            currentOnZoom(distance / lastDistance, (a + b) / 2f)
                        }
                        lastDistance = distance
                        continue
                    }
                    lastDistance = 0f

                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val delta = change.positionChange()
                    total += delta
                    if (!dragging && !zooming && total.getDistance() > viewConfiguration.touchSlop) {
                        dragging = true
                    }
                    if (dragging) {
                        if (currentOnDrag(delta)) {
                            if (delta.x != 0f || delta.y != 0f) change.consume()
                        } else {
                            // 已拖到边界：不再吃事件，也不再把后续位移算进本次拖动，
                            // 否则用户会感觉「手指还在动，图却死了」，页面也滚不动。
                            dragging = false
                            total = Offset.Zero
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(viewportHeight)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f))
            .onSizeChanged { onViewportSize(it) }
            .then(gesture),
    ) {
        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val fixedWidthPx = with(density) {
            (edgePad * 2 + hubWidth + corridor * 2 + providerColumnWidth).toPx()
        }
        // 账号列吃满剩余宽度：默认刚好铺满一屏，不再一上来就横向溢出。
        // 屏幕太窄时才退到 minAccountWidth，那种情况才需要横向拖动。
        val accountWidthPx = (viewportWidthPx - fixedWidthPx)
            .coerceAtLeast(with(density) { minAccountWidth.toPx() })

        // 先算出每个节点在「内容坐标系」里的位置，节点与连线共用同一份数据，
        // 避免两边各算一次导致线接不上卡片边缘。
        val layout = remember(providers, density, accountWidthPx, rowGapPx) {
            topologyLayout(
                providers = providers,
                density = density,
                hubWidth = hubWidth,
                hubHeight = hubHeight,
                providerColumnWidth = providerColumnWidth,
                accountWidthPx = accountWidthPx,
                edgePad = edgePad,
                corridor = corridor,
                rowGapPx = rowGapPx,
                providerNodeHeightPx = providerNodeHeightPx,
                accountNodeHeightPx = accountNodeHeightPx,
            )
        }

        Box(
            modifier = Modifier
                .width(with(density) { layout.contentWidth.toDp() })
                .height(with(density) { layout.contentHeight.toDp() })
                .graphicsLayer {
                    // 缩放以画布左上角为原点，偏移由外层补齐；这样拖动与缩放互不干扰
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                .onSizeChanged { onContentSize(it) },
        ) {
            // 连线在最底层：端点在节点卡片下被盖住，看起来就是接上去的
            TopologyWires(
                layout = layout,
                providers = providers,
                palette = palette,
                gold = gold,
                goldEnd = goldEnd,
                isRunning = isRunning,
                phase = phase,
            )

            // 中枢：画在内容层里，**跟着整张图一起拖动和缩放**。
            //
            // 早先把它钉在视口上（内容滚了它不动），结果是拖动时它和节点像两块
            // 互不相干的东西；而且它不参与缩放，放大后内容全变大、只有它还那么小。
            // 现在它就是图里普通的一员，位置由 layout 算出，缩放由内容层的
            // graphicsLayer 统一处理。
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(layout.hubRect.left.roundToInt(), layout.hubRect.top.roundToInt())
                    }
                    .width(with(density) { layout.hubRect.width.toDp() })
                    .height(with(density) { layout.hubRect.height.toDp() }),
            ) {
                GatewayHubNode(
                    isRunning = isRunning,
                    gold = gold,
                    goldEnd = goldEnd,
                    hasActive = hasActive,
                )
            }

            // 供应商胶囊
            //
            // 用固定尺寸而不是「占满列宽」：连线端点是按 providerRects 算出来的，
            // 胶囊如果被 weight/fillMaxWidth 撑到别的宽度，右缘就跟端点对不上，
            // 表现就是线停在胶囊外面、差一小截没接上。
            providers.forEachIndexed { index, provider ->
                val rect = layout.providerRects[index]
                val color = palette[provider.colorIndex % palette.size]
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(rect.left.roundToInt(), rect.top.roundToInt())
                        }
                        .width(with(density) { rect.width.toDp() })
                        .height(with(density) { rect.height.toDp() }),
                    contentAlignment = Alignment.Center,
                ) {
                    ProviderNode(
                        provider = provider,
                        color = color,
                        gold = gold,
                        isRunning = isRunning,
                    )
                }
            }

            // 账号节点：同样用 rect 的左上角 + 固定高度，保证左缘与连线端点重合
            providers.forEachIndexed { index, provider ->
                provider.accounts.forEachIndexed { accIndex, account ->
                    val rect = layout.accountRects[index][accIndex]
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(rect.left.roundToInt(), rect.top.roundToInt())
                            }
                            .width(with(density) { rect.width.toDp() })
                            .height(with(density) { rect.height.toDp() }),
                    ) {
                        AccountNode(
                            account = account,
                            color = palette[provider.colorIndex % palette.size],
                            gold = gold,
                            isRunning = isRunning,
                        )
                    }
                }
            }
        }

        // 还有内容没露出来时给渐隐提示，让人知道这个画布能拖
        if (maxDragY > 0f && dragOffset.y > -maxDragY + 1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(22.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                        ),
                    ),
            )
        }

        if (maxDragX > 0f && dragOffset.x > -maxDragX + 1f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .height(viewportHeight)
                    .width(20.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                        ),
                    ),
            )
        }

        // 右下角常驻一个小提示：不写这句没人知道这块能拖
        if (maxDragX > 0f || maxDragY > 0f) {
            Text(
                text = "可拖动",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 6.dp, bottom = 3.dp),
            )
        }
    }
}

/** 一个节点在内容坐标系里的位置（px）。 */
private data class TopologyLayout(
    val contentHeight: Float,
    val hubCenterY: Float,
    val hubRect: Rect,
    val providerRects: List<Rect>,
    val accountRects: List<List<Rect>>,
    /** 每个供应商到它名下账号的连线（起点 → 终点）。 */
    val providerToAccounts: List<Pair<Offset, List<Offset>>>,
    /** 网关连到每个供应商的落点（取胶囊左缘，与 [providerRects] 对齐）。 */
    val hubToProviders: List<Offset> = emptyList(),
    /** 端口前的直行段长，线先水平出/入再接曲线，看着才是「接上去」而不是斜插。 */
    val stubPx: Float = 0f,
    /** 内容宽度，缩放居中要用。 */
    val contentWidth: Float = 0f,
    /** 有在途请求的链路，单独走流光绘制。 */
    val activeLinks: List<Pair<Offset, Offset>> = emptyList(),
    /**
     * 逐账号的在途标记，与 [accountRects] 同形状。
     *
     * 静态层靠它把在途链路让给动效层：否则「服务一开所有线都变金色」，
     * 满屏金柱子反而看不出正在走哪条路由。
     */
    val activeAccountFlags: List<List<Boolean>> = emptyList(),
    /** 各供应商的分发权重，按下标与 [providerRects] 对齐。 */
    val providerWeights: List<Float> = emptyList(),
)

/**
 * 计算三级节点的位置。
 *
 * 排布规则：每个供应商占一段，段高 = 供应商胶囊 + 它名下所有账号；段与段之间留 gap。
 * 账号多的一段自然变高，整张图跟着变高 —— 超出的部分靠拖动看，不折叠、不省略。
 */
private fun topologyLayout(
    providers: List<TopologyProviderNode>,
    density: Density,
    hubWidth: Dp,
    hubHeight: Dp,
    providerColumnWidth: Dp,
    accountWidthPx: Float,
    edgePad: Dp,
    corridor: Dp,
    rowGapPx: Float,
    providerNodeHeightPx: Float,
    accountNodeHeightPx: Float,
): TopologyLayout {
    val hubWidthPx = with(density) { hubWidth.toPx() }
    val hubHeightPx = with(density) { hubHeight.toPx() }
    val providerWidthPx = with(density) { providerColumnWidth.toPx() }
    val corridorPx = with(density) { corridor.toPx() }

    // 端口前留一小段直行，线才不会贴着卡片边弯出去
    val stubPx = with(density) { 10.dp.toPx() }
    // 左侧留白：中枢若紧贴 x=0，描边和光晕会被容器裁掉，看起来就是「网关二字被切了一半」
    val edgePadPx = with(density) { edgePad.toPx() }
    val hubLeft = edgePadPx
    val providerLeft = hubLeft + hubWidthPx + corridorPx
    val accountLeft = providerLeft + providerWidthPx + corridorPx
    // 连线在节点上真正的落点：供应商取右缘、账号取左缘，靠 stub 保证水平进出
    val providerPortX = providerLeft + providerWidthPx
    val accountPortX = accountLeft

    val providerRects = ArrayList<Rect>(providers.size)
    val accountRects = ArrayList<List<Rect>>(providers.size)
    val providerToAccounts = ArrayList<Pair<Offset, List<Offset>>>(providers.size)
    /** 网关 → 供应商的起始端口（取自供应商胶囊左缘）。 */
    val hubToProviders = ArrayList<Offset>(providers.size)

    var cursorY = 0f
    providers.forEach { provider ->
        val accountsHeight = provider.accounts.size * accountNodeHeightPx +
            (provider.accounts.size - 1).coerceAtLeast(0) * rowGapPx
        val segmentHeight = maxOf(providerNodeHeightPx, accountsHeight)

        // 供应商胶囊在这一段的垂直正中
        val providerCenterY = cursorY + segmentHeight / 2f
        providerRects.add(
            Rect(
                left = providerLeft,
                top = providerCenterY - providerNodeHeightPx / 2f,
                right = providerLeft + providerWidthPx,
                bottom = providerCenterY + providerNodeHeightPx / 2f,
            ),
        )

        // 账号：从段顶开始往下排，超出段高就撑开
        val accountTops = ArrayList<Rect>(provider.accounts.size)
        val accountCenters = ArrayList<Offset>(provider.accounts.size)
        var accY = cursorY + (segmentHeight - accountsHeight) / 2f
        provider.accounts.forEach { _ ->
            accountTops.add(
                Rect(
                    left = accountLeft,
                    top = accY,
                    right = accountLeft + accountWidthPx,
                    bottom = accY + accountNodeHeightPx,
                ),
            )
            accountCenters.add(Offset(accountPortX, accY + accountNodeHeightPx / 2f))
            accY += accountNodeHeightPx + rowGapPx
        }
        accountRects.add(accountTops)
        // 起点固定在胶囊右缘正中；`stubPx` 由绘制侧负责先直行再拐弯
        providerToAccounts.add(
            Offset(providerPortX, providerCenterY) to accountCenters,
        )
        hubToProviders.add(Offset(providerLeft, providerCenterY))

        cursorY += segmentHeight + rowGapPx * 2
    }

    // 内容至少要有中枢那么高，否则唯一的供应商会被中枢顶出可视区
    val contentHeight = (cursorY - rowGapPx * 2).coerceAtLeast(hubHeightPx + rowGapPx * 2)
    val hubCenterY = contentHeight / 2f
    val hubRect = Rect(
        left = hubLeft,
        top = hubCenterY - hubHeightPx / 2f,
        right = hubLeft + hubWidthPx,
        bottom = hubCenterY + hubHeightPx / 2f,
    )

    return TopologyLayout(
        contentHeight = contentHeight,
        hubCenterY = hubCenterY,
        hubRect = hubRect,
        providerRects = providerRects,
        accountRects = accountRects,
        providerToAccounts = providerToAccounts,
        hubToProviders = hubToProviders,
        stubPx = stubPx,
        // 右侧也留一点，账号卡片的描边同样会被裁
        contentWidth = accountLeft + accountWidthPx + edgePadPx,
        activeLinks = run {
            val links = ArrayList<Pair<Offset, Offset>>()
            providers.forEachIndexed { index, provider ->
                val (start, centers) = providerToAccounts[index]
                provider.accounts.forEachIndexed { accIndex, account ->
                    if (account.activeCount > 0) links.add(start to centers[accIndex])
                }
            }
            links
        },
        providerWeights = providers.map { it.weight },
        activeAccountFlags = providers.map { p -> p.accounts.map { it.activeCount > 0 } },
    )
}

/**
 * 连线层：网关 → 各供应商 → 各自账号，两段贝塞尔扇出。
 *
 * - 供应商段线宽/透明度取分发权重；账号段统一细线，只在有在途时变金。
 * - 有在途的链路沿曲线跑光斑，进度以 [State] 传入、在绘制阶段读取，只重绘不重组。
 */
@Composable
private fun TopologyWires(
    layout: TopologyLayout,
    providers: List<TopologyProviderNode>,
    palette: List<Color>,
    gold: Color,
    goldEnd: Color,
    isRunning: Boolean,
    phase: State<Float>,
) {
    val idle = MaterialTheme.colorScheme.outlineVariant
    val hub = layout.hubRect

    // 静态层只画**空闲**链路，并且一律用中性色。
    //
    // 之前这里写 `if (isRunning) gold else idle`：服务一开，所有连线全变金色，
    // 满屏金色竖柱子，反而看不出正在走哪条 —— 与「连线要能提示路由」的目标背道而驰。
    // 在途链路整个交给下面的动效层去画（更粗、发光、带光点）。
    Canvas(modifier = Modifier.fillMaxSize()) {
        val stub = layout.stubPx

        // 网关 → 供应商：从中枢右缘水平出线，最后水平落在胶囊左缘
        layout.providerRects.forEachIndexed { index, rect ->
            if (isRunning && (providers.getOrNull(index)?.activeCount ?: 0) > 0) return@forEachIndexed
            val weight = layout.providerWeights.getOrElse(index) { 0.5f }
            val stroke = (1.1f + weight * 1.6f).dp.toPx()
            drawPath(
                path = stubPath(
                    Offset(hub.right, hub.center.y),
                    Offset(rect.left, rect.center.y),
                    stub,
                ),
                color = idle.copy(alpha = 0.35f + weight * 0.35f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }

        // 供应商 → 账号：同一家的几根线共用右缘起点，看起来就是「一个口扇出」
        layout.providerToAccounts.forEachIndexed { index, (start, ends) ->
            val flags = layout.activeAccountFlags.getOrNull(index).orEmpty()
            ends.forEachIndexed { accIndex, end ->
                if (isRunning && (flags.getOrNull(accIndex) == true)) return@forEachIndexed
                drawPath(
                    path = stubPath(start, end, stub),
                    color = idle.copy(alpha = 0.55f),
                    style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round),
                )
            }
        }
    }

    // 在途链路的动效单独一层。
    //
    // 「现在正在走哪条路由」是这张图最该被一眼看到的信息，所以给足视觉信号：
    // 整条链路呼吸明暗 + 一列带拖尾的光点沿路径流动（方向 = 网关 → 账号）
    // + 终点扩散涟漪（表示请求已经送到这个账号）。
    // 空闲链路保持静止细线，不抢注意力 —— 全都动起来等于全都没动。
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (!isRunning) return@Canvas
        val progress = phase.value
        val breath = 0.5f + 0.5f * kotlin.math.sin(progress * 2f * kotlin.math.PI).toFloat()
        // 整条光流在两端淡入淡出，光点不会在端点处突然冒出或消失
        val envelope = kotlin.math.sin(progress * kotlin.math.PI).toFloat()

        fun flow(path: Path) {
            val measure = PathMeasure().apply { setPath(path, false) }
            val length = measure.length
            if (length <= 0f) return

            // 1) 整条线发光 + 呼吸：哪怕光点正好在端点淡出，也看得出这条线是「活的」
            drawPath(
                path = path,
                color = gold.copy(alpha = 0.30f + 0.30f * breath),
                style = Stroke(width = (2.0f + 1.4f * breath).dp.toPx(), cap = StrokeCap.Round),
            )

            // 2) 一列光点：头大而亮、尾依次变小变淡，读起来就是「往那边流」
            FLOW_TAILS.forEach { (offset, weight) ->
                val t = (((progress + offset) % 1f) + 1f) % 1f
                val position = measure.getPosition(t * length)
                val radius = (2.6f + 2.0f * weight).dp.toPx()
                drawCircle(
                    color = goldEnd.copy(alpha = 0.9f * weight * envelope),
                    radius = radius,
                    center = position,
                )
                if (weight > 0.9f) {
                    // 头部再罩一圈柔光：远看就是一个亮点在链路上跑
                    drawCircle(
                        color = goldEnd.copy(alpha = 0.22f * envelope),
                        radius = radius * 2.6f,
                        center = position,
                    )
                }
            }

            // 3) 终点涟漪：一圈向外扩散的描边，强调「请求送到这个账号了」
            val ripple = (progress * 2f) % 1f
            drawCircle(
                color = goldEnd.copy(alpha = 0.5f * (1f - ripple) * envelope),
                radius = (3f + ripple * 9f).dp.toPx(),
                center = measure.getPosition(length),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }

        // 网关 → 有在途的供应商：这半截也要亮，否则「从网关出发」那段是暗的，
        // 用户只能看到后半截在闪，看不出整条链路。
        providers.forEachIndexed { index, provider ->
            if (provider.activeCount <= 0) return@forEachIndexed
            val rect = layout.providerRects.getOrNull(index) ?: return@forEachIndexed
            flow(
                stubPath(
                    Offset(hub.right, hub.center.y),
                    Offset(rect.left, rect.center.y),
                    layout.stubPx,
                ),
            )
        }

        // 供应商 → 正在服务的账号
        layout.activeLinks.forEach { (start, end) ->
            flow(stubPath(start, end, layout.stubPx))
        }
    }
}

/**
 * 端点带水平直行的三次贝塞尔。
 *
 * 先沿 x 走 [stubPx] 再接曲线，两端切线都是水平的：线从卡片边缘**垂直穿出/接入**，
 * 而不是斜着插进卡片角上。之前直接从边缘画到边缘，短距离时曲线几乎是直插，
 * 看起来就像「没连上」。
 */
private fun stubPath(start: Offset, end: Offset, stubPx: Float): Path {
    val dx = end.x - start.x
    // 走廊够宽才走 stub，太窄就退化成普通贝塞尔（否则会折返出一小段毛刺）
    val s = if (dx > stubPx * 3f) stubPx else 0f
    val p0 = start
    val p1 = Offset(start.x + s, start.y)
    val p3 = end
    val p2 = Offset(end.x - s, end.y)
    return Path().apply {
        moveTo(p0.x, p0.y)
        if (s > 0f) lineTo(p1.x, p1.y)
        val midDx = p2.x - p1.x
        cubicTo(p1.x + midDx * 0.45f, p1.y, p2.x - midDx * 0.45f, p2.y, p3.x, p3.y)
    }
}

/**
 * 供应商胶囊：名字横排，账号数量在下一行。
 *
 * 恢复成上下两排：竖排虽然不会被省略号截断，但「腾讯国内」四个字竖着排会顶到
 * 胶囊上下边缘，跟旁边账号节点的行高对不齐，看着很别扭。横排 + 下一行计数
 * 反而更紧凑，也符合「名称在上、状态在下」的阅读习惯。
 */
@Composable
private fun ProviderNode(
    provider: TopologyProviderNode,
    color: Color,
    gold: Color,
    isRunning: Boolean,
) {
    val active = isRunning && provider.activeCount > 0
    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (active) 1.5.dp else 1.dp,
                color = if (active) gold else color.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 第一排：供应商名（横排）
        Text(
            text = provider.displayName,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = TEXT_BODY),
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        // 第二排：可用/总数，有在途时补一个金色小胶囊
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(color))
            StatusPill(
                text = "${provider.usableAccounts}/${provider.totalAccounts}",
                color = if (active) gold else MaterialTheme.colorScheme.onSurfaceVariant,
                emphasized = active,
            )
            if (provider.activeCount > 0) {
                StatusPill(text = "${provider.activeCount}在途", color = gold, emphasized = true)
            }
        }
    }
}

/**
 * 自适应状态胶囊。
 *
 * 统一用它来放「3/3」「235」「在途×2」这类短状态：宽度随内容自适应，
 * 同一个列表里的胶囊不会忽宽忽窄；圆角用半高做成全圆头。
 */
@Composable
private fun StatusPill(
    text: String,
    color: Color,
    emphasized: Boolean = false,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (emphasized) color.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = TEXT_TINY,
                fontFamily = FontFamily.Monospace,
            ),
            fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Normal,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * 账号节点：第一行是账号名，第二行是状态胶囊。
 *
 * 额度胶囊单独一行：和名字挤在同一行时，邮箱一长（`user@example.com`）
 * 要么把胶囊挤出去、要么名字被截断，两者都想保就只能都缩水。
 * 分行之后名字有整行可用，额度也不会被误读成名字的一部分。
 */
@Composable
private fun AccountNode(
    account: TopologyAccountNode,
    color: Color,
    gold: Color,
    isRunning: Boolean,
) {
    val active = isRunning && account.activeCount > 0
    val dotColor = when {
        account.dead -> MaterialTheme.colorScheme.error
        !account.usable -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        else -> color
    }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (active) gold.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            )
            .border(
                width = if (active) 1.2.dp else 1.dp,
                color = if (active) gold else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            // 第一行：账号名
            Text(
                text = account.label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = TEXT_BODY),
                color = if (account.usable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            // 第二行：状态胶囊（额度 / 停用 / 在途）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StatusPill(
                    text = when {
                        account.dead -> "失效"
                        !account.usable -> "停用"
                        account.activeCount > 0 -> "在途${if (account.activeCount > 1) "×${account.activeCount}" else ""}"
                        account.creditsKnown -> formatCredits(account.credits)
                        else -> "可用"
                    },
                    color = when {
                        account.dead -> MaterialTheme.colorScheme.error
                        !account.usable -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        active -> gold
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    emphasized = active,
                )
                if (account.raised) {
                    StatusPill(text = "优先", color = gold, emphasized = true)
                }
            }
        }
    }
}

/**
 * 网关中枢节点：竖排「网关」二字，运行中时金色描边 + 微发光晕，停止时置灰。
 *
 * 不显示端口：端口在上面「网关状态」卡片里已经有了，拓扑里再写一遍只是噪音，
 * 而且它会把本就不宽的中枢撑得更难看。这里只保留「网关」这一个身份。
 */
@Composable
private fun GatewayHubNode(
    isRunning: Boolean,
    gold: Color,
    goldEnd: Color,
    hasActive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(14.dp))
            .drawBehind {
                if (isRunning) {
                    val haloAlpha = if (hasActive) 0.22f else 0.10f
                    drawRoundRect(
                        color = gold.copy(alpha = haloAlpha),
                        size = size,
                        cornerRadius = CornerRadius(10.dp.toPx()),
                    )
                }
            }
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = if (hasActive) 1.5.dp else 1.dp,
                color = if (isRunning) gold else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(14.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // 竖排：一个字一行，读起来就是「网 / 关」
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            "网关".forEach { ch ->
                Text(
                    text = ch.toString(),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.sp),
                    fontWeight = FontWeight.Bold,
                    color = if (isRunning) goldEnd else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
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
private fun RecentCallRoutingFlow(call: CallRecord?, providerLabel: (String) -> String = { it }) {
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
        val accountDisplay = call.accountNickname.ifEmpty { call.accountUid.ifEmpty { "默认账号" } }
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
private fun ContextCapacityAndModelBreakdown(state: AppUiState) {
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

/** 状态圆点：运行中绿色带浅光环，停止灰色。 */
@Composable
private fun StatusDot(running: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        if (running) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)))
        }
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(if (running) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)),
        )
    }
}

/** 一行接入地址：浅灰底 8dp 圆角 + 等宽地址 + 黑色复制小块。 */
@Composable
private fun AddressRow(label: String, value: String, onCopy: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
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
            color = MaterialTheme.colorScheme.onSurface,
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

/** 2×3 遥测指标网格。 */
@Composable
private fun TelemetryGrid(state: AppUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProvidersTile(state, Modifier.weight(1f))
            ReadinessTile(state, Modifier.weight(1f))
            ModelsTile(state, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RequestsTile(state, Modifier.weight(1f))
            TodayTokensTile(state, Modifier.weight(1f))
            CreditsTile(state, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tile(modifier: Modifier, label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .height(112.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
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
private fun BigNumber(value: String, suffix: String? = null, color: Color = Color.Unspecified) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Light,
            color = if (color != Color.Unspecified) color else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
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
        BigNumber(formatCount(state.today.requests))
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
        BigNumber(formatTokens(state.today.totalTokens))
        TileNote("输入 ${formatTokens(state.today.promptTokens)} · 输出 ${formatTokens(state.today.completionTokens)}")
    }
}

@Composable
private fun CreditsTile(state: AppUiState, modifier: Modifier) {
    val known = state.pool.creditsKnown > 0
    val goldText = LocalAppColors.current.goldText
    Tile(modifier, "已知额度 · TOTAL CREDITS") {
        BigNumber(
            value = if (known) formatCount(state.pool.totalCredits) else "—",
            color = if (known) goldText else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TileNote(if (known) "已同步 ${state.pool.creditsKnown} 个账号" else "尚未同步额度")
    }
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
                    text = account.nickname.ifEmpty { account.uid },
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
