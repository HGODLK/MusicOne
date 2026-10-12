package com.musicone.demo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned

internal class PlayerTouchRegions {
    var lyricsBounds: Rect = Rect.Zero
    var lyricsGestureBounds: Rect = Rect.Zero
}

internal val LocalPlayerTouchRegions = staticCompositionLocalOf<PlayerTouchRegions?> { null }

internal fun Modifier.playerLyricsRegion(active: Boolean, visualTopExtension: Int = 0): Modifier = composed {
    val regions = LocalPlayerTouchRegions.current
    DisposableEffect(regions, active) {
        if (!active) regions?.lyricsBounds = Rect.Zero
        onDispose { regions?.lyricsBounds = Rect.Zero }
    }
    onGloballyPositioned {
        if (active) {
            val bounds = it.boundsInRoot()
            regions?.lyricsBounds = Rect(bounds.left,
                (bounds.top + visualTopExtension).coerceAtMost(bounds.bottom), bounds.right, bounds.bottom)
        }
    }
}

internal fun canStartPlayerDismiss(point: Offset, height: Float, lyrics: Rect): Boolean =
    point.y >= 0f && point.y < height * .82f && !lyrics.contains(point)

internal fun canStartPlayerDismiss(point: Offset, height: Float, regions: PlayerTouchRegions,
                                   hostOrigin: Offset): Boolean =
    canStartPlayerDismiss(point, height, regions.lyricsBounds.translate(-hostOrigin)) &&
        !regions.lyricsGestureBounds.contains(point + hostOrigin)

@Composable
internal fun Modifier.playerDismissGesture(motion: PageMotion, regions: PlayerTouchRegions,
    onExpandedChange: (Boolean) -> Unit): Modifier {
    val latestChange = rememberUpdatedState(onExpandedChange)
    return pointerInput(motion, regions) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (!canStartPlayerDismiss(down.position, size.height.toFloat(), regions,
                    motion.hostBounds.topLeft)) return@awaitEachGesture
            val velocityTracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            val release = GestureReleaseVelocity(down.uptimeMillis, density, stablePlayerSettlement = true)
            val displacement = PlayerGestureDisplacement(false)
            var dragging = false
            var travel = 1f
            fun move(amount: Float) {
                travel = ((motion.sourceSnapshot["surface"]?.bounds?.top ?: motion.hostBounds.bottom) - motion.hostBounds.top).coerceAtLeast(1f)
                motion.dragTo(motion.value - amount / travel)
            }
            try {
                // 起点已排除歌词列表和歌词页眉；明确下划后持有手势至正常抬手。
                val end = awaitOwnedVerticalGesture(down, velocityTracker, release,
                    begin = { over ->
                        (over > 0f && motion.beginDrag()).also { dragging = it }
                    }, move = ::move, observe = displacement::move)
                if (end != null && dragging) {
                    val completed = end.completed
                    dragging = false
                    val rawVelocityY = velocityTracker.calculateVelocity().y
                    val now = end.timeMillis
                    // 判定速度与动画接续速度分别计算，轻微抬手噪声不改变方向。
                    val intentVelocityY = release.released(now, rawVelocityY, completed)
                    val dismiss = shouldDismissPlayer(motion.value, intentVelocityY / density, completed,
                        displacement.distancePx / density, displacement.reverseDistancePx / density)
                    val settlingVelocity = -release.settlingVelocity(now, rawVelocityY, completed) / travel
                    motion.releaseDragWithVelocity(settlingVelocity, maxVelocity = 6f,
                        releaseTimeNanos = gestureReleaseTimeNanos(now))
                    motion.request(!dismiss, rebound = !dismiss)
                    latestChange.value(!dismiss)
                }
            } finally {
                if (dragging) {
                    val dismiss = shouldDismissPlayer(motion.value, 0f, false)
                    motion.releaseDragWithVelocity(0f)
                    motion.request(!dismiss, rebound = !dismiss)
                    latestChange.value(!dismiss)
                }
            }
        }
    }
}
