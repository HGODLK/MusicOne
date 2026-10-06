package com.musicone.demo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp

internal class PhoneLyricsTouchRegion {
    var cover = Rect.Zero
}

/** 在不缩放的舞台坐标系识别封面拖动，避免小封面缩放放大手指位移。 */
@Composable
internal fun Modifier.phoneLyricsGesture(
    motion: PhoneLyricsMotion,
    region: PhoneLyricsTouchRegion,
    enabled: Boolean,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
): Modifier {
    val latestVisible by rememberUpdatedState(visible)
    val latestChange by rememberUpdatedState(onVisibleChange)
    return pointerInput(motion, region, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!region.cover.inflate(12.dp.toPx()).contains(down.position)) return@awaitEachGesture
            val original = latestVisible
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var started = false
            var distance = 0f
            var initial = motion.lyricsProgress
            val travel = motion.dragTravelPx.coerceAtLeast(1f)
            fun move(delta: Float) {
                distance -= delta
                motion.dragTo(initial + distance / travel)
            }
            try {
                val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                    if ((over < 0f && motion.lyricsProgress < 1f) || (over > 0f && motion.lyricsProgress > 0f)) {
                        initial = motion.lyricsProgress
                        motion.beginDrag()
                        started = true
                        change.consume()
                        move(over)
                    }
                }
                if (start != null && started) {
                    val completed = verticalDrag(start.id) { change ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        move(change.position.y - change.previousPosition.y)
                        change.consume()
                    }
                    val target = if (completed) lyricsDragTarget(motion.lyricsProgress, tracker.calculateVelocity().y)
                        else original
                    motion.finishDrag()
                    started = false
                    latestChange(target)
                }
            } finally {
                if (started) {
                    motion.finishDrag()
                    latestChange(original)
                }
            }
        }
    }
}

internal fun lyricsDragTarget(progress: Float, velocityY: Float): Boolean = when {
    progress <= .08f -> false
    progress >= .92f -> true
    velocityY < -900f -> true
    velocityY > 900f -> false
    else -> progress >= .5f
}
