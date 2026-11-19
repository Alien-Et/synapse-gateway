package io.synapse.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.synapse.app.ui.theme.LocalAppColors
import io.synapse.core.gateway.ActiveRequest
import kotlin.math.abs
import kotlin.math.min

// ---------------------------------------------------------------------------
// 实时调度拓扑（三级树：客户端 → 网关 → 供应商 → 账号）
//
// 设计约束（与网页预览版一致）：
// 1. 竖屏为主，一级一行，层级靠左缩进：网关 52dp、供应商 96dp、账号 150dp；
// 2. 客户端横排，位置不够自动换行（FlowRow）；
// 3. 卡片是「标签」形（胶囊、宽度随内容），该长就长该短就短；
// 4. 客户端→网关走漏斗（垂直进出）；网关→供应商走「一条主干 + 每家一条分枝」；
//    并发走不同供应商时，各条活跃流从网关底部就沿主干下行，彩色虚线叠在主干上；
// 5. 线宽/透明度分层：客户端→网关 最细，网关→供应商 最粗（主干），供应商→账号 最细；
// 6. 活跃路径跑水流动效 + 光点；活跃节点外发光并做脉冲扩散（「被注入」的感觉）。
//
// 坐标采集：所有节点用 onGloballyPositioned 记下自己的 LayoutCoordinates，
// 绘制时统一换算到卡片内 Box 的本地坐标（localPositionOf），这样嵌套多深都不影响；
// 也因为用的是本地坐标，页面滚动不会引起数值抖动。
// ---------------------------------------------------------------------------

private const val INDENT_GATEWAY = 52
private const val INDENT_PROVIDER = 96
private const val INDENT_ACCOUNT = 150

/** 静止连线的统一规格：一条中性细线。粗和颜色都只属于「正在流」。 */
private val IDLE_WIDTH = 1.0.dp
private const val IDLE_ALPHA = 0.30f

/** 拓扑里的一个客户端（下游调用方）。 */
private data class TopoClient(
    val name: String,
    val active: Int,
)

/** 拓扑里的一个账号节点。 */
private data class TopoAccount(
    val uid: String,
    val label: String,
    val usable: Boolean,
    val dead: Boolean,
    val credits: Long,
    val creditsKnown: Boolean,
    val active: Int,
)

/** 拓扑里的一个供应商节点（带它的全部账号）。 */
private data class TopoProvider(
    val id: String,
    val name: String,
    val colorIndex: Int,
    val active: Int,
    val usable: Int,
    val total: Int,
    val accounts: List<TopoAccount>,
)

private object TopoKey {
    const val GATEWAY = "gw"
    fun client(name: String) = "c|$name"
    fun provider(id: String) = "p|$id"
    fun account(pid: String, uid: String) = "a|$pid|$uid"
}

/** 认不出 UA 时统一的占位名；界面里单独给灰色，避免和真实客户端混淆。 */
private const val UNKNOWN_CLIENT = "未识别"

/**
 * 客户端配色：**不维护名字表**，按名字哈希从主题调色板里取。
 *
 * 客户端是「谁在调」这种随时会变的东西，硬编码一份 Cursor/Cline/… 的颜色表，
 * 等于给每个新客户端都留一个待补的分支；自动取色反而不会漏。
 */
private fun clientColor(name: String, palette: List<Color>): Color {
    if (name == UNKNOWN_CLIENT) return Color(0xFF9AA0A6)
    if (palette.isEmpty()) return Color(0xFF9AA0A6)
    return palette[abs(name.hashCode()) % palette.size]
}

/**
 * 客户端名的显示清洗：走 core 的 [ClientNames.normalize]，与网关写入时同一套规则。
 *
 * 早期版本没有规则表，历史记录里存的是整条 UA（如 `SomeClient-Android/3.3.0`），
 * 一条就能占掉半行；再跑一次规则就能收敛成正名，同一个客户端也不会分裂成两个节点。
 */
private fun cleanClientName(raw: String): String =
    io.synapse.core.gateway.ClientNames.normalize(raw).ifEmpty { UNKNOWN_CLIENT }

/**
 * 汇总客户端层：**只看当前在途请求**。
 *
 * 不去翻历史记录补位——静止时这一层就该是空的（界面显示「下游无客户端」），
 * 而不是拿几十次之前的调用记录假装「现在有人连着」。
 */
