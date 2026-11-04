package com.musicone

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

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
    prepareClosedSource: () -> Boolean = { true },
    enabled: Boolean = true,
    drawingWindow: PhoneLyricsDrawingWindow = PhoneLyricsDrawingWindow.None,
    backdropTurn: PhoneLyricsBackdropTurn? = null,
    landscape: Boolean = false,
    orientationChanging: () -> Boolean = { false },
    readingPivot: () -> Float = { 0f },
): Modifier {
    val options = ExperiencePreferences.options
    val frameRate = LocalPlayerFrameRatePolicy.current
    val contentEpoch = LocalPlayerLyricsContent.current?.drawEpoch
    val turn = LocalPhonePlayerTurn.current
    val turnFocus = LocalPhoneLyricsTurnFocus.current
    val canBlur = enabled && lyricBackdropBlurEnabled(Build.VERSION.SDK_INT, options.disableBlur, options.disablePlayerControlsBlur)
    val edgeEase = androidx.compose.runtime.remember { LyricBlurEdgeEase() }
    androidx.compose.runtime.LaunchedEffect(edgeEase) { edgeEase.run() }
    val source = rememberGraphicsLayer()
    val paint = androidx.compose.runtime.remember { Paint() }
    val clearPaint = androidx.compose.runtime.remember { Paint() }
    val additivePaint = androidx.compose.runtime.remember { Paint().apply { blendMode = BlendMode.Plus } }
    androidx.compose.runtime.SideEffect {
        // 正文只保留实时绘制引用；模糊分支各自隔离采样，避免整块扩展视口栅格化及隐式裁剪。
        source.compositingStrategy = CompositingStrategy.Auto
    }
    return lyricDrawWithCache {
        if (!enabled) return@lyricDrawWithCache onDrawWithContent { drawContent() }
        val closedRecording = LyricClosedRecording()
        val viewportHeight = size.height - drawingWindow.top - drawingWindow.bottom
        val headerBottom = (topExtension + topBuffer).toFloat().coerceIn(0f, viewportHeight)
        val topFadeHeight = playerLyricsTopFadeHeight(topExtension.toFloat())
        val topBlurEnd = topGlass?.clearStart?.coerceIn(0f, viewportHeight)
            ?: playerLyricsTopBlurEnd(headerBottom, topFadeHeight, viewportHeight)
        val controls = controlsHeight()
        val originalFadeHeight = PLAYER_CONTROLS_BLUR_FADE_HEIGHT.toPx()
        val fadeHeight = if (focusProtection == null) originalFadeHeight else PLAYER_CONTROLS_WIDENED_BLUR_HEIGHT.toPx()
        val p = topGlass?.maskFraction(topBuffer.toFloat())
            ?: (headerBottom / topBlurEnd.coerceAtLeast(1f)).coerceIn(0f, 1f)
        // 关闭任一模糊开关时不创建模糊场景。底部平时只采样控件一带，开合时带变长才用整窗容量。
        val maximumFade = maxOf(originalFadeHeight, fadeHeight)
        val bottomBlur by lazy(LazyThreadSafetyMode.NONE) {
            if (canBlur) LyricBackdropBlurScene(this, source, 18.dp.toPx(),
                listOf(bottomExtension + controls + maximumFade + 54.dp.toPx(), size.height)) else null
        }
        val topBlur by lazy(LazyThreadSafetyMode.NONE) {
            if (canBlur) LyricBackdropBlurScene(this, source, 6.dp.toPx(),
                listOf(if (backdropTurn?.retained == true) maxOf(backdropTurn.sourceTopHeight, topBlurEnd - topBuffer)
                    else topBlurEnd - topBuffer)) else null
        }
        // 只保留合成指令与实时子图层引用，不缓存歌词画面或新增整窗离屏纹理。
        val composite = obtainGraphicsLayer()
        var recordedStart = Float.NaN
        var recordedEnd = Float.NaN
        var recordedTop = Float.NaN
        var recordedTopEnd = Float.NaN
        var recordedBottomLimit = Float.NaN
        var recordedStrength = Float.NaN
        var recordedPeak = Float.NaN
        var clearMask: Brush? = null
        val blurredMask = Brush.verticalGradient(listOf(Color.Transparent, Color.White), startY = 0f, endY = 1f)
        var topBlurMask: Brush? = null
        var topClearMask: Brush? = null
        var sourceRecorded = false
        var recordedEpoch = Long.MIN_VALUE
        fun recordSource(draw: androidx.compose.ui.graphics.drawscope.ContentDrawScope) {
            val epoch = contentEpoch?.revision ?: 0L
            if (!sourceRecorded || recordedEpoch != epoch) {
                source.record { draw.drawContent() }
                sourceRecorded = true
                recordedEpoch = epoch
            }
        }
        onDrawWithContent {
            val reveal = revealProgress().coerceIn(0f, 1f)
            // 转向时完全关闭的歌词没有像素输出，端点稳定后再准备原有模糊场景。
            if (reveal <= 0f && !prepareClosedSource()) return@onDrawWithContent
            // 歌词列表会整体入场；遮罩反向补偿列表位移，始终留在屏幕顶部。
            val windowShift = windowMotionOffset?.invoke()?.coerceAtLeast(0f)
            val topShift = windowShift ?: 0f
            val drawingProgress = drawingWindow.progress()
            val viewport = drawingWindow.viewport(size.height, topShift, drawingProgress)
            if (reveal <= 0f) {
                // 关闭态仍要录制屏外歌词供预备定位，但不能把它画到播放控件背后。
                if (closedRecording.shouldRecord(frameRate?.isIdle == true,
                        frameRate?.lyricFrameNanos ?: Long.MIN_VALUE)) {
                    recordSource(this)
                }
                return@onDrawWithContent
            }
            recordSource(this)
            // 动画进度只在绘制阶段读取，避免使 lyricDrawWithCache 每帧失效。
            val boundary = playerControlsBlurBoundary(
                viewport.bottom + topShift,
                bottomExtension,
                reveal,
                controls,
                hiddenProgress(),
                windowShift,
            )
            // 下沿和原过渡带跟随控件；只有按当前句计算的上扩范围需要防跳变。
            val originalStart = boundary - originalFadeHeight
            val start = originalStart - edgeEase.span(originalStart - playerControlsBlurStart(boundary,
                originalFadeHeight, fadeHeight, focusProtection?.invoke(), 8.dp.toPx()), 8.dp.toPx())
            val end = boundary.coerceAtLeast(start + 1f)
            val target = PhoneLyricsBackdropFrame(topBuffer + viewport.top,
                topBlurEnd + viewport.top, p, start, end, readingPivot() - topShift)
            val changing = orientationChanging()
            val focus = if (changing && landscape) turnFocus?.read(turn?.generation ?: 0, size.height) else null
            val frame = backdropTurn?.frame(target, landscape, changing, drawingProgress, focus, 8.dp.toPx()) ?: target
            val strength = frame.strength
            val bottomLimit = (size.height - drawingWindow.bottom * drawingProgress - topShift).coerceIn(0f, size.height)
            if (strength <= 0f) {
                drawLayer(source)
                return@onDrawWithContent
            }
            val localStageTop = lyricBackdropPixelEdge(frame.top)
            val localTopEnd = lyricBackdropPixelEdge(frame.topEnd)
            val visibleTopEnd = maxOf(0f, localTopEnd)
            val preparedBottomBlur = bottomBlur
            val preparedTopBlur = topBlur
            // seek 时边界通常固定，只更新源歌词；开合、控件位移或焦点保护变化才重录遮罩。
            if (frame.bottomStart != recordedStart || frame.bottomEnd != recordedEnd || strength != recordedStrength ||
                frame.peak != recordedPeak || localStageTop != recordedTop || localTopEnd != recordedTopEnd ||
                bottomLimit != recordedBottomLimit) {
                // 清晰与模糊权重互补，退场不会让整片歌词跟着变暗或叠出双影。
                additivePaint.alpha = strength
                clearPaint.alpha = 1f - strength
                // 遮罩按单位高度缓存，普通平移或高度变化只改矩阵，不逐帧创建渐变着色器。
                if (recordedPeak != frame.peak || recordedStrength != strength) {
                    topBlurMask = lyricBackdropTopMask(true, canBlur, frame.peak, 1f, strength)
                    topClearMask = lyricBackdropTopMask(false, canBlur, frame.peak, 1f, strength)
                }
                composite.record {
                    // 原顶端裁掉的区域也逐渐恢复清晰正文，不能留到撤下模糊的最后一帧才出现。
                    if (strength < 1f && localStageTop > 0f) drawIntoCanvas { canvas ->
                        val bottom = localStageTop.coerceAtMost(size.height)
                        canvas.saveLayer(Rect(0f, 0f, size.width, bottom), clearPaint)
                        clipRect(bottom = bottom) { drawLayer(source) }
                        canvas.restore()
                    }
                    if (!canBlur) {
                        drawLyricBackdropOpacityFallback(source, visibleTopEnd, frame.bottomStart, frame.bottomEnd, paint, strength)
                    } else {
                        // 收起时歌词视口整体下移，必须让模糊采样同步覆盖固定控件边界上方的过渡带。
                        val clearEnd = frame.bottomStart.coerceIn(0f, size.height)
                        val transitionEnd = maxOf(frame.bottomEnd, clearEnd + 1f).coerceIn(clearEnd, size.height)
                        val compositeBounds = Rect(0f, clearEnd, size.width, size.height)
                        val transitionBounds = Rect(0f, clearEnd, size.width, transitionEnd)
                        val span = transitionEnd - clearEnd
                        if (recordedStrength != strength) {
                            clearMask = Brush.verticalGradient(listOf(Color.White, Color.White.copy(alpha = 1f - strength)),
                                startY = 0f, endY = 1f)
                        }
                        // 两个互补遮罩交接，避免清晰原文从模糊图层的透明像素中透出来。
                        clipRect(top = visibleTopEnd) { drawIntoCanvas { canvas ->
                            clipRect(bottom = clearEnd) { drawLayer(source) }
                            canvas.saveLayer(transitionBounds, paint)
                            clipRect(top = clearEnd, bottom = transitionEnd) { drawLayer(source) }
                            drawLyricBackdropMask(checkNotNull(clearMask), clearEnd, span)
                            canvas.restore()
                            if (strength < 1f && transitionEnd < size.height) {
                                canvas.saveLayer(Rect(0f, transitionEnd, size.width, size.height), clearPaint)
                                clipRect(top = transitionEnd) { drawLayer(source) }
                                canvas.restore()
                            }
                            canvas.saveLayer(compositeBounds, additivePaint)
                            preparedBottomBlur?.draw(this@record, lyricBottomBlurBand(clearEnd, bottomLimit))
                            drawLyricBackdropMask(blurredMask, clearEnd, span)
                            canvas.restore()
                        } }
                    }
                    if (localTopEnd > localStageTop) {
                        val topRegion = Rect(0f, localStageTop, size.width, localTopEnd)
                        drawIntoCanvas { canvas ->
                            canvas.saveLayer(topRegion, paint)
                            clipRect(top = localStageTop, bottom = localTopEnd) { drawLayer(source) }
                            drawLyricBackdropMask(checkNotNull(topClearMask), localStageTop,
                                (localTopEnd - localStageTop).coerceAtLeast(1f))
                            canvas.restore()
                            if (canBlur) {
                                canvas.saveLayer(topRegion, additivePaint)
                                clipRect(top = localStageTop, bottom = localTopEnd) {
                                    preparedTopBlur?.draw(this, lyricTopBlurBand(localStageTop, localTopEnd))
                                }
                                drawLyricBackdropMask(checkNotNull(topBlurMask), localStageTop,
                                    (localTopEnd - localStageTop).coerceAtLeast(1f))
                                canvas.restore()
                            }
                        }
                    }
                }
                recordedStart = frame.bottomStart
                recordedEnd = frame.bottomEnd
                recordedStrength = strength
                recordedPeak = frame.peak
                recordedTop = localStageTop
                recordedTopEnd = localTopEnd
                recordedBottomLimit = bottomLimit
            }
            drawLayer(composite)
        }
    }.graphicsLayer()
}

