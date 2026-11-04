package com.musicone

import android.os.SystemClock
import androidx.compose.runtime.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext

/** 一个歌词窗口共用一个帧循环，采样时间来自播放器，暂停和预览不自行推进。 */
@Composable
internal fun rememberContinuousLyricClock(
    progressMs: () -> Long,
    sampledAtMs: () -> Long,
    advancing: () -> Boolean,
    seeking: () -> Boolean,
    durationMs: Long,
    enabled: Boolean,
    turnHeld: Boolean = false,
    resetKey: Any? = null,
): () -> Long {
    val latestPosition by rememberUpdatedState(progressMs)
    val latestSample by rememberUpdatedState(sampledAtMs)
    val latestAdvancing by rememberUpdatedState(advancing)
    val latestSeeking by rememberUpdatedState(seeking)
    val latestDuration by rememberUpdatedState(durationMs)
    val latestHeld by rememberUpdatedState(turnHeld)
    val presentation = remember { LyricTurnProgress() }
    val readPosition = remember {
        { continuousLyricPosition(latestPosition(), latestSample(), SystemClock.elapsedRealtime(),
            latestAdvancing(), latestSeeking(), latestDuration) }
    }
    val running = advancing() && !seeking()
    val frameClock = LocalLyricAnimationClock.current
    val position = produceState(readPosition(), enabled, running, sampledAtMs(), turnHeld, resetKey) {
        fun sample() = presentation.sample(readPosition(), SystemClock.elapsedRealtimeNanos(), turnHeld,
            resetKey, latestSeeking(), ExperiencePreferences.options.reduceMotion)
        value = sample()
        if (enabled && running || presentation.catchingUp) withContext(frameClock ?: EmptyCoroutineContext) {
            while (isActive && (enabled && running || presentation.catchingUp)) withFrameNanos { value = sample() }
        }
    }
    val latestRunning by rememberUpdatedState(enabled && running)
    return remember(position) {
        { if (latestHeld || presentation.presenting || latestRunning) position.value else readPosition() }
    }
}
