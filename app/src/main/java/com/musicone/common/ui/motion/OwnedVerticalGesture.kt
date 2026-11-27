package com.musicone

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.geometry.Offset

internal data class VerticalGestureEnd(val completed: Boolean, val timeMillis: Long)

/** 在 Initial 阶段拦截纵向拖动：接管后一直追踪同一根手指，并在抬手前先采样最后一帧位移。 */
internal suspend fun AwaitPointerEventScope.awaitOwnedVerticalGesture(
    down: PointerInputChange,
    tracker: VelocityTracker,
    release: GestureReleaseVelocity,
    begin: (Float) -> Boolean,
    move: (Float) -> Unit,
    observe: (Float) -> Unit = {},
): VerticalGestureEnd? {
    var x = 0f
    var y = 0f
    var owned = false
    var time = down.uptimeMillis
    var sampledPosition = down.position
    var sampledTime = down.uptimeMillis
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val change = event.changes.firstOrNull { it.id == down.id }
            ?: return if (owned) VerticalGestureEnd(false, time) else null
        time = change.uptimeMillis
        // 多指触摸或事件流丢失时取消手势；一旦已接管，子控件消费事件不再打断拖动。
        if (event.changes.count { it.pressed } > 1 || (!owned && change.isConsumed)) {
            return if (owned) VerticalGestureEnd(false, time) else null
        }
        // 把批处理事件里的历史采样点也喂给速度估算器，画面仍按本次完整位移更新。
        for (historical in change.historical) {
            if (historical.uptimeMillis > sampledTime && historical.uptimeMillis < time) {
                tracker.addPosition(historical.uptimeMillis, historical.position)
                release.moved(historical.uptimeMillis, historical.position.y - sampledPosition.y)
                sampledPosition = historical.position
                sampledTime = historical.uptimeMillis
            }
        }
        val tail = change.position.y - sampledPosition.y
        val acceptedTail = if (owned && !change.pressed) release.finalDelta(time, tail) else tail
        val position = Offset(change.position.x, sampledPosition.y + acceptedTail)
        val delta = position - change.previousPosition
        x += delta.x
        y += delta.y
        // 用完整轨迹判断松手意图，视觉跟手移动时则扣掉启动阈值（touchSlop）。
        observe(delta.y)
        tracker.addPosition(time, position)
        release.moved(time, acceptedTail)
        sampledPosition = position
        sampledTime = time
        if (!owned) {
            when (verticalSlopIntent(x, y, viewConfiguration.touchSlop)) {
                -1 -> return null
                1 -> {
                    val over = y - if (y > 0f) viewConfiguration.touchSlop else -viewConfiguration.touchSlop
                    // 方向锁定后如果不符合当前手势条件就立刻放手，避免中途反向又抢走子控件手势。
                    if (!begin(over)) return null
                    owned = true
                    move(over)
                }
            }
        } else move(delta.y)
        if (owned) change.consume()
        if (!change.pressed) return if (owned) VerticalGestureEnd(true, time) else null
    }
}
