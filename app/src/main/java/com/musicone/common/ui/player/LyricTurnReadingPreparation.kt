package com.musicone

import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/** 播放跟随落到目标阅读锚点；手动浏览保留同一句及视口中的相对偏移。 */
internal data class LyricReadingPosition(val line: Int, val offset: Float, val height: Int, val following: Boolean,
    val rowHeight: Int = 0, val playbackLine: Int = line) {
    fun targetOffset(targetHeight: Int, targetRowHeight: Int = 0): Int = when {
        following -> 0
        offset < 0f && rowHeight > 0 && targetRowHeight > 0 ->
            (offset * targetRowHeight / rowHeight).roundToInt()
        else -> (offset * targetHeight / height.coerceAtLeast(1)).roundToInt()
    }
}

/** 目标排版和定位均在旧帧覆盖下完成，释放后不再重播首开对齐。 */
@Composable
internal fun LyricTurnReadingPreparation(controller: LyricLayerTransitionController,
    anchor: PhoneLyricsReadingAnchor?, turn: PhonePlayerTurn?, rotation: Int,
    expected: LyricPlacementGeometry,
    topLine: LyricTopLineAlignment, gestureRefocus: LyricGestureRefocus, restoreFollowing: (Boolean) -> Unit) {
    val position = anchor?.position
    val latestRestore by rememberUpdatedState(restoreFollowing)
    val generation = turn?.generation
    val preserveRowMotion = LocalPhoneLyricsColumnWidth.current != null
    val drawingWindow = LocalPhoneLyricsDrawingWindow.current
    LyricAnimationEffect(controller, position, generation, turn?.preparing, rotation, expected, preserveRowMotion) {
        if (position == null || turn?.preparing != true || !expected.measured) return@LyricAnimationEffect
        val readingAnchor = anchor ?: return@LyricAnimationEffect
        fun requestCurrent() = readingAnchor.position === position && turn.preparing &&
            turn.generation == generation && turn.targetRotation == rotation
        fun viewportReady(): Boolean {
            val pane = controller.currentPane
            val info = pane.listState.layoutInfo
            val actual = LyricPlacementGeometry(info.viewportSize.width, info.viewportSize.height, info.beforeContentPadding)
            return lyricTurnViewportReady(expected, actual, rotation, turn.targetRotation,
                pane.listHost.ready, turn.endpointReady("lyrics", rotation))
        }
        // 只等目标视口，不将每次旧尺寸／中间尺寸变化作为一次新的准备任务。
        snapshotFlow { viewportReady() }.first { it }
        if (!requestCurrent()) return@LyricAnimationEffect
        gestureRefocus.cancel()
        controller.settleForLayout(position.playbackLine)
        snapshotFlow { viewportReady() }.first { it }
        if (!requestCurrent()) return@LyricAnimationEffect
        val target = controller.currentPane
        val lease = target.listHost.scrollLease()
        target.playbackStep.prepareLayout(position.playbackLine, preserveOffsets = preserveRowMotion)
        val placed = placeLyricTurnReadingPosition(position,
            expected.copy(height = drawingWindow.logicalHeight(expected.height)),
            current = { requestCurrent() && target === controller.currentPane && lease() && viewportReady() },
            rowHeight = { target.listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == position.line }?.size },
            scrollTo = { line, offset -> target.listState.scrollToItem(line, offset) },
            rowMotionOffset = { if (preserveRowMotion) target.playbackStep.offsetPx(position.line) else 0f })
        if (placed) {
            // 清除旧入场任务，正常播放只从准备好的最终阅读位置接续。
            topLine.acceptSeekAlignment()
            latestRestore(position.following)
            readingAnchor.prepared(turn.generation, rotation, expected, preserveRowMotion)
        }
    }
}
