package io.synapse.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.ReceiptLong
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 底部导航栏 —— DeepSeek 风格的**纯色扁平**设计，配色与 AppBar 标题栏完全同源。
 *
 * ## 配色为什么全部来自 MaterialTheme
 *
 * 标题栏（[PageHeader]）的配色就三样：
 * - 底色 `colorScheme.background`
 * - 标题 `colorScheme.onBackground`（金色装饰线单独取 LocalAppColors）
 * - 副标题 `colorScheme.onSurfaceVariant`
 *
 * 导航栏**一个自定义颜色都不写**，同样只从 colorScheme 取。这带来两个保证：
 * 1. 导航栏与标题栏在深浅色下**永远一致**——主题切浅色两者同时变浅，切深色同时变深，
 *    不可能再出现「App 设了浅色、底栏却还是黑的」（上一版自己判断系统明暗导致的 bug）；
 * 2. 用户自定义金色/动态取色时，导航栏的选中色自动跟着主题变，无需单独适配。
 *
 * ## 视觉
 *
 * DeepSeek 风格 = **不透明纯色 + 无阴影 + 细分割线 + 扁平高亮**：
 * - 背景与页面同色（`background`），不再用半透明玻璃；
 * - 与内容之间只有一条 `outlineVariant` 细线，不加阴影不加圆角（贴底直角）；
 * - 选中项 = `primaryContainer` 圆角胶囊 + `primary` 图标文字；
 * - 未选中 = `onSurfaceVariant`。
 *
 * ## 响应式
 *
 * 手机（<600dp）常驻贴底；平板/桌面换成左侧 [AppNavRail]（可折叠/隐藏），
 * 由 `MainActivity` 按 `isCompactWidth()` 分派。
 */
/**
 * 以 600dp 为界判断「手机 / 桌面」。
 *
 * 用 LocalConfiguration 而不是 BoxWithConstraints：配置变化（横竖屏、分屏、折叠屏展开）
 * 会触发重组，导轨/底栏自动切换。600dp 是 Material 的 compact/medium 分界。
 */
@Composable
fun isCompactWidth(): Boolean = LocalConfiguration.current.screenWidthDp < 600

// ---------------------------------------------------------------- 数据

/** 一个导航项：图标 + 短标签。 */
data class NavItem(
    val label: String,
    val icon: ImageVector,
)

/** 桌面/平板侧栏的三态。 */
enum class NavRailMode {
    /** 只显示图标（窄条）。 */
    COLLAPSED,

    /** 图标 + 标签（宽条）。 */
    EXPANDED,

    /** 完全隐藏，内容区留一个恢复按钮。 */
    HIDDEN,
}

/** 本 App 的五个导航项。 */
object AppNavItems {
    val all = listOf(
        NavItem("首页", Icons.Rounded.Dashboard),
        NavItem("供应商", Icons.Rounded.Hub),
        NavItem("模型", Icons.Rounded.Bolt),
        NavItem("记录", Icons.Rounded.ReceiptLong),
        NavItem("我的", Icons.Rounded.AutoAwesome),
    )
}

// ---------------------------------------------------------------- 单项

@Composable
private fun NavItemCell(
    item: NavItem,
    active: Boolean,
    horizontal: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 全部来自主题：与标题栏文字/副标题/金色装饰同一套语义
    val tint by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "navTint",
    )
    val pill by animateColorAsState(
        targetValue = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        label = "navPill",
    )
    val pillWidth by animateDpAsState(
        targetValue = if (horizontal) 148.dp else 58.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "navPillWidth",
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(pill)
            .clickable(
                onClick = onClick,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (horizontal) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .width(pillWidth)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(21.dp))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = tint,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .width(pillWidth)
                    .padding(vertical = 5.dp),
            ) {
                Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(21.dp))
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 移动端：贴底 bar

/**
 * 移动端贴底导航栏：纯色、扁平、与标题栏同色。
 *
 * 与上一版（玻璃）的差异：不透明背景、零阴影、零圆角，只用一条
 * `outlineVariant` 细线与内容分隔——这正是 DeepSeek / Telegram 桌面版那种
 * 「底栏是窗口的一部分」的扁平感，而不是浮在内容上的卡片。
 */
@Composable
fun AppBottomNavBar(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
    ) {
        // 与内容的分隔线：和卡片描边同一颜色，深浅色自动适配
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEachIndexed { index, item ->
                NavItemCell(
                    item = item,
                    active = index == selectedIndex,
                    horizontal = false,
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f).height(54.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 桌面端：可折叠侧栏

/**
 * 桌面/平板侧栏：折叠（只图标）/ 展开（图标+标签）/ 隐藏三态。
 * 配色同样全走主题，与底栏/标题栏一体。
 */
@Composable
fun AppNavRail(
    items: List<NavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    mode: NavRailMode,
    onModeChange: (NavRailMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = mode == NavRailMode.EXPANDED
    val width by animateDpAsState(
        targetValue = if (expanded) 172.dp else 72.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy),
        label = "railWidth",
    )

    Column(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 右侧细分割线：与内容的边界（贴底 bar 是横线，rail 是竖线，同一颜色）
        Box(
            Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )

        // 折叠 / 展开开关
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    onModeChange(if (expanded) NavRailMode.COLLAPSED else NavRailMode.EXPANDED)
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Rounded.ChevronLeft else Icons.Rounded.ChevronRight,
                contentDescription = if (expanded) "收起侧栏" else "展开侧栏",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        items.forEachIndexed { index, item ->
            NavItemCell(
                item = item,
                active = index == selectedIndex,
                horizontal = expanded,
                onClick = { onSelect(index) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            )
        }

        Spacer(Modifier.weight(1f))

        // 隐藏入口
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { onModeChange(NavRailMode.HIDDEN) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.VisibilityOff,
                contentDescription = "隐藏侧栏",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 侧栏隐藏后，内容区左上角的恢复按钮。 */
@Composable
fun RestoreNavRailButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 2.dp,
        modifier = modifier
            .padding(12.dp)
            .size(40.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = "显示侧栏",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
