package com.musicone

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink

/** 播放进度严格按实际消耗掉的 PCM 帧数算，只进队列、暂停或按系统时间推算都不算数。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class LgPcmPlaybackClock(private val sampleRate: Int) {
    private var startUs = AudioSink.CURRENT_POSITION_NOT_SET
    private var submittedFrames = 0L

    fun submitted(presentationTimeUs: Long, frames: Long) {
        if (startUs == AudioSink.CURRENT_POSITION_NOT_SET) {
            startUs = presentationTimeUs - submittedFrames * C.MICROS_PER_SECOND / sampleRate
        }
        submittedFrames += frames
    }

    fun positionUs(completedFrames: Long): Long =
        if (startUs == AudioSink.CURRENT_POSITION_NOT_SET) startUs
        else startUs + completedFrames.coerceIn(0L, submittedFrames) * C.MICROS_PER_SECOND / sampleRate

    fun discontinuity() { startUs = AudioSink.CURRENT_POSITION_NOT_SET }
}
