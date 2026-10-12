package com.musicone.demo

import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.geometry.Offset

internal data class VerticalGestureEnd(val completed: Boolean, val timeMillis: Long)

/** 复用歌单的前置分发方式；接管后持续持有同一手指，并先采样最后位移再处理抬手。 */
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
        // 多指和输入流丢失属于真正取消；子级消费不再中断已经接管的拖动。
        if (event.changes.count { it.pressed } > 1 || (!owned && change.isConsumed)) {
            return if (owned) VerticalGestureEnd(false, time) else null
        }
        // 合并事件的历史点也参与估速，画面仍只在本次事件提交一次完整位移。
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
        // 完整轨迹用于松手意图，视觉跟手仍扣除启动死区。
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
                    // 已锁定的方向不属于此操作时立即让出，不能在途中反向后抢走子级手势。
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
