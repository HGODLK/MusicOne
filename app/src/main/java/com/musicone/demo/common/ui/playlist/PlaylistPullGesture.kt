package com.musicone.demo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs

internal class PlaylistPullState {
    var displacement by mutableStateOf(Offset.Zero)
    var releaseProgress by mutableFloatStateOf(1f)
    var released by mutableStateOf(false)
    var active by mutableStateOf(false)
    private var rebound = Animatable(0f)

    fun begin() {
        active = true
        released = false
        rebound = Animatable(0f)
    }

    fun returnToOrigin(motion: PageMotion) {
        motion.request(true, rebound = true, settle = {
            if (ExperiencePreferences.options.reduceMotion) rebound.snapTo(1f)
            else rebound.animateTo(1f, musicSpring(stiffness = 420f))
        })
    }

    fun artworkBounds(motion: PageMotion, source: Rect, target: Rect): Rect {
        if (!active || !motion.moving) return motionRect(source, target, motion.value)
        val dragged = pulledPlaylistArtworkBounds(target, displacement, if (released) releaseProgress else motion.value)
        if (!released) return dragged
        return if (motion.wantsOpen) {
            motionRect(dragged, target, rebound.value)
        } else motionRect(source, dragged, (motion.value / releaseProgress.coerceAtLeast(.001f)).coerceIn(0f, 1f))
    }
}

internal fun pulledPlaylistArtworkBounds(target: Rect, displacement: Offset, motionProgress: Float): Rect {
    val pullProgress = 1f - motionProgress.coerceIn(0f, 1f)
    val scale = 1f - .28f * pullProgress
    val center = target.center + displacement
    val halfWidth = target.width * scale / 2f
    val halfHeight = target.height * scale / 2f
    return Rect(center.x - halfWidth, center.y - halfHeight, center.x + halfWidth, center.y + halfHeight)
}

internal val LocalPlaylistPull = staticCompositionLocalOf<PlaylistPullState?> { null }

// 在按下瞬间锁定顶部资格；从中途滑到顶部必须松手后重新下拉。
internal fun Modifier.playlistPullGesture(
    scroll: LazyListState,
    motion: PageMotion?,
    pull: PlaylistPullState?,
    onClose: () -> Unit,
    canStart: () -> Boolean = { true },
    requireScrollAtTop: Boolean = true,
): Modifier {
    if (motion == null || pull == null) return this
    return composed {
    val close by rememberUpdatedState(onClose)
    val startAllowed by rememberUpdatedState(canStart)
    val minimumProgress = if (LocalEntityFrame.current != null) 0f else .15f
    pointerInput(scroll, motion, pull) {
        val activation = 32 * density
        val dismissDistance = 128 * density
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val eligible = startAllowed() && playlistPullStartEligible(
                scrollAtTop = scroll.firstVisibleItemIndex == 0 && scroll.firstVisibleItemScrollOffset == 0,
                scrollInProgress = scroll.isScrollInProgress,
                motionPhase = motion.phase,
                requireScrollAtTop = requireScrollAtTop,
            )
            if (!eligible) return@awaitEachGesture
            val velocityTracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var dragging = false
            var rejected = false
            var releasedNormally = false
            var displacement = Offset.Zero
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    if (event.changes.count { it.pressed } > 1) { rejected = true; break }
                    displacement += change.position - change.previousPosition
                    if (!dragging && !rejected) {
                        if (displacement.y < -viewConfiguration.touchSlop || abs(displacement.x) > activation) rejected = true
                        if (!rejected && displacement.y > activation && displacement.y > abs(displacement.x) * 1.5f) {
                            dragging = motion.beginDrag()
                            if (dragging) pull.begin()
                        }
                    }
                    if (dragging) {
                        change.consume()
                        pull.displacement = Offset(displacement.x, displacement.y - activation)
                        val downwardDistance = pull.displacement.y.coerceAtLeast(0f)
                        motion.dragTo(playlistPullProgress(downwardDistance, size.height * .65f, minimumProgress))
                    }
                    if (event.changes.none { it.pressed }) releasedNormally = true
                } while (event.changes.any { it.pressed })
            } finally {
                if (dragging) {
                    pull.releaseProgress = motion.value
                    pull.released = true
                    motion.releaseDragWithVelocity(
                        playlistProgressVelocity(velocityTracker.calculateVelocity().y, size.height * .65f),
                    )
                    if (releasedNormally && !rejected && pull.displacement.y >= dismissDistance) {
                        // 先让导航刷新返回锚点并发起关闭；回调未处理时再补上动画终点。
                        close()
                        if (motion.wantsOpen) motion.request(false)
                    } else pull.returnToOrigin(motion)
                }
            }
        }
    }
    }
}

internal fun playlistPullStartEligible(
    scrollAtTop: Boolean,
    scrollInProgress: Boolean,
    motionPhase: MotionPhase,
    requireScrollAtTop: Boolean = true,
): Boolean = (!requireScrollAtTop || scrollAtTop) && !scrollInProgress && motionPhase == MotionPhase.SHOWN

// 普通歌单为自由拖动封面预留归位进度；实体页沿固定路径可直接收回来源菜单。
internal fun playlistPullProgress(distance: Float, travel: Float, minimumProgress: Float = .15f): Float =
    (1f - distance.coerceAtLeast(0f) / travel.coerceAtLeast(1f)).coerceIn(minimumProgress, 1f)

internal fun playlistProgressVelocity(velocityY: Float, travel: Float): Float =
    (-velocityY / travel.coerceAtLeast(1f)).coerceIn(-1.6f, 1.6f)
