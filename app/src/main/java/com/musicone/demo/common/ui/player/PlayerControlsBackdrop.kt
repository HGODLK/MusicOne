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
    topExtension: Int = 0,
    topBuffer: Int = 0,
    windowMotionOffset: (() -> Float)? = null,
    focusProtection: (() -> LyricBlurProtection)? = null,
    topGlass: PlayerLyricsTopGlass? = null,
): Modifier {
    if (ExperiencePreferences.options.disableBlur && topExtension <= 0 && topBuffer <= 0) return this
    val source = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val exitBlurred = rememberGraphicsLayer()
    val topBlurred = rememberGraphicsLayer()
    val paint = androidx.compose.runtime.remember { Paint() }
    val additivePaint = androidx.compose.runtime.remember { Paint().apply { blendMode = BlendMode.Plus } }
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 底部模糊参数不变；顶部使用更轻的半径，效果对象按密度复用。
    val blurEffect = androidx.compose.runtime.remember(density) {
        BlurEffect(with(density) { 18.dp.toPx() }, with(density) { 18.dp.toPx() }, TileMode.Clamp)
    }
    val topBlurEffect = androidx.compose.runtime.remember(density) {
        BlurEffect(with(density) { 6.dp.toPx() }, with(density) { 6.dp.toPx() }, TileMode.Clamp)
    }
    androidx.compose.runtime.SideEffect {
        blurred.renderEffect = blurEffect
        exitBlurred.renderEffect = blurEffect
        topBlurred.renderEffect = topBlurEffect
    }
    return drawWithCache {
        val canBlur = Build.VERSION.SDK_INT >= 31 && !ExperiencePreferences.options.disableBlur
        val headerBottom = (topExtension + topBuffer).toFloat().coerceIn(0f, size.height)
        val topFadeHeight = playerLyricsTopFadeHeight(topExtension.toFloat())
        val topBlurEnd = topGlass?.clearStart?.coerceIn(0f, size.height)
            ?: playerLyricsTopBlurEnd(headerBottom, topFadeHeight, size.height)
        if (!canBlur && topBlurEnd == 0f) {
            return@drawWithCache onDrawWithContent { drawContent() }
        }
        val controls = controlsHeight()
        val originalFadeHeight = PLAYER_CONTROLS_BLUR_FADE_HEIGHT.toPx()
        val fadeHeight = if (focusProtection == null) originalFadeHeight else PLAYER_CONTROLS_WIDENED_BLUR_HEIGHT.toPx()
        // 采样区域按最终位置固定，歌词开关时不再逐帧重建模糊图层。
        val cropTop = floor(
            (size.height - bottomExtension - controls - fadeHeight - 54.dp.toPx())
                .coerceIn(0f, size.height),
        ).toInt()
        val cropHeight = (ceil(size.height).toInt() - cropTop).coerceAtLeast(1)
        val sampledSize = IntSize(ceil(size.width).toInt(), cropHeight)
        val fullSampledSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())
        val topSampledSize = IntSize(ceil(size.width).toInt().coerceAtLeast(1),
            minOf(size.height, topBlurEnd + 18.dp.toPx()).toInt().coerceAtLeast(1))
        val p = topGlass?.maskFraction(topBuffer.toFloat())
            ?: (headerBottom / topBlurEnd.coerceAtLeast(1f)).coerceIn(0f, 1f)
        // 模糊层承担页眉背后主要显示，并保留顶端渐隐；在页眉下方平滑过渡到透明。
        val topBlurMask = Brush.verticalGradient(
            0f to Color.Transparent,
            (p * .25f) to Color.White.copy(alpha = .06f),
            (p * .55f) to Color.White.copy(alpha = .4f),
            (p * .8f) to Color.White.copy(alpha = .78f),
            p to Color.White,
            1f to Color.Transparent,
            startY = 0f, endY = (topBlurEnd - topBuffer).coerceAtLeast(1f))
        // 有模糊时清晰层在页眉退让、仅在页眉下方互补接入；无模糊时清晰层正常顶端渐隐，不留空白。
        val topClearMask = if (canBlur) {
            Brush.verticalGradient(
                0f to Color.Transparent,
                p to Color.Transparent,
                1f to Color.White,
                startY = 0f, endY = (topBlurEnd - topBuffer).coerceAtLeast(1f))
        } else {
            Brush.verticalGradient(
                0f to Color.Transparent,
                (p * .25f) to Color.White.copy(alpha = .06f),
                (p * .55f) to Color.White.copy(alpha = .4f),
                (p * .8f) to Color.White.copy(alpha = .78f),
                p to Color.White,
                1f to Color.White,
                startY = 0f, endY = (topBlurEnd - topBuffer).coerceAtLeast(1f))
        }
        var sampleRecorded = false
        var exitSampleRecorded = false
        var topSampleRecorded = false
        onDrawWithContent {
            val reveal = revealProgress().coerceIn(0f, 1f)
            // 歌词列表会整体入场；遮罩反向补偿列表位移，始终留在屏幕顶部。
            val windowShift = windowMotionOffset?.invoke()?.coerceAtLeast(0f)
            val topShift = windowShift ?: 0f
            val localStageTop = (topBuffer - topShift).toFloat()
            val localTopEnd = (topBlurEnd - topShift).toFloat()
            val visibleTopEnd = maxOf(0f, localTopEnd)
            if (reveal <= .001f) {
                // 关闭态仍要录制屏外歌词供预备定位，但不能把它画到播放控件背后。
                source.record { this@onDrawWithContent.drawContent() }
                return@onDrawWithContent
            }
            source.record { this@onDrawWithContent.drawContent() }
            if (canBlur && topBlurEnd > 0f && !topSampleRecorded) {
                topBlurred.record(size = topSampledSize) { drawLayer(source) }
                topSampleRecorded = true
            }
            if (!canBlur) {
                clipRect(top = visibleTopEnd) { drawLayer(source) }
            } else {
            // 动画进度只在绘制阶段读取，避免使 drawWithCache 每帧失效。
            val boundary = playerControlsBlurBoundary(
                size.height,
                bottomExtension,
                reveal,
                controls,
                hiddenProgress(),
                windowShift,
            )
            val start = playerControlsBlurStart(boundary, originalFadeHeight, fadeHeight,
                focusProtection?.invoke(), 8.dp.toPx())
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
            clipRect(top = visibleTopEnd) { drawIntoCanvas { canvas ->
                clipRect(bottom = clearEnd) { drawLayer(source) }
                canvas.saveLayer(transitionBounds, paint)
                clipRect(top = clearEnd, bottom = transitionEnd) { drawLayer(source) }
                drawRect(clearMask, blendMode = BlendMode.DstIn)
                canvas.restore()
                canvas.saveLayer(compositeBounds, additivePaint)
                if (useExitSample) {
                    drawLayer(exitBlurred)
                } else {
                    translate(top = cropTop.toFloat()) { drawLayer(blurred) }
                }
                drawRect(blurredMask, blendMode = BlendMode.DstIn)
                canvas.restore()
            } }
            }
            if (localTopEnd > localStageTop && reveal > .001f) {
                val topRegion = Rect(0f, localStageTop, size.width, localTopEnd)
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(topRegion, paint)
                    clipRect(top = localStageTop, bottom = localTopEnd) { drawLayer(source) }
                    translate(top = localStageTop) { drawRect(topClearMask, blendMode = BlendMode.DstIn) }
                    canvas.restore()
                    if (canBlur) {
                        canvas.saveLayer(topRegion, additivePaint)
                        clipRect(top = localStageTop, bottom = localTopEnd) { drawLayer(topBlurred) }
                        translate(top = localStageTop) { drawRect(topBlurMask, blendMode = BlendMode.DstIn) }
                        canvas.restore()
                    }
                }
            }
        }
    }
}

internal fun playerControlsBlurBoundary(
    drawingHeight: Float, bottomExtension: Int, reveal: Float, controlsHeight: Float, hidden: Float,
    windowMotionOffset: Float? = null,
): Float = (if (windowMotionOffset == null) (drawingHeight - bottomExtension) * reveal
    else drawingHeight - bottomExtension - windowMotionOffset) - controlsHeight +
    playerControlsTranslation(hidden, controlsHeight)

internal fun playerControlsNeedsExitBlur(transitionStart: Float, croppedSampleTop: Float): Boolean =
    transitionStart < croppedSampleTop

internal fun playerLyricsTopFadeHeight(headerBottom: Float): Float =
    if (headerBottom <= 0f) 0f else (headerBottom * .45f).coerceAtLeast(1f)

internal fun playerLyricsTopBlurEnd(headerBottom: Float, fadeHeight: Float, drawingHeight: Float): Float =
    if (headerBottom <= 0f) 0f else minOf(drawingHeight, headerBottom + fadeHeight)

internal val PLAYER_CONTROLS_BLUR_FADE_HEIGHT = 88.dp
internal val PLAYER_CONTROLS_WIDENED_BLUR_HEIGHT = 128.dp
