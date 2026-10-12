package com.musicone.demo

import android.os.SystemClock
import androidx.compose.runtime.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext

internal fun continuousLyricPosition(
    positionMs: Long, sampledAtMs: Long, nowMs: Long,
    advancing: Boolean, seeking: Boolean, durationMs: Long,
): Long {
    val elapsed = if (advancing && !seeking && sampledAtMs > 0L) (nowMs - sampledAtMs).coerceAtLeast(0L) else 0L
    val position = positionMs.coerceAtLeast(0L) + elapsed
    return if (durationMs > 0L) position.coerceAtMost(durationMs) else position
}

/** 一个歌词窗口共用一个帧循环，采样时间来自播放器，暂停和预览不自行推进。 */
@Composable
internal fun rememberContinuousLyricClock(
    progressMs: () -> Long,
    sampledAtMs: () -> Long,
    advancing: () -> Boolean,
    seeking: () -> Boolean,
    durationMs: Long,
    enabled: Boolean,
): () -> Long {
    val latestPosition by rememberUpdatedState(progressMs)
    val latestSample by rememberUpdatedState(sampledAtMs)
    val latestAdvancing by rememberUpdatedState(advancing)
    val latestSeeking by rememberUpdatedState(seeking)
    val latestDuration by rememberUpdatedState(durationMs)
    val readPosition = remember {
        { continuousLyricPosition(latestPosition(), latestSample(), SystemClock.elapsedRealtime(),
            latestAdvancing(), latestSeeking(), latestDuration) }
    }
    val running = advancing() && !seeking()
    val frameClock = LocalLyricAnimationClock.current
    val position = produceState(initialValue = readPosition(), key1 = enabled, key2 = running, key3 = sampledAtMs()) {
        value = readPosition()
        if (enabled && running) withContext(frameClock ?: EmptyCoroutineContext) {
            while (isActive) withFrameNanos { value = readPosition() }
        }
    }
    val framedPosition = remember(position) { { position.value } }
    return if (enabled && running) framedPosition else readPosition
}
