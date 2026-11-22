package io.synapse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze

/**
 * 毛玻璃设计系统。
 *
 * Haze 1.4.0 提供的是真正的 backdrop-filter：页面的背景层用 [Modifier.haze] 标记为采样源，
 * 卡片用 `Modifier.hazeChild()` 采样并模糊它下方的画面。这与 CSS 的 backdrop-filter 等价。
 *
 * 为了让"毛"出来的效果可见，页面背景不能是纯色——[GlassBackdrop] 在纯色底上叠两团很淡的
 * 渐变光斑（金 + 蓝），卡片经过时会有轻微的色相与明度变化，读起来就是玻璃而不是半透明白块。
 */

/** 当前页面的 Haze 采样源；由 [GlassBackdrop] 创建并向下提供。 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/** 统一的玻璃样式：模糊半径 24dp、轻色调、低噪点。 */
fun glassStyle(tint: Color, blurRadius: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(24f)) =
    HazeStyle(
        backgroundColor = Color.Transparent,
        tint = HazeTint(tint.copy(alpha = 0.06f)),
        blurRadius = blurRadius,
        noiseFactor = 0.02f,
    )

/**
 * 页面背景：纯色底 + 两团极淡的渐变光斑，并注册为 Haze 的采样源。
 *
 * 用 [GlassBackdrop] 替代 [PageScaffold] 里原来的纯色 background，
 * 之后该页面内所有 `GlassCard` 都能模糊到底纹。
 */
@Composable
fun GlassBackdrop(
    baseColor: Color,
    accentColor: Color,
    content: @Composable () -> Unit,
) {
    val hazeState = remember { HazeState() }
    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(baseColor)
                .drawBehind {
                    // 左上金晕：用径向渐变模拟光斑，中心在屏幕外，只有右下部分落在屏内
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(accentColor.copy(alpha = 0.085f), Color.Transparent),
                            center = Offset(size.width * 0.12f, -size.height * 0.08f),
                            radius = size.width * 0.9f,
                        ),
                    )
                    // 右下冷色晕：与暖金形成极轻微的冷暖对比，避免整屏发黄
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF3B82F6).copy(alpha = 0.045f), Color.Transparent),
                            center = Offset(size.width * 0.95f, size.height * 1.05f),
                            radius = size.width * 0.85f,
                        ),
                    )
                }
                .haze(hazeState),
        ) {
            content()
        }
    }
}
