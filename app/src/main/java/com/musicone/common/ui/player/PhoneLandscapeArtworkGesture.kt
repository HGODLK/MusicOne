package com.musicone

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** 点击和横向滑动共用一个识别器，拖动越过阈值后不再触发播放点击。 */
internal fun Modifier.phoneLandscapeArtworkGesture(enabled: Boolean, onToggle: () -> Unit,
    onPrevious: () -> Unit, onNext: () -> Unit): Modifier = composed {
    val toggle by rememberUpdatedState(onToggle)
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    semantics {
        role = Role.Button
        onClick("播放或暂停") { if (enabled) toggle(); enabled }
    }.pointerInput(enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var displacement = Offset.Zero
            var dragged = false
            var horizontal = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || event.changes.count { it.pressed } > 1 || change.isConsumed) {
                    break
                }
                displacement += change.position - change.previousPosition
                if (!dragged && displacement.getDistance() > viewConfiguration.touchSlop) {
                    dragged = true
                    horizontal = abs(displacement.x) > abs(displacement.y)
                }
                if (dragged && horizontal) change.consume()
                if (!change.pressed) {
                    if (!dragged) toggle()
                    else if (horizontal && abs(displacement.x) >= 48.dp.toPx()) {
                        if (displacement.x < 0f) next() else previous()
                    }
                    break
                }
            }
        }
    }
}