private fun buildClients(active: List<ActiveRequest>): List<TopoClient> {
    val counts = LinkedHashMap<String, Int>()
    active.forEach { req ->
        val name = cleanClientName(req.clientName)
        counts[name] = (counts[name] ?: 0) + 1
    }
    return counts.map { (name, n) -> TopoClient(name = name, active = n) }
        .sortedWith(
            compareByDescending<TopoClient> { it.active }
                .thenBy { it.name == UNKNOWN_CLIENT }
                .thenBy { it.name },
        )
}

/**
 * 汇总供应商/账号层：只显示**真实有账号**的供应商；
 * 有在途请求的排前面，其次可用账号多的。
 */
private fun buildProviders(state: AppUiState, active: List<ActiveRequest>): List<TopoProvider> {
    if (state.accounts.isEmpty()) return emptyList()
    val activeByProvider = active.groupBy { it.providerId.lowercase() }.mapValues { it.value.size }
    val activeByAccount = active.groupBy { "${it.providerId.lowercase()}/${it.uid}" }.mapValues { it.value.size }

    return state.accounts
        .groupBy { it.providerId.lowercase() }
        .map { (pid, accs) ->
            val info = state.providers.firstOrNull { it.id.equals(pid, ignoreCase = true) }
            val accounts = accs
                .map { acc ->
                    TopoAccount(
                        uid = acc.uid,
                        label = acc.customName.ifEmpty {
                            acc.nickname.ifEmpty {
                                if (acc.uid.length > 8) acc.uid.take(6) + "…" else acc.uid
                            }
                        },
                        usable = acc.usable,
                        dead = acc.disabled,
                        credits = acc.credits,
                        creditsKnown = acc.creditsKnown,
                        active = activeByAccount["$pid/${acc.uid}"] ?: 0,
                    )
                }
                .sortedWith(
                    compareByDescending<TopoAccount> { it.active > 0 }
                        .thenByDescending { it.active }
                        .thenByDescending { it.usable },
                )
            TopoProvider(
                id = pid,
                name = info?.displayName?.ifEmpty { pid } ?: pid,
                colorIndex = 0,
                active = activeByProvider[pid] ?: 0,
                usable = accs.count { it.usable },
                total = accs.size,
                accounts = accounts,
            )
        }
        .sortedWith(compareByDescending<TopoProvider> { it.active }.thenByDescending { it.usable })
        .mapIndexed { index, provider -> provider.copy(colorIndex = index) }
}

