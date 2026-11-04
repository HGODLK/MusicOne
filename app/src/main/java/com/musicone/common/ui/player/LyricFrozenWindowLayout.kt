package com.musicone

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.graphics.Color

/** 旧窗口保留最后一次真实排版；父页面转向不再使退出文字重新换行或创建字形场景。 */
internal data class LyricWindowGeometry(
    val typography: LyricTypography,
    val readingBounds: LyricReadingBounds?,
    val boundaryWindow: LyricBoundaryWindow?,
    val touchPadding: Dp,
    val topExtension: Int,
    val topBuffer: Int,
    val contentColor: Color = Color.Unspecified,
    val exitProgress: (() -> Float)? = null,
    val entranceProgress: (() -> Float)? = null,
    val limitTransitionHistory: Boolean = false,
)

internal class LyricFrozenWindowLayout {
    private var geometry: LyricWindowGeometry? = null
    private var constraints: Constraints? = null
    private var frozenGeometry: LyricWindowGeometry? = null

    fun geometry(current: LyricWindowGeometry, frozen: Boolean): LyricWindowGeometry {
        if (!frozen) { frozenGeometry = null; return current }
        return frozenGeometry ?: (geometry ?: current).let { previous ->
            val exit = previous.exitProgress?.invoke()
            val entrance = previous.entranceProgress?.invoke()
            previous.copy(boundaryWindow = previous.boundaryWindow?.let {
                val offset = it.offset()
                val height = constraints?.maxHeight?.takeIf { value -> value != Constraints.Infinity }?.toFloat()
                val edges = height?.let { value -> it.edges?.invoke(value, offset) }
                // 退出窗口冻结本轮遮罩位置，后续切歌位移仍以同一边界继续补偿。
                LyricBoundaryWindow(it.topBuffer, offset = { offset }, partialTop = it.partialTop,
                    edges = if (edges == null || height == null) null else { nextHeight, shift ->
                        LyricBoundaryEdges((edges.top - (shift - offset)).coerceAtLeast(0f),
                            (nextHeight - (height - edges.bottom) - (shift - offset)).coerceAtMost(nextHeight))
                    })
            }, exitProgress = exit?.let { value -> { value } },
                entranceProgress = entrance?.let { value -> { value } })
        }.also { frozenGeometry = it }
    }

    fun record(current: LyricWindowGeometry) { geometry = current }

    fun constraints(current: Constraints, frozen: Boolean): Constraints =
        if (frozen) constraints ?: current else current.also { constraints = it }
}

internal fun Modifier.lyricFrozenWindowLayout(state: LyricFrozenWindowLayout, frozen: Boolean): Modifier =
    layout { measurable, constraints ->
        val child = measurable.measure(state.constraints(constraints, frozen))
        layout(constraints.constrainWidth(child.width), constraints.constrainHeight(child.height)) {
            child.place(0, 0)
        }
    }
