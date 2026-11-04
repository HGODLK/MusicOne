package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

/** 临时绘制缓冲围绕原阅读锚点展开；转向及正在衔接的开合结束后才撤下额外列表行。 */
internal class PhoneLyricsDrawingWindow(
    val top: Int = 0,
    val bottom: Int = 0,
    private val turn: PhonePlayerTurn? = null,
) {
    val extended get() = top > 0 || bottom > 0
    fun logicalHeight(height: Int): Int = (height - top - bottom).coerceAtLeast(1)
    fun progress(): Float {
        val motion = turn ?: return 1f
        if (motion.preparing) return 0f
        if (!motion.orientationChanging) return 1f
        return motion.drawingProgress("lyrics")
    }
    fun bounds(value: LyricReadingBounds?): LyricReadingBounds? = value?.let {
        LyricReadingBounds(it.topClearPx + top, it.bottomInsetPx + bottom)
    }
    fun edges(height: Float, original: LyricBoundaryWindow, shift: Float,
        fraction: Float = progress()): LyricBoundaryEdges {
        val p = fraction
        val viewport = viewport(height, shift, p)
        return LyricBoundaryEdges(
            // 补行只服务转向；收起时原正文上沿随窗口移动，不能反向露出补行拖住退场。
            (viewport.top + original.topBuffer * p).coerceAtLeast(top * p),
            viewport.bottom.coerceAtMost(height))
    }

    /** 扩展边界随转向收敛，开合位移始终完整反向抵消，二者不能相乘。 */
    fun viewport(height: Float, shift: Float, fraction: Float = progress()): LyricBoundaryEdges =
        LyricBoundaryEdges(top * fraction - shift, height - bottom * fraction - shift)

    companion object { val None = PhoneLyricsDrawingWindow() }
}

internal val LocalPhoneLyricsDrawingWindow = staticCompositionLocalOf { PhoneLyricsDrawingWindow.None }

/** 转屏尾段与普通开合共用当前视口，开合途中不撤补行或重测正文。 */
internal class PhoneLyricsDrawingRetention {
    private var retained = PhoneLyricsDrawingWindow.None

    fun resolve(target: PhoneLyricsDrawingWindow, openingOrClosing: Boolean, visible: Boolean): PhoneLyricsDrawingWindow {
        retained = when {
            !visible -> PhoneLyricsDrawingWindow.None
            target.extended -> target
            openingOrClosing -> retained
            else -> PhoneLyricsDrawingWindow.None
        }
        return retained
    }
}

@Composable
internal fun rememberPhoneLyricsDrawingWindow(surface: PhoneLyricsSurface,
    value: PhoneLyricsSurfaceRequest, pageMotion: PageMotion): PhoneLyricsDrawingWindow {
    val turn = LocalPhonePlayerTurn.current
    val density = LocalDensity.current
    val retention = remember(surface, turn) { PhoneLyricsDrawingRetention() }
    val motion = LocalRetainedPhoneLyricsMotion.current
    val selected = LocalPhonePlayerPresentation.current?.portraitLyrics?.value ?: value.displayed
    val openingOrClosing by remember(motion, selected) { derivedStateOf {
        motion?.settledForRotation(selected) == false
    } }
    val bounds = surface.bounds
    val target = remember(turn, turn?.generation, turn?.orientationChanging, value.rotation, bounds.size,
        value.slot.viewport.top, value.slot.viewport.bottom, value.lyrics.readingBounds, density) {
        if (turn?.orientationChanging != true) PhoneLyricsDrawingWindow.None else {
            val viewport = value.slot.viewport
            val height = bounds.height.roundToInt() + viewport.top + viewport.bottom
            val width = (bounds.width - with(density) {
                viewport.horizontalPadding.toPx() * 2 + value.touchPadding.toPx()
            }).coerceAtLeast(1f)
            val row = lyricTypographyForWidth(value.spec.mode, width / density.density).estimatedRowHeightDp
            val anchor = lyricReadingAnchorDp(height / density.density, value.lyrics.readingBounds, density, row) * density.density
            val root = surface.coordinates?.size
            val window = root?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: pageMotion.hostBounds.size
            val (top, bottom) = phoneLyricsDrawingInsets(window, height, anchor)
            PhoneLyricsDrawingWindow(top, bottom, turn)
        }
    }
    return retention.resolve(target, openingOrClosing, LocalPlayerVisible.current && pageMotion.mounted)
}

/** 物理窗口对角线覆盖任意转角投影，避免依赖固定补几句或另一朝向的旧高度。 */
internal fun phoneLyricsDrawingInsets(window: Size, height: Int, anchor: Float): Pair<Int, Int> {
    val reach = ceil(hypot(window.width.toDouble(), window.height.toDouble())).toInt()
    return ceil((reach - anchor).coerceAtLeast(0f)).toInt() to
        ceil((reach - (height - anchor)).coerceAtLeast(0f)).toInt()
}
