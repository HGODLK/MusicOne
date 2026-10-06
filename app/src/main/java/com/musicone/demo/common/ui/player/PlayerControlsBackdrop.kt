package com.musicone.demo

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil
import kotlin.math.floor

/** 提前准备歌词模糊图层；控件上方自然过渡，控件隐藏后在底边保留模糊尾迹。 */
@Composable
internal fun Modifier.playerControlsBackdrop(
    controlsHeight: () -> Float,
    hiddenProgress: () -> Float,
    revealProgress: () -> Float = { 1f },
    bottomExtension: Int = 0,
): Modifier {
    if (ExperiencePreferences.options.disableBlur) return this
    val source = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val exitBlurred = rememberGraphicsLayer()
    val paint = androidx.compose.runtime.remember { Paint() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 模糊参数不变，只按密度变化重建效果对象。
    val blurEffect = androidx.compose.runtime.remember(density) {
        BlurEffect(with(density) { 18.dp.toPx() }, with(density) { 18.dp.toPx() }, TileMode.Clamp)
    }
    androidx.compose.runtime.SideEffect {
        blurred.renderEffect = blurEffect
        exitBlurred.renderEffect = blurEffect
    }
    return drawWithCache {
        if (Build.VERSION.SDK_INT < 31) {
            return@drawWithCache onDrawWithContent { drawContent() }
        }
        val controls = controlsHeight()
        val fadeHeight = 88.dp.toPx()
        // 采样区域按最终位置固定，歌词开关时不再逐帧重建模糊图层。
        val cropTop = floor(
            (size.height - bottomExtension - controls - fadeHeight - 54.dp.toPx())
                .coerceIn(0f, size.height),
        ).toInt()
        val cropHeight = (ceil(size.height).toInt() - cropTop).coerceAtLeast(1)
        val sampledSize = IntSize(ceil(size.width).toInt(), cropHeight)
        val fullSampledSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
        var sampleRecorded = false
        var exitSampleRecorded = false
        onDrawWithContent {
            val reveal = revealProgress().coerceIn(0f, 1f)
            if (reveal <= .001f) {
                drawContent()
                return@onDrawWithContent
            }
            // 动画进度只在绘制阶段读取，避免使 drawWithCache 每帧失效。
            val boundary = playerControlsBlurBoundary(
                size.height,
                bottomExtension,
                reveal,
                controls,
                hiddenProgress(),
            )
            val start = boundary - fadeHeight
            val end = boundary.coerceAtLeast(start + 1f)
            val useExitSample = playerControlsNeedsExitBlur(start, cropTop.toFloat())
            // 收起时歌词视口整体下移，必须让模糊采样同步覆盖固定控件边界上方的过渡带。
            val clearEnd = start.coerceIn(0f, size.height)
            val transitionEnd = maxOf(end, clearEnd + 1f).coerceIn(clearEnd, size.height)
            val compositeBounds = Rect(0f, clearEnd, size.width, size.height)
            val transitionBounds = Rect(0f, clearEnd, size.width, transitionEnd)
            val clearMask = Brush.verticalGradient(
                listOf(Color.White, Color.Transparent), startY = clearEnd, endY = transitionEnd,
            )
            val blurredMask = Brush.verticalGradient(
                listOf(Color.Transparent, Color.White), startY = clearEnd, endY = transitionEnd,
            )
            source.record { this@onDrawWithContent.drawContent() }
            // 显示列表引用实时源图层，几何不变时无需重新录制模糊采样指令。
            // 首次在源内容录制后建立引用；源歌词仍逐次更新，不冻结滚动或切歌。
            if (!sampleRecorded) {
                blurred.record(size = sampledSize) {
                    translate(top = -cropTop.toFloat()) { drawLayer(source) }
                }
                sampleRecorded = true
            }
            if (useExitSample && !exitSampleRecorded) {
                // 完整采样只在歌词收起/反向回弹时建立；常态播放继续使用底部局部采样。
                exitBlurred.record(size = fullSampledSize) { drawLayer(source) }
                exitSampleRecorded = true
            }
            // 两个互补遮罩交接，避免清晰原文从模糊图层的透明像素中透出来。
            drawIntoCanvas { canvas ->
                clipRect(bottom = clearEnd) { drawLayer(source) }
                canvas.saveLayer(transitionBounds, paint)
                clipRect(top = clearEnd, bottom = transitionEnd) { drawLayer(source) }
                drawRect(clearMask, blendMode = BlendMode.DstIn)
                canvas.restore()
                canvas.saveLayer(compositeBounds, paint)
                if (useExitSample) {
                    drawLayer(exitBlurred)
                } else {
                    translate(top = cropTop.toFloat()) { drawLayer(blurred) }
                }
                drawRect(blurredMask, blendMode = BlendMode.DstIn)
                canvas.restore()
            }
        }
    }
}

internal fun playerControlsBlurBoundary(
    drawingHeight: Float, bottomExtension: Int, reveal: Float, controlsHeight: Float, hidden: Float,
): Float = (drawingHeight - bottomExtension) * reveal - controlsHeight +
    playerControlsTranslation(hidden, controlsHeight)

internal fun playerControlsNeedsExitBlur(transitionStart: Float, croppedSampleTop: Float): Boolean =
    transitionStart < croppedSampleTop
