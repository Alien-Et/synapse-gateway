package io.synapse.app.ui.providers.mcloud

import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 移动云盘的滑动拼图。
 *
 * ## 比例必须锁死，否则永远对不准
 * 上游原图是 **680×400**（`picWidth`/`picHeight`），滑块小图是 **96 宽**。
 * 早期版本把背景用 `ContentScale.Crop` 塞进固定高度，横向被压扁，
 * 滑块和缺口在视觉上对不上——用户拖到「看起来对齐」的位置，实际差一大截。
 * 现在背景按原始宽高比显示，滑块也按同一缩放系数缩放，两者刻度一致。
 *
 * ## 交互
 * 手势挂在**整条轨道**上，不是挂在 56dp 的滑块上：手指粗一点就点不中，
 * 实测挂在滑块上时完全拖不动。
 */
@Composable
fun SlidePuzzle(
    sliderImage: String,
    backgroundImage: String,
    sliderWidth: Int,
    onCompleted: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = remember(backgroundImage) { decodeBitmap(backgroundImage) }
    val slider = remember(sliderImage) { decodeBitmap(sliderImage) }

    // 上游给的原始尺寸；拿不到时按 680x400 兜底
    val bgW = background?.width?.toFloat()?.takeIf { it > 0 } ?: 680f
    val bgH = background?.height?.toFloat()?.takeIf { it > 0 } ?: 400f
    val sliderOrigW = slider?.width?.toFloat()?.takeIf { it > 0 } ?: sliderWidth.toFloat()

    var offsetX by remember(backgroundImage) { mutableFloatStateOf(0f) }
    var trackWidth by remember { mutableFloatStateOf(0f) }
    var completed by remember(backgroundImage) { mutableStateOf(false) }
    /** 背景实际绘制宽度，用来把「原图坐标」换算成屏幕坐标。 */
    var drawnWidth by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current

    // 图没解出来时不要卡住用户：直接放行
    LaunchedEffect(background, slider) {
        if (background == null || slider == null) {
            completed = true
            onCompleted()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (completed) "拼图已完成" else "把滑块拖到图中的缺口位置",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 背景：严格按原图宽高比，绝不拉伸
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(bgW / bgH)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onSizeChanged { drawnWidth = it.width.toFloat() },
        ) {
            if (background != null) {
                Image(
                    bitmap = background,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds, // 容器已等比，这里不会变形
                    modifier = Modifier.fillMaxWidth().height(this.maxHeight),
                )
            }
            if (slider != null) {
                // 滑块尺寸 = 原图滑块像素 × 屏幕相对原图的缩放系数。
                //
                // 这里必须和**缺口**用同一个系数：缺口是背景图的一部分，背景被等比缩放到
                // drawnWidth，缺口也就同比缩小。之前滑块用固定 dp（不随背景缩放），
                // 于是小图上出现「滑块比缺口小一圈」——用户拖到视觉对齐也判定不出结果。
                val scale = if (bgW > 0) drawnWidth / bgW else 1f
                val sliderPx = (sliderOrigW * scale).coerceAtLeast(24f)

                Image(
                    bitmap = slider,
                    contentDescription = "滑块",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .offset { IntOffset(offsetX.roundToInt(), 0) }
                        .size(with(density) { sliderPx.toDp() }),
                )
            }
        }

        // 轨道：整条都能拖
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .onSizeChanged { trackWidth = it.width.toFloat() }
                .pointerInput(backgroundImage, trackWidth) {
                    detectDragGestures(
                        onDragEnd = {
                            // 判定标准：拖到轨道 45% 以上就算通过。
                            // 上游并不校验轨迹（真正的风控在发码频率上），
                            // 假精确只会让用户反复失败。
                            if (trackWidth > 0f && offsetX >= trackWidth * 0.45f) {
                                completed = true
                                onCompleted()
                            }
                        },
                    ) { change, drag ->
                        change.consume()
                        if (!completed) {
                            val max = (trackWidth - with(density) { 56.dp.toPx() }).coerceAtLeast(0f)
                            offsetX = (offsetX + drag.x).coerceIn(0f, max)
                        }
                    }
                },
        ) {
            Text(
                text = if (completed) "已完成" else "按住拖动 →",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(start = 24.dp),
            )
            if (slider != null) {
                // 轨道滑块放大显示（轨道只有 56dp 高，按原比例会小到看不清），
                // 它只是「手柄」，真正代表位置的是 offsetX，所以两处尺寸可以不同。
                Image(
                    bitmap = slider,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .offset { IntOffset(offsetX.roundToInt(), 0) }
                        .size(56.dp)
                        .padding(6.dp),
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    offsetX = 0f
                    completed = false
                    onRefresh()
                },
                modifier = Modifier.width(120.dp),
            ) {
                Text("换一张")
            }
            if (completed) {
                Text(
                    text = "✓ 已通过",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 解 Base64 → [ImageBitmap]。上游有时带 `data:image/png;base64,` 前缀，两种都要能解。
 *
 * 解不出来返回 null，由调用方放行（上游不二次校验轨迹，不该因为它卡住用户）。
 */
private fun decodeBitmap(raw: String): ImageBitmap? = runCatching {
    val payload = raw.substringAfter("base64,", raw).trim()
    if (payload.isEmpty()) return@runCatching null
    val bytes = Base64.decode(payload, Base64.DEFAULT)
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        ?: return@runCatching null
    bitmap.asImageBitmap()
}.getOrNull()
