package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import kotlinx.coroutines.CancellationException

/** 菜单与结果共用连续运动值，反向时保留当前进度和速度。 */
internal class QqSearchMotion {
    val menu = Animatable(0f)
    val full = Animatable(0f)
    var fullMoving by mutableStateOf(false)
    var resultsReady by mutableStateOf(false)
    var backdropSnapshot by mutableStateOf<ImageBitmap?>(null)
    var snapshotUnavailable by mutableStateOf(false)
    var frozenMenuSize: Pair<Dp, Dp>? = null
    val mounted get() = menu.value > 0f || full.value > 0f
}

@Composable
internal fun rememberQqSearchMotion(state: QqSearchState, backdrop: GraphicsLayer): QqSearchMotion {
    val motion = remember { QqSearchMotion() }
    LaunchedEffect(state.opened) { motion.menu.animateTo(if (state.opened) 1f else 0f,
        if (state.opened) PlayerEnterAnimation else PlayerReturnAnimation) }
    LaunchedEffect(state.full) {
        if (!state.full && motion.full.value == 0f) {
            motion.fullMoving = false
            motion.resultsReady = false
            motion.backdropSnapshot = null
            motion.snapshotUnavailable = false
            return@LaunchedEffect
        }
        motion.fullMoving = true
        // 首次展开前固化像素，而不是仅保存仍会随页面变化的图层引用。
        if (state.full && motion.backdropSnapshot == null) {
            try { motion.backdropSnapshot = backdrop.toImageBitmap() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { motion.snapshotUnavailable = true }
        }
        withFrameNanos { }
        motion.full.animateTo(if (state.full) 1f else 0f,
            if (state.full) PlayerEnterAnimation else PlayerReturnAnimation)
        withFrameNanos { }
        motion.resultsReady = state.full
        motion.fullMoving = false
        if (!state.full) {
            motion.backdropSnapshot = null
            motion.snapshotUnavailable = false
            motion.frozenMenuSize = null
        }
    }
    return motion
}

internal fun searchMenuHeight(window: Float, keyboard: Float, top: Float, desired: Float): Float =
    minOf(window / 2f, (window - keyboard - top - 12f).coerceAtLeast(0f), desired).coerceAtLeast(0f)

internal data class QqSearchGeometry(val width: Dp, val height: Dp, val right: Dp, val top: Dp, val corner: Dp,
    val contentWidth: Dp, val contentHeight: Dp)

internal fun searchSuggestionHeight(count: Int, error: Boolean, rowHeight: Float): Float =
    56f + 12f + maxOf(1, count) * rowHeight + if (error) 48f else 0f

@Composable
internal fun searchGeometry(width: Dp, height: Dp, state: QqSearchState, motion: QqSearchMotion): () -> QqSearchGeometry {
    val density = LocalDensity.current
    val ime = with(density) { WindowInsets.ime.getBottom(this).toDp() }
    val gutter = if (width >= 600.dp) maxOf(32.dp, (width - 1120.dp) / 2) else 20.dp
    val menuWidth = minOf(480.dp, width - gutter * 2)
    val target = searchMenuHeight(height.value, ime.value, 10f,
        if (state.query.isBlank()) 204f else searchSuggestionHeight(state.suggestions.size,
            state.suggestionError != null, maxOf(48f, 20f * density.fontScale + 28f))).dp
    val menuHeight by animateDpAsState(target, musicMotion(360), label = "历史联想菜单高度")
    return {
    val open = motion.menu.value
    val full = motion.full.value
    val menuSize = if (state.full || motion.fullMoving) {
        motion.frozenMenuSize ?: (menuWidth to menuHeight).also { motion.frozenMenuSize = it }
    } else (menuWidth to menuHeight).also { motion.frozenMenuSize = it }
    val expandedWidth = 48.dp + (menuSize.first - 48.dp) * open
    val expandedHeight = 48.dp + (menuSize.second - 48.dp) * open
    QqSearchGeometry(
        expandedWidth + (width - expandedWidth) * full,
        expandedHeight + (height - expandedHeight) * full,
        gutter * (1f - full), 10.dp * (1f - full), 26.dp * (1f - full),
        menuSize.first, menuSize.second,
    )
    }
}

internal class QqSearchRevealShape(private val geometryProvider: () -> QqSearchGeometry) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val geometry = geometryProvider()
        val right = size.width - geometry.right.toPx()
        Outline.Rounded(RoundRect(right - geometry.width.toPx(), geometry.top.toPx(),
            right, geometry.top.toPx() + geometry.height.toPx(), CornerRadius(geometry.corner.toPx())))
    }
}

internal val LocalQqSearchMotion = staticCompositionLocalOf<QqSearchMotion?> { null }

/** 尺寸只在端点交接时切换，不能依赖逐帧的展开进度。 */
internal fun searchContentSize(menu: Pair<Dp, Dp>, viewport: Pair<Dp, Dp>, resultsReady: Boolean): Pair<Dp, Dp> =
    if (resultsReady) viewport else menu

/** 内容图层按外层视口定位，不能使用固定测量后的图层自身宽度。 */
internal fun searchContentOffset(viewportWidth: Dp, frame: QqSearchGeometry, open: Float): Dp =
    viewportWidth - frame.right - frame.width + 32.dp * (1f - open)
