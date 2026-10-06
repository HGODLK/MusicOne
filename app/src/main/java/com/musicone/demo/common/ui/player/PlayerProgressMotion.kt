package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs

@Stable
internal class PlayerProgressMotionState(initialValue: Float) {
    private val progress = Animatable(initialValue.coerceIn(0f, 1f))
    private var seekRevision = 0L
    private var pendingSeek: Pair<Long, Float>? = null
    val value: Float get() = progress.value

    internal suspend fun snapTo(value: Float) {
        pendingSeek = null
        progress.snapTo(value.coerceIn(0f, 1f))
    }

    internal fun beginSeek(value: Float): Long {
        val revision = ++seekRevision
        pendingSeek = revision to value.coerceIn(0f, 1f)
        return revision
    }

    internal fun hasPendingSeek(): Boolean = pendingSeek != null

    internal suspend fun animateSeek(revision: Long) {
        val target = pendingSeek?.takeIf { it.first == revision }?.second ?: return
        try {
            progress.animateTo(target, musicMotion(240))
        } finally {
            if (pendingSeek?.first == revision) pendingSeek = null
        }
    }

    internal suspend fun animateReset() {
        pendingSeek = null
        progress.animateTo(0f, musicMotion(220))
    }

    internal suspend fun alignTo(value: Float, durationMs: Int) {
        progress.animateTo(value.coerceIn(0f, 1f), musicMotion(durationMs))
    }

    internal suspend fun advanceTo(value: Float, durationMs: Int) {
        progress.animateTo(
            value.coerceIn(0f, 1f),
            tween(durationMs.coerceAtLeast(1), easing = LinearEasing),
        )
    }
}

@Composable
internal fun rememberPlayerProgressMotion(
    trackId: String,
    positionMs: Long,
    durationMs: Long,
    advancing: Boolean,
    switching: Boolean,
    lyricSeek: LyricProgressSeek? = null,
    active: Boolean = true,
): PlayerProgressMotionState {
    val authoritative = playerProgressFraction(positionMs, durationMs)
    val motion = remember { PlayerProgressMotionState(authoritative) }
    var trackedTrackId by remember { mutableStateOf(trackId) }
    var switchOriginTrackId by remember { mutableStateOf<String?>(null) }
    var wasSwitching by remember { mutableStateOf(false) }
    var pendingResetTrackId by remember { mutableStateOf<String?>(null) }
    var handledLyricSeek by remember { mutableStateOf(lyricSeek?.revision) }
    var wasActive by remember { mutableStateOf(active) }
    val newLyricSeek = lyricSeek != null && lyricSeek.revision != handledLyricSeek && lyricSeek.trackId == trackId

    LaunchedEffect(trackId, lyricSeek, switching, active) {
        if (!active) { handledLyricSeek = lyricSeek?.revision; return@LaunchedEffect }
        if (switching || lyricSeek?.trackId != trackId) {
            handledLyricSeek = lyricSeek?.revision
            return@LaunchedEffect
        }
        if (!newLyricSeek) return@LaunchedEffect
        val request = lyricSeek
        val revision = motion.beginSeek(request.fraction)
        handledLyricSeek = request.revision
        // 音频已提交跳转，动画独立于周期进度回传，后续点击从当前帧续接。
        motion.animateSeek(revision)
    }

    LaunchedEffect(trackId, positionMs, durationMs, advancing, switching, active) {
        val resuming = active && !wasActive
        wasActive = active
        if (!active || resuming) {
            // 常驻页恢复时直接对齐当前进度，不能把隐藏期间的换曲补播成归零动画。
            trackedTrackId = trackId
            pendingResetTrackId = null
            switchOriginTrackId = null
            wasSwitching = false
            motion.snapTo(authoritative)
            if (!active) return@LaunchedEffect
        }
        val trackChanged = trackId != trackedTrackId
        if (switching) {
            if (!wasSwitching) switchOriginTrackId = trackedTrackId
            wasSwitching = true
            trackedTrackId = trackId
            pendingResetTrackId = null
            // 首次点击后归零；连续切歌只会继续收束到同一个零点。
            motion.animateReset()
            return@LaunchedEffect
        }
        if ((motion.hasPendingSeek() || newLyricSeek) && !trackChanged) return@LaunchedEffect

        if (trackChanged) {
            trackedTrackId = trackId
            pendingResetTrackId = trackId
        }
        val returnedToOrigin = wasSwitching && trackId == switchOriginTrackId
        wasSwitching = false
        switchOriginTrackId = null

        if (pendingResetTrackId == trackId) {
            motion.animateReset()
            pendingResetTrackId = null
            if (!advancing) return@LaunchedEffect
        } else if (returnedToOrigin) {
            // 连点后回到原曲时，解除冻结并柔和追上仍在播放的真实位置。
            motion.alignTo(authoritative, 240)
        }

        if (!advancing) {
            motion.alignTo(authoritative, 120)
            return@LaunchedEffect
        }

        val driftMs = abs(motion.value - authoritative) * durationMs.coerceAtLeast(1L)
        if (driftMs > PROGRESS_DISCONTINUITY_MS) motion.snapTo(authoritative)
        val targetPosition = (positionMs + PROGRESS_SAMPLE_MS).coerceAtMost(durationMs.coerceAtLeast(0L))
        motion.advanceTo(
            playerProgressFraction(targetPosition, durationMs),
            (targetPosition - positionMs).coerceIn(1L, PROGRESS_SAMPLE_MS).toInt(),
        )
    }
    return motion
}

internal fun playerProgressFraction(positionMs: Long, durationMs: Long): Float =
    (positionMs.coerceAtLeast(0L).toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)

private const val PROGRESS_SAMPLE_MS = 1_000L
private const val PROGRESS_DISCONTINUITY_MS = 2_000f