/**
 * 实时调度拓扑卡。
 *
 * 数据全部来自 [AppUiState]：`activeRequests` 提供在途（谁在用、用到哪个账号），
 * `accounts`/`providers` 提供静态结构，`calls` 提供客户端画像。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveRoutingTopologyCard(state: AppUiState, modifier: Modifier = Modifier) {
    val palette = LocalAppColors.current.providerPalette
    val gold = LocalAppColors.current.goldStart
    // 主干是结构线、不参与数据语义，用中性色（不是主题金）：金色留给「有流量」。
    val trunkColor = MaterialTheme.colorScheme.onSurfaceVariant
    val running = state.running
    val active = state.activeRequests

    val clients = remember(active) { buildClients(active) }
    val providers = remember(state.accounts, state.providers, active) { buildProviders(state, active) }

    // 流动相位与脉冲相位：只在绘制阶段读取，不逐帧重组。
    val transition = rememberInfiniteTransition(label = "topo")
    val flow by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "topoFlow",
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Restart),
        label = "topoPulse",
    )

    val coords = remember { mutableStateMapOf<String, LayoutCoordinates>() }
    var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    val gatewayCharged = running && active.isNotEmpty()

    SectionCard(modifier = modifier) {
        // ---- 卡片头：标题 + 运行态 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "实时调度拓扑",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "LIVE ROUTING",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = when {
                    !running -> "已停止"
                    active.isEmpty() -> "空闲"
                    else -> "${active.size} 在途"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    !running -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    active.isEmpty() -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> gold
                },
            )
        }

        if (providers.isEmpty()) {
            Text(
                text = "还没有可用的账号，先到「供应商」里登录一个。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .onGloballyPositioned { rootCoords = it },
        ) {
            // 连线层：放在内容下面，卡片本体是实心的，线不会盖到字上。
            Canvas(modifier = Modifier.matchParentSize()) {
                drawTopology(
                    root = rootCoords,
                    coords = coords,
                    clients = clients,
                    providers = providers,
                    palette = palette,
                    flow = flow,
                    pulse = pulse,
                    running = running,
                    gatewayCharged = gatewayCharged,
                    trunkColor = trunkColor,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // ---- 一级：客户端（横排，自动换行）----
                if (clients.isEmpty()) {
                    // 静止时下游就是「没有人连着」——不用历史记录假装有人。
                    TopoChip(
                        text = "下游无客户端",
                        meta = null,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        charged = false,
                        dim = true,
                        key = "c|none",
                        coords = coords,
                        small = true,
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        clients.forEach { client ->
                            val color = clientColor(client.name, palette)
                            TopoChip(
                                text = client.name,
                                meta = if (client.active > 0) "${client.active}" else null,
                                color = color,
                                charged = running && client.active > 0,
                                dim = !running,
                                key = TopoKey.client(client.name),
                                coords = coords,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))

                // ---- 二级：网关 ----
                Box(Modifier.padding(start = INDENT_GATEWAY.dp)) {
                    GatewayChip(
                        running = running,
                        charged = gatewayCharged,
                        key = TopoKey.GATEWAY,
                        coords = coords,
                    )
                }

                // ---- 三级/四级：供应商 + 账号 ----
                providers.forEach { provider ->
                    val color = palette[provider.colorIndex % palette.size]
                    Box(Modifier.padding(start = INDENT_PROVIDER.dp, top = 10.dp)) {
                        TopoChip(
                            text = provider.name,
                            meta = "${provider.usable}/${provider.total}" +
                                if (provider.active > 0) " · ${provider.active} 在途" else "",
                            color = color,
                            charged = running && provider.active > 0,
                            dim = !running && provider.active == 0,
                            key = TopoKey.provider(provider.id),
                            coords = coords,
                            strong = true,
                        )
                    }
                    provider.accounts.forEach { account ->
                        Box(Modifier.padding(start = INDENT_ACCOUNT.dp)) {
                            TopoChip(
                                text = account.label,
                                meta = when {
                                    account.active > 0 -> "${account.active} 在途"
                                    account.creditsKnown -> formatCredits(account.credits)
                                    else -> null
                                },
                                color = color,
                                charged = running && account.active > 0,
                                dim = account.dead || !account.usable,
                                key = TopoKey.account(provider.id, account.uid),
                                coords = coords,
                                small = true,
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )

        // 文字版路由流水：拓扑给「结构」，这里给「最近一次到底走了哪条路」。
        // 供应商名/账号名都动态解析，不用记录里的历史快照——改了名这里要跟着变。
        RecentCallRoutingFlow(
            call = state.calls.firstOrNull(),
            providerLabel = { id -> state.providerOf(id)?.displayName ?: id },
            accountLabel = { record ->
                state.accounts
                    .firstOrNull { it.providerId == record.providerId && it.uid == record.accountUid }
                    ?.displayName
                    ?: record.accountNickname.ifEmpty { record.accountUid.ifEmpty { "默认账号" } }
            },
        )

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )

        ContextCapacityAndModelBreakdown(state)
    }
}

/** 网关节点：一个普通标签，只写「AI 中枢」四个字——端口和计数都另有地方看。 */
@Composable
private fun GatewayChip(
    running: Boolean,
    charged: Boolean,
    key: String,
    coords: MutableMap<String, LayoutCoordinates>,
) {
    val gold = LocalAppColors.current.goldStart
    val tint = if (running) gold else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .onGloballyPositioned { coords[key] = it }
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (running) gold.copy(alpha = if (charged) 0.18f else 0.10f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            )
            .border(
                width = 1.dp,
                color = tint.copy(alpha = if (charged) 0.6f else 0.3f),
                shape = RoundedCornerShape(percent = 50),
            )
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(tint.copy(alpha = if (running) 0.95f else 0.4f)),
        )
        Text(
            text = "AI 中枢",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 通用标签节点。
 *
 * 宽度随内容（胶囊形），左侧一条 3dp 竖色条作为层级/归属标识；
 * 活跃时底色和描边一起提亮，配合 Canvas 里的外发光读起来就是「正在被注入流量」。
 */
@Composable
private fun TopoChip(
    text: String,
    meta: String?,
    color: Color,
    charged: Boolean,
    dim: Boolean,
    key: String,
    coords: MutableMap<String, LayoutCoordinates>,
    strong: Boolean = false,
    small: Boolean = false,
) {
    val barAlpha = when {
        charged -> 1f
        dim -> 0.3f
        strong -> 0.85f
        else -> 0.6f
    }
    Row(
        modifier = Modifier
            .onGloballyPositioned { coords[key] = it }
            .clip(RoundedCornerShape(percent = 50))
            .background(
                color.copy(alpha = if (charged) 0.16f else if (dim) 0.05f else 0.08f),
            )
            .border(
                width = 1.dp,
                color = color.copy(alpha = if (charged) 0.55f else if (dim) 0.14f else 0.26f),
                shape = RoundedCornerShape(percent = 50),
            )
            .padding(
                horizontal = if (small) 9.dp else 11.dp,
                vertical = if (small) 3.dp else 4.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = if (small) 10.dp else 12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color.copy(alpha = barAlpha)),
        )
        Text(
            text = text,
            style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            fontSize = if (small) 11.sp else 12.sp,
            fontWeight = if (charged || strong) FontWeight.SemiBold else FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dim) 0.45f else 0.92f),
            maxLines = 1,
            // 账号名可能是邮箱（my-melody1@live.com），缩进到 150dp 后放不下；
            // 省略号比直接裁掉半截字更可读。
            overflow = TextOverflow.Ellipsis,
        )
        if (!meta.isNullOrEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = color.copy(alpha = if (dim) 0.5f else 0.95f),
                maxLines = 1,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 绘制层
// ---------------------------------------------------------------------------

/**
 * 画三级连线 + 活跃节点外发光。
 *
 * 走线结构：
 * ```
 *  [Cursor] [Cline]        ← 客户端横排
 *      ╲     ╱             ← 漏斗：垂直进出网关顶边
 *     [ AI 中枢 ]           ← 网关
 *      ╲│╱                  ← 一把「绳子」挂在网关底部，只错开一点点
 *       ╲                    ← 往下慢慢荡开
 *  [讯飞]  [灵犀]  [字节]
 *    └─ 账号  └─ 账号         ← 小钩
 * ```
 *
 * 走线的形状照用户的手绘来：**几根自然下垂的绳子**，不是「一条直线主干再分叉」。
 * 每条线从网关底部几乎同一个点出发、先近乎垂直地坠下去，快到目标节点高度时才横着荡过去——
 * 重力拉出来的那道弧，比电路板的直角分叉自然得多。
 */
private fun DrawScope.drawTopology(
    root: LayoutCoordinates?,
    coords: Map<String, LayoutCoordinates>,
    clients: List<TopoClient>,
    providers: List<TopoProvider>,
    palette: List<Color>,
    flow: Float,
    pulse: Float,
    running: Boolean,
    gatewayCharged: Boolean,
    trunkColor: Color,
) {
    val rootCoords = root ?: return
    if (palette.isEmpty()) return

    fun rectOf(key: String): Rect? {
        val node = coords[key] ?: return null
        return runCatching {
            val p = rootCoords.localPositionOf(node, Offset.Zero)
            Rect(p.x, p.y, p.x + node.size.width, p.y + node.size.height)
        }.getOrNull()
    }

    // 服务停了就把结构压暗：树还在（看得到配置了什么），但不该比运行中还抢眼。
    val fade = if (running) 1f else 0.55f

    val gateway = rectOf(TopoKey.GATEWAY)

    // ---- 活跃节点外发光（先画，压在最底下）----
    if (running) {
        clients.forEach { client ->
            if (client.active <= 0) return@forEach
            val r = rectOf(TopoKey.client(client.name)) ?: return@forEach
            drawChargedGlow(r, clientColor(client.name, palette), pulse)
        }
        if (gateway != null) drawChargedGlow(gateway, palette[0], pulse, animated = gatewayCharged)
        providers.forEach { provider ->
            val color = palette[provider.colorIndex % palette.size]
            if (provider.active > 0) {
                rectOf(TopoKey.provider(provider.id))?.let { drawChargedGlow(it, color, pulse) }
            }
            provider.accounts.forEach { account ->
                if (account.active <= 0) return@forEach
                rectOf(TopoKey.account(provider.id, account.uid))?.let { drawChargedGlow(it, color, pulse) }
            }
        }
    }

    // ---- 客户端 → 网关：漏斗，垂直进出，不会横着绕一圈 ----
    if (gateway != null) {
        clients.forEachIndexed { index, client ->
            val r = rectOf(TopoKey.client(client.name)) ?: return@forEachIndexed
            val maxSpread = (gateway.width - 16.dp.toPx()).coerceAtLeast(0f)
            val spread = min(maxSpread, 14.dp.toPx() + index * 12.dp.toPx())
            val from = Offset(r.left + r.width * 0.34f, r.bottom)
            val to = Offset(gateway.left + spread, gateway.top)
            if (to.y - from.y < 3.dp.toPx()) return@forEachIndexed
            val dy = to.y - from.y
            val path = Path().apply {
                moveTo(from.x, from.y)
                cubicTo(from.x, from.y + dy * 0.45f, to.x, to.y - dy * 0.45f, to.x, to.y)
            }
            val on = running && client.active > 0
            strokeWire(
                path = path,
                // 颜色只属于「正在流」：没有流量的线一律中性细线，不按供应商/客户端上色。
                color = if (on) clientColor(client.name, palette) else trunkColor,
                width = if (on) 1.4.dp.toPx() else IDLE_WIDTH.toPx(),
                alpha = (if (on) 0.5f else IDLE_ALPHA) * fade,
                flow = flow,
                stagger = index,
                active = on,
            )
        }
    }

    // ---- 网关 → 供应商：几根自然下垂的绳子，各自荡到旁边的供应商 ----
    if (gateway != null) {
        val firstX = gateway.left + 10.dp.toPx()
        // 起点完全重合：几条线在网关下方「黏」成一条，越往下才越分得开，
        // 于是上半段读起来是一根绳子、下半段是一条一条的股。
        val step = 0f
        providers.forEachIndexed { index, provider ->
            val rect = rectOf(TopoKey.provider(provider.id)) ?: return@forEachIndexed
            val color = palette[provider.colorIndex % palette.size]
            val active = running && provider.active > 0
            val sx = firstX + index * step
            val sy = gateway.bottom
            val py = rect.center.y
            val px = rect.left
            if (py - sy < 3.dp.toPx()) return@forEachIndexed
            val dy = py - sy
            val path = Path().apply {
                moveTo(sx, sy)
                // 绳子的感觉：先几乎垂直坠下去（控制点拉得很低），快到节点高度时才横着荡过去。
                // 不要「先竖后横」的直角，那样是电路板；要的是重力拉出来的那一道弧。
                cubicTo(sx, sy + dy * 0.58f, px - 18.dp.toPx(), py, px, py)
            }
            strokeWire(
                path = path,
                color = if (active) color else trunkColor,
                width = if (active) 3.0.dp.toPx() else IDLE_WIDTH.toPx(),
                alpha = (if (active) 0.85f else IDLE_ALPHA) * fade,
                flow = flow,
                stagger = index,
                active = active,
            )
        }
    }

    // ---- 供应商 → 账号：小钩子 ----
    providers.forEach { provider ->
        val color = palette[provider.colorIndex % palette.size]
        val providerRect = rectOf(TopoKey.provider(provider.id)) ?: return@forEach
        provider.accounts.forEachIndexed { index, account ->
            val accRect = rectOf(TopoKey.account(provider.id, account.uid)) ?: return@forEachIndexed
            val from = Offset(
                providerRect.left + min(
                    (providerRect.width - 10.dp.toPx()).coerceAtLeast(0f),
                    12.dp.toPx() + index * 6.dp.toPx(),
                ),
                providerRect.bottom,
            )
            val to = Offset(accRect.left, accRect.center.y)
            if (to.y - from.y < 3.dp.toPx()) return@forEachIndexed
            val midY = from.y + (to.y - from.y) * 0.55f
            val path = Path().apply {
                moveTo(from.x, from.y)
                cubicTo(from.x, midY, to.x - 18.dp.toPx(), to.y, to.x, to.y)
            }
            val on = running && account.active > 0
            strokeWire(
                path = path,
                color = if (on) color else trunkColor,
                width = if (on) 1.3.dp.toPx() else IDLE_WIDTH.toPx(),
                alpha = (if (on) 0.6f else IDLE_ALPHA) * fade,
                flow = flow,
                stagger = index,
                active = on,
                dashOn = 2f,
                dashOff = 5f,
            )
        }
    }
}

/**
 * 一条线。
 *
 * **静止线是实线**——虚线只属于「正在流」。这一点跟网页版对齐：全是虚线会显得断断续续、
 * 很散（用户原话「虚线有点孤独」），而实线在低透明度下反而像一条安静的光纤。
 *
 * 活跃线才切虚线 + 外发光（等价于网页版的 `drop-shadow(0 0 5px)`）+ 沿路径滑的光点。
 *
 * [stagger] 错开每条活跃线的虚线相位：所有线同相位时虚线间隙会对齐，
 * 十几条叠在一起就糊成一条。
 */
private fun DrawScope.strokeWire(
    path: Path,
    color: Color,
    width: Float,
    alpha: Float,
    flow: Float,
    stagger: Int,
    active: Boolean,
    dashOn: Float = 4f,
    dashOff: Float = 6f,
) {
    if (!active) {
        drawPath(path, color.copy(alpha = alpha), style = Stroke(width = width, cap = StrokeCap.Round))
        return
    }
    // 外发光：宽而淡的一层垫在下面，读起来是「这条线在发亮」而不是「这条线更粗」。
    drawPath(path, color.copy(alpha = 0.12f), style = Stroke(width = width * 3.6f, cap = StrokeCap.Round))
    drawPath(
        path,
        color.copy(alpha = alpha),
        style = Stroke(
            width = width,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                intervals = floatArrayOf(dashOn.dp.toPx(), dashOff.dp.toPx()),
                phase = -flow * 10.dp.toPx() + stagger * 2.5.dp.toPx(),
            ),
        ),
    )

    // 光点：沿路径滑，头亮尾淡，读起来像水被推着走。
    val measure = PathMeasure()
    measure.setPath(path, false)
    val length = measure.length
    if (length <= 0f) return
    val head = ((flow + stagger * 0.19f) % 1f) * length
    measure.getPosition(head).let { p ->
        drawCircle(color.copy(alpha = 0.14f), radius = (width * 3.2f).coerceAtLeast(3.dp.toPx()), center = p)
        drawCircle(color.copy(alpha = 0.95f), radius = (width * 1.5f).coerceAtLeast(1.6.dp.toPx()), center = p)
    }
    val tail = (head - length * 0.16f + length) % length
    measure.getPosition(tail).let { p ->
        drawCircle(color.copy(alpha = 0.5f), radius = (width * 0.9f).coerceAtLeast(1.dp.toPx()), center = p)
    }
}

