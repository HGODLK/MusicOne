package com.musicone.demo

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

/** 明确到达终点的位移优先；短划再按速度判断，不随页面高度增加门槛。 */
internal fun gestureTarget(progress: Float, velocity: Float, distanceDp: Float,
                           original: Boolean, threshold: Float = .5f,
                           dragDistanceDp: Float = Float.POSITIVE_INFINITY,
                           flingVelocityDp: Float = 650f): Boolean = when {
    abs(distanceDp) < 8f -> original
    distanceDp >= dragDistanceDp && (velocity > -flingVelocityDp || progress >= .9f) -> true
    distanceDp <= -dragDistanceDp && (velocity < flingVelocityDp || progress <= .1f) -> false
    velocity >= flingVelocityDp -> true
    velocity <= -flingVelocityDp -> false
    else -> progress >= threshold
}

/** 斜向起手先观察，纵向位移显著主导时再锁定纵向，避免横划切歌被误抢。 */
internal fun verticalSlopIntent(x: Float, y: Float, slop: Float): Int = when {
    abs(x) > slop * 2f && abs(x) > abs(y) * 1.5f -> -1
    abs(y) > slop && abs(y) >= abs(x) * 1.25f -> 1
    else -> 0
}

/** 在方向锁定之前也采样，避免快短划只留下拖动后的一个点。 */
internal suspend fun AwaitPointerEventScope.awaitTrackedVerticalSlop(
    down: PointerInputChange, tracker: VelocityTracker, release: GestureReleaseVelocity,
    onSlop: (PointerInputChange, Float) -> Unit,
): PointerInputChange? {
    var x = 0f
    var y = 0f
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return null
        if (!change.pressed || change.isConsumed || currentEvent.changes.count { it.pressed } > 1) return null
        val delta = change.position - change.previousPosition
        x += delta.x
        y += delta.y
        tracker.addPosition(change.uptimeMillis, change.position)
        release.moved(change.uptimeMillis, delta.y)
        when (verticalSlopIntent(x, y, viewConfiguration.touchSlop)) {
            -1 -> return null
            1 -> {
                onSlop(change, y - if (y > 0f) viewConfiguration.touchSlop else -viewConfiguration.touchSlop)
                if (change.isConsumed) return change
            }
        }
    }
}
