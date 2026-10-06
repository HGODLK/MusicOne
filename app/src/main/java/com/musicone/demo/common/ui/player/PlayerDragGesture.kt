package com.musicone.demo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

internal class PlayerTouchRegions {
    var lyricsBounds: Rect = Rect.Zero
}

internal val LocalPlayerTouchRegions = staticCompositionLocalOf<PlayerTouchRegions?> { null }

internal fun Modifier.playerLyricsRegion(active: Boolean): Modifier = composed {
    val regions = LocalPlayerTouchRegions.current
    DisposableEffect(regions, active) {
        if (!active) regions?.lyricsBounds = Rect.Zero
        onDispose { regions?.lyricsBounds = Rect.Zero }
    }
    onGloballyPositioned { if (active) regions?.lyricsBounds = it.boundsInRoot() }
}

internal fun canStartPlayerDismiss(point: Offset, height: Float, lyrics: Rect): Boolean =
    point.y >= 0f && point.y < height / 2f && !lyrics.contains(point)

@Composable
internal fun Modifier.playerDismissGesture(motion: PageMotion, regions: PlayerTouchRegions,
    onExpandedChange: (Boolean) -> Unit): Modifier {
    val latestChange = rememberUpdatedState(onExpandedChange)
    return pointerInput(motion, regions) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!canStartPlayerDismiss(down.position, size.height.toFloat(), regions.lyricsBounds)) return@awaitEachGesture
            val velocityTracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var dragging = false
            var travel = 1f
            fun move(amount: Float) {
                travel = ((motion.sourceSnapshot["surface"]?.bounds?.top ?: motion.hostBounds.bottom) - motion.hostBounds.top).coerceAtLeast(1f)
                motion.dragTo(motion.value - amount / travel)
            }
            try {
                // 子级先处理滚动；只有未被消费的向下手势才开始收起。
                val start = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                    if (over > 0f && motion.beginDrag()) {
                        dragging = true
                        change.consume()
                        move(over)
                    }
                }
                if (start != null && dragging) {
                    val completed = verticalDrag(start.id) { change ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        move(change.position.y - change.previousPosition.y)
                        change.consume()
                    }
                    dragging = false
                    val velocityY = velocityTracker.calculateVelocity().y
                    val dismiss = shouldDismissPlayer(motion.value, velocityY, completed)
                    motion.releaseDragWithVelocity((-velocityY / travel).coerceIn(-1.6f, 1.6f))
                    motion.request(!dismiss, rebound = !dismiss)
                    latestChange.value(!dismiss)
                }
            } finally {
                if (dragging) motion.request(true, rebound = true)
            }
        }
    }
}