/**
 * 「被注入」的外发光：三层静态扩散 + 一层随时间向外散开的脉冲。
 *
 * 用圆角矩形（半径取高度一半）贴合胶囊形状；透明度压得很低，
 * 靠层数堆出柔和边缘，比单层高透明度更像光而不是色块。
 */
private fun DrawScope.drawChargedGlow(rect: Rect, color: Color, pulse: Float, animated: Boolean = true) {
    val radius = CornerRadius(rect.height / 2f)
    for (i in 1..3) {
        val e = (i * 2.2f).dp.toPx()
        drawRoundRect(
            color = color.copy(alpha = 0.085f / i),
            topLeft = Offset(rect.left - e, rect.top - e),
            size = Size(rect.width + e * 2, rect.height + e * 2),
            cornerRadius = CornerRadius(radius.x + e),
        )
    }
    // 只有真的在跑请求时才让脉冲扩散：空闲时网关也常亮光晕表示「服务在跑」，
    // 但一直扩散会让人以为有流量，反而误读。
    if (!animated) return
    val e = (1.5f + 9f * pulse).dp.toPx()
    drawRoundRect(
        color = color.copy(alpha = 0.18f * (1f - pulse)),
        topLeft = Offset(rect.left - e, rect.top - e),
        size = Size(rect.width + e * 2, rect.height + e * 2),
        cornerRadius = CornerRadius(radius.x + e),
    )
}
