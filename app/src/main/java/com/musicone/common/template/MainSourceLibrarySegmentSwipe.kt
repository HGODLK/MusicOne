package com.musicone

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/**
 * “我的歌单”分区左右滑动：只在分区标题及以下生效，拖过 48dp 立即切换、短促甩动在松手时补判，
 * 一次按下最多切一个分区；调用与按钮相同的切换，动效不另做，过渡中可以接着滑。
 * 手指左滑去下一个分区；已在首尾分区、没有可去的方向时不接手，让根页面照常左右切页。
 */
@Composable
internal fun Modifier.librarySegmentSwipe(
    sections: List<Int>,
    selected: Int,
    enabled: Boolean,
    regionTop: () -> Float,
    onSelect: (Int) -> Unit,
): Modifier {
    val currentSections by rememberUpdatedState(sections)
    val currentSelected by rememberUpdatedState(selected)
    val active by rememberUpdatedState(enabled && sections.size > 1)
    val select by rememberUpdatedState(onSelect)
    fun target(fingerDx: Float): Int? {
        val index = currentSections.indexOf(currentSelected)
        if (index < 0 || fingerDx == 0f) return null
        return currentSections.getOrNull(if (fingerDx < 0f) index + 1 else index - 1)
    }
    return pointerInput(Unit) {
        awaitEachGesture {
            // 卡片点击会消费按下事件，这里仍要接住，是否横拖看后续移动有没有被子组件消费。
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!active || down.position.y < regionTop()) return@awaitEachGesture
            val release = GestureReleaseVelocity(down.uptimeMillis, density)
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            // 首尾方向不消费，交给外层根页面切页；纵向滚动、拖动排序先消费时这里自动放弃。
            val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                if (active && target(over) != null) change.consume()
            } ?: return@awaitEachGesture
            var distance = start.position.x - down.position.x
            var switched = false
            fun switchIfFar() {
                if (switched || !active || abs(distance) / density < LibrarySegmentSwipeDistanceDp) return
                switched = true
                target(distance)?.let(select)
            }
            tracker.addPosition(start.uptimeMillis, start.position)
            release.moved(start.uptimeMillis, distance)
            switchIfFar()
            val completed = horizontalDrag(start.id) { change ->
                tracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.positionChange().x
                distance += delta
                release.moved(change.uptimeMillis, delta)
                change.consume()
                switchIfFar()
            }
            if (switched || !completed || !active) return@awaitEachGesture
            val now = currentEvent.changes.firstOrNull { it.id == start.id }?.uptimeMillis ?: start.uptimeMillis
            val velocity = release.released(now, tracker.calculateVelocity().x, true)
            if (librarySegmentSwipeCommits(distance / density, release.effectiveDistancePx / density, velocity / density))
                target(distance)?.let(select)
        }
    }
}

/** 与迷你播放条切歌同一套门槛：拖过 48dp，或至少 12dp 且同向 240dp/s 的甩动。 */
internal const val LibrarySegmentSwipeDistanceDp = 48f

internal fun librarySegmentSwipeCommits(distanceDp: Float, effectiveDistanceDp: Float, velocityDp: Float): Boolean =
    abs(distanceDp) >= LibrarySegmentSwipeDistanceDp ||
        (abs(effectiveDistanceDp) >= 12f && effectiveDistanceDp * velocityDp > 0f && abs(velocityDp) >= 240f &&
            distanceDp * velocityDp > 0f)

/** 切换过渡中只在最早阶段吞掉按下：卡片和分段按钮收不到点击，横向移动不消费，分区横滑照常接手。 */
internal fun Modifier.blockLibraryTapsWhile(blocked: Boolean): Modifier = if (!blocked) this else pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { if (it.changedToDown()) it.consume() }
    }
}
