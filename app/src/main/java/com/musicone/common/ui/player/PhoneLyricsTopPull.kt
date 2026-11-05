package com.musicone

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** 当前可操作歌词列表的顶部边界；开合手势只通过它读取和滚动列表，不持有歌词窗口。 */
internal class PhoneLyricsListEdge {
    private var owner: LyricLayerTransitionController? = null

    fun attach(controller: LyricLayerTransitionController) { owner = controller }
    fun detach(controller: LyricLayerTransitionController) { if (owner === controller) owner = null }

    fun atTop(): Boolean = owner?.let {
        it.state == LyricLayerTransitionState.NORMAL && !it.currentPane.isFrozen &&
            !it.currentPane.listState.canScrollBackward
    } == true

    /** 手指上推的距离为负，列表滚动方向与手指相反。 */
    fun scrollList(fingerDelta: Float) {
        owner?.currentPane?.listState?.dispatchRawDelta(-fingerDelta)
    }
}

/** 只登记正在交互的窗口；切歌交接中的旧窗口不能决定是否到顶。 */
@Composable
internal fun PublishPhoneLyricsListEdge(edge: PhoneLyricsListEdge?, controller: LyricLayerTransitionController, active: Boolean) {
    DisposableEffect(edge, controller, active) {
        if (active) edge?.attach(controller)
        onDispose { edge?.detach(controller) }
    }
}

internal data class LyricsTopPullSplit(val progress: Float, val list: Float)

/** 下拉先把列表滚回顶部再关闭；上推先把歌词推回全开再滚动列表。 */
internal fun lyricsTopPullSplit(delta: Float, progress: Float, travel: Float, atTop: Boolean): LyricsTopPullSplit {
    if (delta >= 0f) return if (atTop) LyricsTopPullSplit(-delta / travel, 0f) else LyricsTopPullSplit(0f, delta)
    val toOpen = min(-delta, (1f - progress).coerceAtLeast(0f) * travel)
    return LyricsTopPullSplit(toOpen / travel, delta + toOpen)
}

/** 等到列表已在顶部且手指正在下移；返回的事件作为接管起点，起点之前的滚动不计入关闭距离。 */
internal suspend fun AwaitPointerEventScope.awaitLyricsTopPullStart(pointer: PointerId, canPull: () -> Boolean): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == pointer } ?: return null
        if (!change.pressed || change.isConsumed || event.changes.count { it.pressed } > 1) return null
        if (change.position.y > change.previousPosition.y && canPull()) return change
    }
}

internal val LANDSCAPE_LYRICS_TOP_PULL_DISTANCE = 64.dp

/** 累计到顶之后的净下拉距离；离开顶部后清零。 */
internal fun landscapeLyricsTopPullDistance(pulled: Float, delta: Float, atTop: Boolean): Float =
    if (atTop) (pulled + delta).coerceAtLeast(0f) else 0f

/** 横屏歌词开合不跟手，到顶后继续下拉到固定距离就切到时钟；坐标已是旋转后的列表坐标。 */
@Composable
internal fun Modifier.landscapeLyricsTopPull(edge: PhoneLyricsListEdge?, enabled: Boolean, onClose: () -> Unit): Modifier {
    if (edge == null) return this
    val latestClose by rememberUpdatedState(onClose)
    return pointerInput(edge, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var pulled = 0f
            var closed = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || event.changes.count { it.pressed } > 1) break
                // 切到时钟后这根手指不再滚动正在退场的歌词。
                if (closed) { change.consume(); continue }
                pulled = landscapeLyricsTopPullDistance(pulled, change.position.y - change.previousPosition.y, edge.atTop())
                if (pulled >= LANDSCAPE_LYRICS_TOP_PULL_DISTANCE.toPx()) {
                    closed = true
                    change.consume()
                    latestClose()
                }
            }
        }
    }
}
