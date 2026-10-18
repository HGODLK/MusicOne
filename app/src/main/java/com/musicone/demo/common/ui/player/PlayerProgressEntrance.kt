package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 入场只控制绘制系数，实时播放进度与用户跳转仍由原时间轴管理。 */
@Stable
internal class PlayerProgressEntranceState(
    initialPhase: MotionPhase,
    private var trackId: String,
    private var lyricSeekRevision: Long?,
) {
    private var phase = initialPhase
    private var pending by mutableStateOf(initialPhase != MotionPhase.SHOWN)
    private var reveal = Animatable(0f)
    private var job: Job? = null
    val value: Float get() = if (pending) reveal.value else 1f

    fun cancel() {
        job?.cancel()
        job = null
        pending = false
    }

    fun update(nextPhase: MotionPhase, wantsOpen: Boolean, nextTrackId: String, switching: Boolean,
        nextLyricSeekRevision: Long?, scope: CoroutineScope, requestedAtNanos: Long) {
        val changed = nextTrackId != trackId || nextLyricSeekRevision != lyricSeekRevision
        trackId = nextTrackId
        lyricSeekRevision = nextLyricSeekRevision
        val previousPhase = phase
        phase = nextPhase
        if (phase == MotionPhase.HIDDEN) {
            if (previousPhase != MotionPhase.HIDDEN) {
                cancel()
                reveal = Animatable(0f)
                pending = true
            }
            return
        }
        // 页面拖动、关闭回弹与反向不打断本轮入场；真正隐藏后才重置。
        if (changed || switching) {
            cancel()
            return
        }
        if (pending && job == null && wantsOpen) {
            job = scope.launch {
                // 从打开请求计时，准备布局及整页转场消耗的时间也计入 278ms。
                delay(playerProgressEntranceDelayMs(requestedAtNanos, System.nanoTime()))
                reveal.animateTo(1f, musicMotion(360))
                pending = false
            }
        }
    }
}

@Composable
internal fun rememberPlayerProgressEntrance(motion: PageMotion, trackId: String,
    switching: Boolean, lyricSeekRevision: Long?): PlayerProgressEntranceState {
    val scope = rememberCoroutineScope()
    val phase = motion.phase
    val wantsOpen = motion.wantsOpen
    val requestedAtNanos = motion.openRequestedAtNanos
    val entrance = remember(motion) {
        PlayerProgressEntranceState(phase, trackId, lyricSeekRevision)
    }
    SideEffect { entrance.update(phase, wantsOpen, trackId, switching, lyricSeekRevision, scope, requestedAtNanos) }
    return entrance
}

internal fun playerProgressEntranceDelayMs(requestedAtNanos: Long, nowNanos: Long): Long {
    val elapsed = (nowNanos - requestedAtNanos).coerceAtLeast(0L)
    val remaining = (278_000_000L - elapsed).coerceAtLeast(0L)
    return (remaining + 999_999L) / 1_000_000L
}
