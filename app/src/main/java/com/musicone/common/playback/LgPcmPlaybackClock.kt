package com.musicone

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink

/** 媒体时间只随已消费的 PCM 帧推进；提交到队列、暂停或墙上时间经过都不能代替消费。 */
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
