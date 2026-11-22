package io.synapse.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.synapse.app.ui.theme.WarningAmber
import io.synapse.core.gateway.GatewaySettings
import io.synapse.core.usage.CallRecord
import io.synapse.core.usage.CallStatus
import io.synapse.core.usage.LogLine
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogTab(val label: String) { Calls("调用记录"), Requests("请求日志") }

private enum class StatusFilter(val label: String) {
    ALL("全部"), SUCCESS("成功"), FAILED("失败"), ABORTED("中断"),
}

private enum class LevelFilter(val label: String) {
    ALL("全部"), INFO("信息"), WARN("警告"), ERROR("错误"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(state: AppUiState, viewModel: AppViewModel) {
    var tab by remember { mutableStateOf(LogTab.Calls) }
    var filter by remember { mutableStateOf(StatusFilter.ALL) }
    var levelFilter by remember { mutableStateOf(LevelFilter.ALL) }
    // 请求日志的详情：与调用记录分开（那边是结构化字段，这边是一段自由文本）
    var detailLog by remember { mutableStateOf<LogLine?>(null) }
    var showLogSettings by remember { mutableStateOf(false) }
    // 就地展开的那条记录（null = 全部收起）。只允许一条，避免列表被撑成几屏。
    var expandedCallId by remember { mutableStateOf<String?>(null) }
    // 滚动时自动切紧凑：手指在滚时列表变单行，一屏能扫更多条；停手 500ms 后恢复。
    // 用 isScrollInProgress 而不是算速度——速度阈值在不同机型上手感差异大，
    // 「在滚就紧凑」是最不容易出错的口径，而且用户停手后立刻能看到详情。
    var compactWhileScrolling by remember { mutableStateOf(false) }

    // 进入本页先拉一次（历史数据）；之后靠 RequestLog 的推送实时刷新，
    // 不再做 5 秒轮询——日志写入时 ViewModel 会主动推 state。
    LaunchedEffect(tab) {
        viewModel.refreshRecords()
    }

    val listState = rememberLazyListState()

    // ---------------- 自动滚屏 ----------------
    // 规则（主人要求）：
    // - 用户没往上翻 → 新日志来了自动跟到最新；
    // - 用户上滑查看历史 → 停在他看的位置，上方继续追加但不抢滚动；
    // - 用户滑回顶部 → 自动恢复跟屏。
    //
    // ⚠️ 方向：RequestLog 用 addFirst，**最新一条在 index 0（顶部）**，
    // 所以「跟最新」= 停在顶部，不是滚到底部。
    //
    // ⚠️ 曾经的关键 bug：之前用「第一条是否贴顶（offset >= -4）」来判断用户有没有离开，
    // 但新日志插入会让列表整体上移，插入瞬间 index 0 的 offset 是负数，
    // 于是被误判成「用户滑走了」，userScrolledAway 被永久置为 true —— 从此再也不自动滚，
    // 表现就是「日志不再自动堆叠/自动滚屏」。
    //
    // 现在改成两个独立信号：
    // 1. [userDragged]：只有**手指真的拖动过**才算用户离开（程序滚动不算）；
    // 2. 滚回顶部（firstVisibleItemIndex == 0）就恢复跟屏。
    var userScrolledAway by remember(tab) { mutableStateOf(false) }

    // 只有用户拖拽才会置位：用 nestedScroll 拿用户手势，避开程序滚动的干扰。
    val dragWatcher = remember(listState) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // 手指向下拉（available.y > 0）= 想看更新的内容，不算离开
                // 手指向上推（available.y < 0）= 往下翻历史，算离开
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y < 0) {
                    userScrolledAway = true
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }

    // 回到顶部（index 0 且基本贴顶）就恢复跟屏。这里放宽到 -32：插入动画期间
    // 首条会有小幅负偏移，太紧会导致「刚恢复又被判离开」，来回抖动。
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val first = info.visibleItemsInfo.firstOrNull()
            first != null && first.index == 0 && first.offset >= -32
        }.collect { atTop ->
            if (atTop) userScrolledAway = false
        }
    }

    val calls = remember(state.calls, filter) {
        state.calls.filter {
            when (filter) {
                StatusFilter.ALL -> true
                StatusFilter.SUCCESS -> it.status == CallStatus.SUCCESS
                StatusFilter.FAILED -> it.status == CallStatus.FAILED
                StatusFilter.ABORTED -> it.status == CallStatus.ABORTED
            }
        }
    }

    // 滚动时自动切紧凑：手指一滚就收成单行，停手 500ms 后恢复舒适模式。
    // 500ms 是实测手感值——太短会在惯性滚动中来回跳，太长则停手后还卡在紧凑态。
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                compactWhileScrolling = true
            } else {
                delay(500)
                // 二次确认：这 500ms 内如果又滚起来了，就别切回去
                if (!listState.isScrollInProgress) compactWhileScrolling = false
            }
        }
    }

    val logs = remember(state.logs, levelFilter) {
        when (levelFilter) {
            LevelFilter.ALL -> state.logs
            else -> state.logs.filter { it.level.equals(levelFilter.name, ignoreCase = true) }
        }
    }

    // 本页自带骨架：筛选区固定在顶部，列表用 LazyColumn 懒加载。
    // 记录最多数百条、每条含大段原文，一次性组合全部卡片会明显卡顿；
    // 而 LazyColumn 不能嵌在 PageScaffold 的 verticalScroll 里（无限高度约束会崩），所以这里不走 PageScaffold。
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        PageHeader(
            title = "记录",
            subtitle = "调用与请求日志",
            actions = {
                // 刷新按钮已删除：本页每 5 秒自动刷新，手动刷新只会打断自动滚屏
                StatusChip(state.running)
            },
        )

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(LogTab.Calls.label, tab == LogTab.Calls) { tab = LogTab.Calls }
                    ChoiceChip(LogTab.Requests.label, tab == LogTab.Requests) { tab = LogTab.Requests }
                }
                // 两个标签下都保留设置入口；面板内容与标签无关（见下方 LogSettings 分组）
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showLogSettings = !showLogSettings }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "日志设置",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = if (showLogSettings) "收起设置" else "日志设置",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showLogSettings) {
                // 面板与当前标签无关：两个标签看到完全相同的设置。
                // 此前内容随标签切换（调用记录给归档/保留/清空，请求日志只给容量/清空），
                // 导致「这个设置只有某个标签才有」的困惑——同类返工已发生两次。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 面板较长，限高 + 可滚动，避免挤掉下面的列表
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DiagnosticsSettings(state, viewModel)
                    CallLogSettings(state, viewModel)
                    RequestLogSettings(state, viewModel)
                }
            }
            if (tab == LogTab.Calls) {
                Text(
                    text = "共 ${state.calls.size} 条记录",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusFilter.entries.forEach { option ->
                        ChoiceChip(option.label, filter == option, { filter = option })
                    }
                }
                Text(
                    text = "点卡片看原文 · 长按删除 · 参数在右上「日志设置」里逐项调整",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LevelFilter.entries.forEach { option ->
                        ChoiceChip(option.label, levelFilter == option) { levelFilter = option }
                    }
                }
                Text(
                    text = "共 ${logs.size} 条日志 · 容量 ${state.settings.requestLogCapacity} 条",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "网关与上游交互的流水，环形覆盖只保留最近若干条；参数在右上「日志设置」里逐项调整。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 自动跟随（最新在 index 0）：不依赖「条数变化」那种脆弱时序，
        // 改成**持续守护**——只要用户没翻走、且首条不是 index 0，就拉回顶部。
        // 这样无论新条目在哪一帧进布局、有没有插入动画，都一定跟得上。
        // 用户往上翻历史时 userScrolledAway=true，循环立刻停止，不抢滚动。
        LaunchedEffect(tab, listState) {
            snapshotFlow {
                val info = listState.layoutInfo
                val first = info.visibleItemsInfo.firstOrNull()
                Triple(
                    first?.index ?: -1,
                    first?.offset ?: 0,
                    info.totalItemsCount,
                )
            }.collect { (firstIndex, firstOffset, total) ->
                if (userScrolledAway || total <= 0) return@collect
                // 首条不在 0（或虽在 0 但被新条目顶出可见区）时拉回顶部
                if (firstIndex != 0 || firstOffset < -32) {
                    listState.scrollToItem(0)
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .scrollFade(listState)
                // 监听用户拖拽：只有手指真的往下翻历史才停止自动跟屏
                .nestedScroll(dragWatcher),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (tab == LogTab.Calls) {
                if (calls.isEmpty()) {
                    item(key = "empty-calls") {
                        SectionCard {
                            EmptyHint(
                                title = "暂无调用记录",
                                message = "用任一 API Key 调用本地网关后，记录会出现在这里。",
                            )
                        }
                    }
                } else {
                    items(calls, key = { it.id }) { record ->
                        var confirmDelete by remember { mutableStateOf(false) }
                        SectionCard {
                            CallRow(
                                record = record,
                                providerName = state.providerOf(record.providerId)?.displayName
                                    ?: record.providerId,
                                // 展开态按记录 id 保存在列表层级：滚动出屏再回来仍然展开。
                                // 只允许同时展开一条——多条全展开会把列表撑成几屏，
                                // 反而失去「扫视」的意义。
                                expanded = expandedCallId == record.id,
                                compact = compactWhileScrolling,
                                // 最终账号动态解析：改名后列表立刻跟着变
                                accountLabel = { rec ->
                                    state.accounts
                                        .firstOrNull { it.providerId == rec.providerId && it.uid == rec.accountUid }
                                        ?.displayName
                                        ?: rec.finalAccount.ifEmpty {
                                            rec.accountNickname.ifEmpty { rec.accountUid }
                                        }
                                },
                                onToggleExpand = {
                                    expandedCallId = if (expandedCallId == record.id) null else record.id
                                },
                                // 长按只开确认框：删的是一条带完整原文的记录，误触代价太高
                                onLongClick = { confirmDelete = true },
                                loadFullText = { field -> viewModel.engineFullText(record.id, field) },
                            )
                        }
                        if (confirmDelete) {
                            ConfirmDestructiveDialog(
                                title = "删除这条调用记录？",
                                body = "${record.model} · ${statusLabel(record.status)}，删除后不可恢复。",
                                onConfirm = { viewModel.deleteCall(record.id) },
                                onDismiss = { confirmDelete = false },
                            )
                        }
                    }
                }
            } else {
                if (logs.isEmpty()) {
                    item(key = "empty-logs") {
                        SectionCard {
                            EmptyHint("暂无请求日志", "启动服务并发起调用后，这里会记录每次转发与换号。")
                        }
                    }
                } else {
                    // key 用单调自增的 seq：时间戳+内容可能重复（同一秒内多条相同日志），
                    // 重复 key 会让 LazyColumn 直接崩溃。
                    itemsIndexed(logs, key = { _, line -> line.seq }) { _, line ->
                        SectionCard {
                            // 卡片只放概要（固定一行），完整正文点开浮窗看，避免超长 JSON 把列表撑得忽高忽低
                            RequestLogRow(line) { detailLog = line }
                        }
                    }
                }
            }
        }
    }

    detailLog?.let { line ->
        ModalBottomSheet(onDismissRequest = { detailLog = null }) {
            RequestLogDetailSheet(line)
        }
    }
}

/**
 * 请求日志卡片：固定高度，只放概要（时间 / 级别 / 首行摘要）。
 *
 * 完整正文（多行 JSON、堆栈、超长上游报文）点开 [RequestLogDetailSheet] 查看。
 * 卡片高度不随内容变化 —— 旧实现直接塞 `line.render()`，一条几十行的日志就能
 * 把列表撑出一屏高，滚动时忽高忽低，也没法快速扫过。
 */
@Composable
private fun RequestLogRow(line: LogLine, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = TIME_FORMAT.format(Date(line.atMillis)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.extraSmall)
                .background(levelColor(line.level).copy(alpha = 0.14f))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text(
                text = line.level,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = levelColor(line.level),
            )
        }
        Text(
            // 只取第一行：多行内容在卡片里会撑高，点开才看全文
            text = line.text.lineSequence().firstOrNull().orEmpty().ifBlank { "（空）" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 请求日志详情浮窗：完整正文（保留换行）+ 一键复制。
 *
 * 与调用记录的卡片展开区分开：那边是结构化字段（请求/响应/上游报文），
 * 这边只有一段自由文本。
 */
@Composable
private fun RequestLogDetailSheet(line: LogLine) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = TIME_FORMAT.format(Date(line.atMillis)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(levelColor(line.level).copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = line.level,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = levelColor(line.level),
                )
            }
            Box(Modifier.weight(1f))
            TextButton(
            shape = MaterialTheme.shapes.medium,
            onClick = { clipboard.setText(AnnotatedString(line.text)) }) {
                Text("复制")
            }
        }
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 一条调用记录的卡片（方案 B：左侧竖色条 + 信息分层 + 就地展开）。
 *
 * ## 为什么改成这个样子
 *
 * 旧实现一张卡最多 8 个垂直块（模型行 / 路由标签行 / 时间行 / 指标行 /
 * 故障账号行 / 禁用行 / 模型重写行 / 错误行），一屏只能看 3~4 条，
 * 而其中大部分信息日常扫视根本用不上：
 *
 * - 「路由」是常态（几乎每次都是），每张卡都挂一枚纯属噪音；
 * - 失败的账号会重复渲染「故障」标签 N 次（`故障 账号A 故障 账号B …`）；
 * - 绝对时间（`11-19 04:28:20`）难以快速判断「多久以前」；
 * - 「流式」是常态，只有「非流式」才值得提一句。
 *
 * ## 现在的分层
 *
 * **概览层**（永远可见，扫视用）：状态色条 + 模型名 + 结果标签 + 关键指标 + 相对时间。
 * **展开层**（点击卡片就地展开）：完整账号链路、上游模型名、错误全文、各字段原文。
 *
 * 关键信息（失败账号、模型重写）不再默认占版面，但一点就能看到——
 * 折叠不等于隐藏。
 *
 * @param expanded 是否展开（由调用方持有，保证列表滚动时展开态不丢）
 * @param onToggleExpand 点击卡片：切换展开
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun CallRow(
    record: CallRecord,
    /** 供应商展示名（渲染时从供应商表解析；找不到就退回 id，不落库）。 */
    providerName: String,
    expanded: Boolean,
    /** 滚动中的紧凑模式：只留「色条 + 模型名 + 耗时 + 时间」一行。 */
    compact: Boolean,
    /**
     * 最终账号的展示名解析（按 providerId + uid 查当前账号）。
     *
     * 记录里存的是**历史快照**（`finalAccount` / `accountNickname`），
     * 用户改名后不会跟着变——所以要动态解析，只有查不到（账号已删/老记录无 uid）
     * 时才回落到快照。
     */
    accountLabel: (CallRecord) -> String,
    onToggleExpand: () -> Unit,
    onLongClick: () -> Unit,
    /** 展开时按需加载完整原文（与浮窗共用同一条加载路径）。 */
    loadFullText: suspend (io.synapse.core.usage.CallTextField) -> String?,
) {
    val ok = record.status == CallStatus.SUCCESS
    val tone = statusColor(record.status)
    // 最终落在哪个账号：优先按 uid 查当前账号（改名后立刻生效），
    // 查不到才回落到记录里的历史快照
    val finalName = accountLabel(record)
    // 区域不同 = 故障转移；两边都有值时才比较（非区域型供应商两个都是空串 → 路由）
    val crossRegion = record.routeRegion.isNotEmpty() &&
        record.finalAccountRegion.isNotEmpty() &&
        record.routeRegion != record.finalAccountRegion
    // 试过但没成的账号。成功时排除最终那个；中断不算故障（只是打到一半被掐了）；
    // 失败时全列（全部都不行，区分「哪个成功了」毫无意义）。
    val failedAccounts = when {
        record.status == CallStatus.SUCCESS ->
            record.attemptedAccounts.filter { it != finalName && it.isNotEmpty() }
        record.status == CallStatus.ABORTED -> emptyList()
        record.attemptedAccounts.isNotEmpty() ->
            record.attemptedAccounts.filter { it.isNotEmpty() }
        finalName.isNotEmpty() -> listOf(finalName)
        else -> emptyList()
    }

    // ---- 紧凑模式：滚动中只留一行（色条 + 模型 + 耗时 + 时间）----
    // 目的是一屏能扫过更多条记录；停手后自动恢复完整卡片。
    // 展开中的那条永远不走紧凑，否则用户点了展开却看到一行会很困惑。
    if (compact && !expanded) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggleExpand, onLongClick = onLongClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tone),
            )
            Text(
                text = record.model.ifBlank { "未指定模型" },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!ok) {
                Text(
                    text = if (record.httpStatus > 0) "${record.httpStatus}" else statusLabel(record.status),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = tone,
                    maxLines = 1,
                )
            }
            Text(
                text = formatDuration(record.durationMillis),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (record.totalTokens > 0) {
                Text(
                    text = formatTokens(record.totalTokens),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Text(
                text = relativeAgo(record.startedAtMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggleExpand, onLongClick = onLongClick),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 左侧竖色条：替代右上角状态徽章。省横向空间（徽章要占 40~50dp），
        // 而且竖条在列表里连成一片时，成功/失败的分布一眼可见。
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = 44.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(tone),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            // ---- 第一行：模型名 + 结果标签 ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // 模型名缺失（旧记录或异常写入路径）时给明确占位；纯空白串同样回落，
                    // 否则头部会出现「看着像什么都没渲染」的空白。
                    text = record.model.ifBlank { "未指定模型" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // 成功不挂标签（左侧绿条已经说明了）；失败挂状态码，中断挂「中断」。
                // 「上游 0」表示压根没打到上游，不显示——别让人以为上游回了 0。
                if (!ok) {
                    val label = when {
                        record.status == CallStatus.ABORTED -> "中断"
                        record.httpStatus > 0 -> "HTTP ${record.httpStatus}"
                        else -> statusLabel(record.status)
                    }
                    TagPill(text = label, tone = tone, dot = false)
                }
                if (record.modelBanned) {
                    TagPill(text = "禁用", tone = MaterialTheme.colorScheme.error, dot = false)
                }
            }

            // ---- 第二行：账号 / 路由 / 故障摘要（一行讲完，不再重复 N 次「故障」）----
            val accountLine = buildString {
                if (ok) {
                    if (crossRegion) append("故障转移 · ")
                    append(providerName)
                    if (finalName.isNotEmpty()) append(" · ").append(finalName)
                } else if (failedAccounts.isNotEmpty()) {
                    // 失败时把账号压成一行：`3 个账号均失败`，具体是谁在展开区
                    if (failedAccounts.size == 1) {
                        append("故障 · ").append(failedAccounts.first())
                    } else {
                        append(failedAccounts.size).append(" 个账号均失败")
                    }
                }
            }
            if (accountLine.isNotEmpty()) {
                Text(
                    text = accountLine,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (ok) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ---- 第三行：指标 + 相对时间（时间靠右，扫视时成列）----
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // 耗时放最前：它是「这次顺不顺」的第一直觉
                MetricChip("耗时", formatDuration(record.durationMillis), MaterialTheme.colorScheme.onSurfaceVariant)
                if (record.totalTokens > 0) {
                    MetricChip("tokens", formatTokens(record.totalTokens), MaterialTheme.colorScheme.primary)
                }
                if (record.cachedTokens > 0 && record.promptTokens > 0) {
                    val hitPercent = String.format(
                        Locale.US, "%.0f",
                        record.cachedTokens * 100.0 / record.promptTokens,
                    )
                    MetricChip("命中", "$hitPercent%", MaterialTheme.colorScheme.tertiary)
                }
                if (record.pointsConsumed > 0) {
                    MetricChip("积分", record.pointsConsumed.toString(), MaterialTheme.colorScheme.secondary)
                }
                // 「流式」是常态不显示；只有非流式才提一句——那才是异常情况
                if (!record.streaming) {
                    MetricChip("非流式", "", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // 相对时间：绝对时间在展开区，这里只要「多久以前」
                Text(
                    text = relativeAgo(record.startedAtMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }

            // ---- 错误摘要：折叠时只给一行，展开看全文 ----
            // ⚠️ 必须按字符截断，不能只给 maxLines：Compose 会为整段文本做换行测量，
            // 上游错误原文可能几千字符（整段 HTML / JSON），只靠 maxLines 照样卡主线程。
            if (record.error.isNotEmpty() && !expanded) {
                Text(
                    text = record.error.take(ERROR_PREVIEW_CHARS),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // ---- 展开区：完整信息 + 原文（按需加载）----
            if (expanded) {
                CallExpandedDetail(record, providerName, failedAccounts, finalName, loadFullText)
            }
        }
    }
}

/**
 * 展开后的详情区：完整账号链路、上游模型、错误全文、请求/响应/上游报文原文。
 *
 * 原文走 [loadFullText] 按需加载：概览卡片只持有截断摘要，
 * 完整原文可能几十万字符，不展开就不该读盘。
 */
@Composable
private fun CallExpandedDetail(
    record: CallRecord,
    providerName: String,
    failedAccounts: List<String>,
    finalName: String,
    loadFullText: suspend (io.synapse.core.usage.CallTextField) -> String?,
) {
    val clipboard = LocalClipboardManager.current
    var fullRequest by remember(record.id) { mutableStateOf<String?>(null) }
    var fullResponse by remember(record.id) { mutableStateOf<String?>(null) }
    var fullRaw by remember(record.id) { mutableStateOf<String?>(null) }
    var loaded by remember(record.id) { mutableStateOf(false) }

    // 展开时才加载；收起后再展开会复用已加载的内容（remember(record.id) 保活）
    LaunchedEffect(record.id) {
        if (loaded) return@LaunchedEffect
        val (rq, rs, raw) = withContext(Dispatchers.IO) {
            Triple(
                loadFullText(io.synapse.core.usage.CallTextField.REQUEST),
                loadFullText(io.synapse.core.usage.CallTextField.RESPONSE),
                loadFullText(io.synapse.core.usage.CallTextField.RAW),
            )
        }
        fullRequest = rq
        fullResponse = rs
        fullRaw = raw
        loaded = true
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // 账号链路：成功时给「最终账号 + 路上折了谁」，失败时给全部试过的。
        // ⚠️ 这一行是**历史快照**（attemptedAccounts 只存昵称、不存 uid），
        // 账号改名后不会跟着变；改名只影响「最终账号」那一行（按 uid 实时解析）。
        if (record.attemptedAccounts.isNotEmpty()) {
            DetailKv(
                "账号链路",
                record.attemptedAccounts.joinToString(" → "),
            )
        }
        if (record.status == CallStatus.SUCCESS && failedAccounts.isNotEmpty()) {
            DetailKv("失败过", failedAccounts.joinToString(" · "), MaterialTheme.colorScheme.error)
        }
        // 上游实际收到的模型名：只有与请求的不同才说（相同就是废话）
        if (record.resolvedModel.isNotEmpty() && record.resolvedModel != record.model) {
            DetailKv("上游模型", record.resolvedModel)
        }
        DetailKv("请求模型", record.model)
        DetailKv("供应商", providerName)
        if (finalName.isNotEmpty()) DetailKv("最终账号", finalName)
        DetailKv(
            "时间",
            buildString {
                append(TIME_FORMAT.format(Date(record.startedAtMillis)))
                append(if (record.streaming) " · 流式" else " · 非流式")
                append(" · HTTP ").append(record.httpStatus)
            },
        )
        if (record.promptTokens > 0 || record.completionTokens > 0) {
            DetailKv(
                "token",
                "输入 ${formatTokens(record.promptTokens)} / 输出 ${formatTokens(record.completionTokens)}" +
                    if (record.cachedTokens > 0) " / 命中 ${formatTokens(record.cachedTokens)}" else "",
            )
        }

        // 错误全文用可折叠块，而不是普通 DetailKv：
        // 上游错误原文可能几千字符，直接塞进 Text 会拖死换行测量。
        if (record.error.isNotEmpty()) {
            ExpandableTextBlock(
                title = "错误",
                body = record.error,
                note = null,
                onCopy = { clipboard.setText(AnnotatedString(record.error)) },
            )
        }

        // 三段原文：与浮窗一致，按需加载，可复制
        ExpandableTextBlock(
            title = "请求原文",
            body = fullRequest ?: record.requestBody,
            note = truncatedNote(fullRequest == null, record.requestBody),
            onCopy = { clipboard.setText(AnnotatedString(fullRequest ?: record.requestBody)) },
        )
        ExpandableTextBlock(
            title = "响应原文",
            body = fullResponse ?: record.responseBody,
            note = truncatedNote(fullResponse == null, record.responseBody),
            onCopy = { clipboard.setText(AnnotatedString(fullResponse ?: record.responseBody)) },
        )
        if (record.rawResponse.isNotEmpty() || fullRaw != null) {
            ExpandableTextBlock(
                title = "上游报文",
                body = fullRaw ?: record.rawResponse,
                note = truncatedNote(fullRaw == null, record.rawResponse),
                onCopy = { clipboard.setText(AnnotatedString(fullRaw ?: record.rawResponse)) },
            )
        }
    }
}

/** 展开区的一行「键：值」。 */
@Composable
private fun DetailKv(key: String, value: String, valueColor: Color? = null) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 展开区里可折叠的原文块。
 *
 * 默认只显示前几行（原文动辄几万字符，直接铺开会把卡片撑成一屏高），
 * 点标题展开全文。复制按钮始终可用，不需要先展开。
 */
/**
 * 展开区里可折叠的原文块。
 *
 * ## ⚠️ 必须按字符截断，不能只靠 maxLines
 *
 * 曾经用 `maxLines = 6` 限制显示行数，以为这样就只测量 6 行。**错**：
 * Compose 的 `Text` 会为**整段文本**做换行测量（`minikin::breakLineGreedy`），
 * `maxLines` 只是把测量结果裁掉，计算量一点没省。请求原文动辄几万到几十万字符，
 * 一展开就把主线程卡死 → ANR（崩溃栈全在 `libminikin.so` 的换行计算里）。
 *
 * 所以这里**先按字符数截断**，再交给 `Text`：
 * - 收起：只渲染前 [COLLAPSED_CHARS] 个字符
 * - 展开：每次多加载 [PAGE_CHARS] 个字符，用户点「继续加载」才继续
 *
 * 这样任何一次测量都只面对几万字符以内的文本，主线程不会被拖死。
 * 复制按钮始终拿**完整原文**（`body` 本身没有被截断）。
 */
@Composable
private fun ExpandableTextBlock(
    title: String,
    body: String,
    note: String?,
    onCopy: () -> Unit,
) {
    // 当前渲染到多少字符。key 用 body 保证换了记录就重置。
    var shownChars by remember(body) { mutableStateOf(COLLAPSED_CHARS) }
    val visible = body.take(shownChars)
    val hasMore = shownChars < body.length
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (hasMore) "${body.length} 字符" else "${body.length} 字符 · 全部",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
            Box(Modifier.weight(1f))
            Text(
                text = "复制",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text(
            text = visible,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hasMore) {
            val remaining = body.length - shownChars
            Text(
                text = if (remaining > PAGE_CHARS) {
                    "继续加载 $PAGE_CHARS 字符（还有 $remaining）▾"
                } else {
                    "展开剩余 $remaining 字符 ▾"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable { shownChars += PAGE_CHARS }
                    .padding(vertical = 3.dp),
            )
        }
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

/**
 * 收起时渲染的字符数。
 *
 * 取 2000：与旧 `DetailBlock` 的 `PREVIEW_CHARS` 一致，是实测在手机上
 * 既不卡（测量量可控）又能看到足够上下文的量级。
 */
private const val COLLAPSED_CHARS = 2_000

/** 每次「继续加载」新增的字符数。分页而不是一次全给，避免一次测量几十万字符。 */
private const val PAGE_CHARS = 20_000

/**
 * 概览层错误摘要的截断长度。
 *
 * 只显示一行，但**必须先截断**：`maxLines` 不会减少换行测量的计算量，
 * 一段几千字符的上游错误原文照样能把主线程拖到 ANR。
 */
private const val ERROR_PREVIEW_CHARS = 300

/**
 * 相对时间文案（`刚刚` / `3分钟前` / `2小时前` / `昨天` / `3天前`）。
 *
 * 列表里绝对时间（`11-19 04:28:20`）没法快速判断新旧，扫视时相对时间更有用；
 * 需要精确时刻时展开卡片就有。
 */
private fun relativeAgo(atMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val delta = nowMillis - atMillis
    if (delta < 0L) return "刚刚"
    return when {
        delta < 60_000L -> "刚刚"
        delta < 3_600_000L -> "${delta / 60_000L}分钟前"
        delta < 86_400_000L -> "${delta / 3_600_000L}小时前"
        delta < 172_800_000L -> "昨天"
        else -> "${delta / 86_400_000L}天前"
    }
}

/**
 * 状态胶囊：路由 / 故障转移 / 账号 / 故障。
 *
 * 与 [MetricChip] 的区别：这个是「一句话标签」，不强调数值，
 * 所以字号用 labelMedium（MetricChip 是 labelSmall 等宽数字，专给指标用）。
 */
@Composable
private fun TagPill(text: String, tone: Color, dot: Boolean) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(tone.copy(alpha = 0.12f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tone),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = tone,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 一条指标胶囊：等宽数字 + 标签，用于把 token/命中/耗时等压成一行可扫读的信息。
 *
 * 两个 Text 都禁止折行（maxLines = 1 + softWrap = false）：宽度不足时只会省略显示，
 * 绝不会折成多行把胶囊撑成方块（曾导致「积分」胶囊被挤窄后标签折行、外观像文字堆叠）。
 */
@Composable
private fun MetricChip(label: String, value: String, tone: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(tone.copy(alpha = 0.10f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = tone,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 状态徽章：成功 / 失败 / 中断三态都有稳定的底色与文字。
 *
 * 不能只留一个无底色的小字——一旦头部字段异常，那一行看起来就像完全没渲染。
 */
@Composable
private fun StatusBadge(status: CallStatus) {
    val tone = statusColor(status)
    Text(
        text = statusLabel(status),
        style = MaterialTheme.typography.labelSmall,
        color = tone,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(tone.copy(alpha = 0.10f))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}


/**
 * 字段是否被截断的说明行：溢出文件缺失时，长度超过摘要阈值即视为「存储中为摘要」。
 */
private fun truncatedNote(fullMissing: Boolean, summary: String): String? = when {
    !fullMissing -> null
    summary.length >= io.synapse.core.usage.CallLogStore.MAX_SUMMARY_CHARS ->
        "内容较长，仅保留前 ${io.synapse.core.usage.CallLogStore.MAX_SUMMARY_CHARS} 字符摘要（完整原文已被清理或本次未启用完整保存）"
    else -> null
}

/**
 * 排查诊断：详细日志开关。开启后记录每次调用的请求、转发、发送与返回原文，用于排查 503 类问题。
 */
@Composable
private fun DiagnosticsSettings(state: AppUiState, viewModel: AppViewModel) {
    SectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("详细日志", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "记录每次调用的请求、转发、发送与返回原文，用于排查 503 等问题；关闭后立即生效",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = state.settings.verboseLogging,
                onCheckedChange = {
                    viewModel.updateSettings(state.settings.copy(verboseLogging = it))
                },
            )
        }
    }
}

/**
 * 调用记录的参数设置：归档开关、保留天数、清空。
 *
 * 与请求日志、详细日志并列展示在同一面板内——面板内容不随标签变化。
 */
@Composable
private fun CallLogSettings(state: AppUiState, viewModel: AppViewModel) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("记录调用", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "保存每次调用的请求/响应原文与用量；关闭后不再新增（已存记录不受影响）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.settings.callLoggingEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings(state.settings.copy(callLoggingEnabled = it))
                    },
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text(
                text = "服务运行时每日自动清理超过该天数的记录，当前 ${state.settings.logRetentionDays} 天。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RetentionField(state.settings.logRetentionDays) { viewModel.setLogRetentionDays(it) }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text("清空记录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "删除本机保存的全部调用记录（含完整原文），不可恢复。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var confirmClear by remember { mutableStateOf(false) }
            OutlineActionButton("清空调用记录") { confirmClear = true }
            if (confirmClear) {
                ConfirmDestructiveDialog(
                    title = "清空全部调用记录？",
                    body = "将删除本机保存的 ${state.storedCalls} 条调用记录（含完整原文），不可恢复。",
                    confirmText = "清空",
                    onConfirm = { viewModel.clearCalls() },
                    onDismiss = { confirmClear = false },
                )
            }
        }
    }
}

/**
 * 保留天数输入。
 *
 * 失焦或回车才提交，避免边输入边保存把「30」中间的「3」当成 30 天。越界值由 core 夹回，
 * 这里只做即时提示。
 */
@Composable
fun RetentionField(current: Int, onCommit: (Int) -> Unit) {
    var text by remember(current) { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in GatewaySettings.MIN_RETENTION_DAYS..GatewaySettings.MAX_RETENTION_DAYS
    val changed = parsed != null && parsed != current

    Text(
        text = "保留天数（${GatewaySettings.MIN_RETENTION_DAYS}-${GatewaySettings.MAX_RETENTION_DAYS}）",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
            shape = MaterialTheme.shapes.medium,

        value = text,
        onValueChange = { input -> text = input.filter(Char::isDigit).take(4) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = text.isNotEmpty() && !valid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        supportingText = {
            when {
                text.isEmpty() -> Text("不能为空")
                !valid -> Text("需在 ${GatewaySettings.MIN_RETENTION_DAYS}-${GatewaySettings.MAX_RETENTION_DAYS} 之间")
            }
        },
    )
    if (changed) {
        OutlineActionButton("保存保留天数") { onCommit(parsed) }
    }
}

/**
 * 请求日志的参数设置：每项参数一个控件，展开后直接调整，不折叠到弹窗里。
 *
 * 与调用记录、详细日志并列展示在同一面板内——面板内容不随标签变化。
 */
@Composable
private fun RequestLogSettings(state: AppUiState, viewModel: AppViewModel) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("日志容量", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "环形缓冲最多保留的日志条数，超出后自动丢弃最旧一条；调大占用更多内存与存储。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LogCapacityField(state.settings.requestLogCapacity) {
                viewModel.updateSettings(state.settings.copy(requestLogCapacity = it))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Text("清空日志", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "删除当前全部请求日志，容量等设置不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var confirmClearLogs by remember { mutableStateOf(false) }
            OutlineActionButton("清空请求日志") { confirmClearLogs = true }
            if (confirmClearLogs) {
                ConfirmDestructiveDialog(
                    title = "清空全部请求日志？",
                    body = "将删除当前所有请求日志（容量等设置不受影响），不可恢复。",
                    confirmText = "清空",
                    onConfirm = { viewModel.clearLogs() },
                    onDismiss = { confirmClearLogs = false },
                )
            }
        }
    }
}


private fun statusLabel(status: CallStatus): String = when (status) {
    CallStatus.SUCCESS -> "成功"
    CallStatus.FAILED -> "失败"
    CallStatus.ABORTED -> "中断"
}

@Composable
private fun levelColor(level: String): Color = when (level.uppercase()) {
    "WARN" -> WarningAmber
    "ERROR" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

/** 日志容量输入：失焦/回车不适用（按钮提交），越界由 core 夹回。 */
@Composable
private fun LogCapacityField(current: Int, onCommit: (Int) -> Unit) {
    var text by remember(current) { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null &&
        parsed in io.synapse.core.gateway.GatewaySettings.MIN_REQUEST_LOG_CAPACITY..
        io.synapse.core.gateway.GatewaySettings.MAX_REQUEST_LOG_CAPACITY
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            shape = MaterialTheme.shapes.medium,

            value = text,
            onValueChange = { input -> text = input.filter(Char::isDigit).take(4) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("请求日志容量（${io.synapse.core.gateway.GatewaySettings.MIN_REQUEST_LOG_CAPACITY}-${io.synapse.core.gateway.GatewaySettings.MAX_REQUEST_LOG_CAPACITY} 条）") },
            isError = text.isNotEmpty() && !valid,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
        )
        if (parsed != null && valid && parsed != current) {
            OutlineActionButton("保存容量") { onCommit(parsed) }
        }
    }
}

@Composable
private fun statusColor(status: CallStatus): Color = when (status) {
    CallStatus.SUCCESS -> MaterialTheme.colorScheme.tertiary
    CallStatus.FAILED -> MaterialTheme.colorScheme.error
    CallStatus.ABORTED -> WarningAmber
}

private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
