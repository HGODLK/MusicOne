package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/** 记录并锁定歌词当前阅读行的文字基线，供横竖屏切换时作为旋转枢轴（动画帧不遍历坐标）。 */
internal class PhoneLyricsReadingAnchor {
    private data class Row(val token: Any, val coordinates: LayoutCoordinates, val layout: () -> TextLayoutResult?)
    private var pane: LyricWindowPane? = null
    private var trackId: String? = null
    private var current = 0
    private var following = true
    var position by mutableStateOf<LyricReadingPosition?>(null)
        private set
    val held get() = position != null
    private var prepared: Triple<Int, Int, LyricPlacementGeometry>? = null
    private var preservingRowMotion = false
    private val rows = mutableMapOf<Int, Row>()
    private val pivots = mutableMapOf<Triple<Size, IntSize, Offset>, Offset>()
    private var pivotHeld = false
    private var drawingWindow = PhoneLyricsDrawingWindow.None
    private val heldPanes = mutableSetOf<LyricWindowPane>()

    fun bind(track: String, value: LyricWindowPane, line: Int, follow: Boolean = true,
        drawing: PhoneLyricsDrawingWindow = PhoneLyricsDrawingWindow.None) {
        if (trackId != track) { trackId = track; release() }
        // 切换列表实例时清空旧行坐标，保持对同一句歌词的锁定。
        if (pane !== value) { pane = value; rows.clear() }
        if (held && heldPanes.add(value)) value.playbackStep.holdTurnPosition(true)
        current = line
        following = follow
        drawingWindow = drawing
    }

    private fun selectedLine(): Int = current.takeIf { following && it in rows } ?: pane?.listState?.layoutInfo
        ?.visibleItemsInfo?.minByOrNull { abs(it.offset) }?.index ?: current

    /** 确认目标行已挂载且完成排版后才锁定阅读基线，不拿估算值启动转屏动画。 */
    fun captureFrame(): Boolean {
        val row = rows[position?.line ?: selectedLine()] ?: return false
        if (pane?.listHost?.ready != true || !row.coordinates.isAttached || row.layout() == null) return false
        hold()
        return true
    }

    fun hold() {
        if (held) return
        pivotHeld = false
        pivots.clear()
        pane?.let { heldPanes.add(it); it.playbackStep.holdTurnPosition(true) }
        val line = selectedLine()
        val info = pane?.listState?.layoutInfo
        val item = info?.visibleItemsInfo?.firstOrNull { it.index == line }
        position = LyricReadingPosition(line,
            (item?.offset ?: 0) + (pane?.playbackStep?.offsetPx(line) ?: 0f),
            drawingWindow.logicalHeight(info?.viewportSize?.height ?: 0), following,
            item?.size ?: 0, pane?.currentLine ?: current)
        prepared = null
    }
    private fun releasePositions() {
        heldPanes.forEach { it.playbackStep.holdTurnPosition(false) }
        heldPanes.clear()
    }
    /** 固定当前算好的旋转枢轴，此时列表位置锁暂不释放，等首帧画完再放。 */
    fun pinForMotion() { pivotHeld = true }
    /** 保留旋转枢轴供转向动画使用，同时释放列表位置锁让歌词恢复正常滚动。 */
    fun releaseForMotion() { pivotHeld = true; position = null; prepared = null; releasePositions() }
    fun release() { pivotHeld = false; pivots.clear(); position = null; prepared = null; releasePositions() }
    fun clear() { pane = null; trackId = null; rows.clear(); release() }

    fun prepared(generation: Int, rotation: Int, geometry: LyricPlacementGeometry, preserveRowMotion: Boolean = false) {
        if (held) {
            prepared = Triple(generation, rotation, geometry)
            preservingRowMotion = preserveRowMotion
        }
    }
    fun ready(generation: Int, rotation: Int): Boolean {
        if (!held) return true
        val info = pane?.listState?.layoutInfo ?: return false
        val geometry = LyricPlacementGeometry(info.viewportSize.width, info.viewportSize.height, info.beforeContentPadding)
        val item = info.visibleItemsInfo.firstOrNull { it.index == position?.line } ?: return false
        // 验收时把列表偏移和行内剩余位移动画加在一起算，不强求行动画归零。
        val rowOffset = if (preservingRowMotion) pane?.playbackStep?.offsetPx(position?.line ?: current) ?: 0f else 0f
        return prepared == Triple(generation, rotation, geometry) &&
            abs(item.offset + rowOffset - (position?.targetOffset(drawingWindow.logicalHeight(geometry.height), item.size) ?: 0)) <= 1f
    }

    fun placed(owner: LyricWindowPane, index: Int, token: Any, coordinates: LayoutCoordinates,
        layout: () -> TextLayoutResult?) {
        if (pane === owner) rows[index] = Row(token, coordinates, layout)
    }

    fun remove(owner: LyricWindowPane, index: Int, token: Any) {
        if (pane === owner && rows[index]?.token === token) rows.remove(index)
    }

    fun fraction(hostSize: Size, viewportSize: IntSize, viewportOffset: Offset): Offset? {
        val key = Triple(hostSize, viewportSize, viewportOffset)
        if (pivotHeld) pivots[key]?.let { return it }
        val viewport = pane?.listHost?.coordinates() ?: return null
        if (!viewport.isAttached || viewport.size.width != viewportSize.width ||
            drawingWindow.logicalHeight(viewport.size.height) != viewportSize.height) return null
        val row = rows[position?.line ?: selectedLine()] ?: return null
        val text = row.layout() ?: return null
        if (!row.coordinates.isAttached) return null
        // 相对列表容器计算基线坐标，排除外层的整页和转向变换，防止把动画补偿又算回终点。
        val point = runCatching {
            viewport.localPositionOf(row.coordinates, Offset(0f, text.firstBaseline)) - Offset(0f, drawingWindow.top.toFloat())
        }.getOrNull() ?: return null
        return phoneLyricsBaselineFraction(point, viewportOffset, hostSize)?.also {
            if (held || pivotHeld) pivots[key] = it
        }
    }
}

internal fun phoneLyricsBaselineFraction(point: Offset, viewportOffset: Offset, hostSize: Size): Offset? {
    if (hostSize.width <= 0f || hostSize.height <= 0f) return null
    val native = point + viewportOffset
    if (!native.x.isFinite() || !native.y.isFinite()) return null
    return Offset(native.x / hostSize.width, native.y / hostSize.height)
}

@Composable
internal fun Modifier.phoneLyricsReadingRow(anchor: PhoneLyricsReadingAnchor?, pane: LyricWindowPane?,
    index: Int, layout: () -> TextLayoutResult?): Modifier {
    if (anchor == null || pane == null) return this
    val token = remember(anchor, pane, index) { Any() }
    DisposableEffect(anchor, pane, index, token) { onDispose { anchor.remove(pane, index, token) } }
    return onGloballyPositioned { anchor.placed(pane, index, token, it, layout) }
}