internal fun playerControlsBlurBoundary(
    drawingHeight: Float, bottomExtension: Int, reveal: Float, controlsHeight: Float, hidden: Float,
    windowMotionOffset: Float? = null,
): Float = (if (windowMotionOffset == null) (drawingHeight - bottomExtension) * reveal
    else drawingHeight - bottomExtension - windowMotionOffset) - controlsHeight +
    playerControlsTranslation(hidden, controlsHeight)

internal fun playerLyricsTopFadeHeight(headerBottom: Float): Float =
    if (headerBottom <= 0f) 0f else (headerBottom * .45f).coerceAtLeast(1f)

internal fun playerLyricsTopBlurEnd(headerBottom: Float, fadeHeight: Float, drawingHeight: Float): Float =
    if (headerBottom <= 0f) 0f else minOf(drawingHeight, headerBottom + fadeHeight)

/** 遮罩边缘压在半个像素上时盖不满这一行，相加的模糊层会漏出来，在文字上形成一条亮线。 */
internal fun lyricBackdropPixelEdge(value: Float): Float = kotlin.math.round(value)

internal val PLAYER_CONTROLS_BLUR_FADE_HEIGHT = 88.dp
internal val PLAYER_CONTROLS_WIDENED_BLUR_HEIGHT = 128.dp
