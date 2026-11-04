package com.musicone

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class LgPcmPlaybackClockTest {
    @Test fun bufferedButUnconsumedAudioDoesNotAdvancePlayback() {
        val clock = LgPcmPlaybackClock(44100)
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, clock.positionUs(0))
        clock.submitted(1_000_000_000_000L, 3528)
        assertEquals(1_000_000_000_000L, clock.positionUs(0))
        assertEquals(1_000_000_020_000L, clock.positionUs(882))
    }

    @Test fun consumptionAdvancesPastTenSecondsAtEverySupportedMusicRate() {
        for (rate in listOf(44100, 48000, 96000, 192000)) {
            val clock = LgPcmPlaybackClock(rate)
            val block = (rate / 50).toLong()
            clock.submitted(0, block * 4)
            for (index in 1..1500) {
                val timeUs = index * 20_000L
                assertEquals(timeUs, clock.positionUs(index * block))
                clock.submitted(timeUs + 60_000L, block)
            }
            assertEquals(30_000_000L, clock.positionUs(1500 * block))
        }
    }

    @Test fun noFurtherConsumptionDuringPauseKeepsTheSamePosition() {
        val clock = LgPcmPlaybackClock(48000)
        clock.submitted(5_000_000L, 3840)
        assertEquals(5_020_000L, clock.positionUs(960))
        repeat(100) { assertEquals(5_020_000L, clock.positionUs(960)) }
        assertEquals(5_040_000L, clock.positionUs(1920))
    }

    @Test fun seekOrNewStreamStartsANewTimelineWithoutOldFrames() {
        val old = LgPcmPlaybackClock(44100)
        old.submitted(0L, 441000)
        assertEquals(10_000_000L, old.positionUs(441000))
        val afterSeek = LgPcmPlaybackClock(44100)
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, afterSeek.positionUs(0))
        afterSeek.submitted(90_000_000L, 882)
        assertEquals(90_000_000L, afterSeek.positionUs(0))
        assertEquals(90_020_000L, afterSeek.positionUs(882))
    }

    @Test fun discontinuityReanchorsWithoutCountingQueuedFramesTwice() {
        val clock = LgPcmPlaybackClock(48000)
        clock.submitted(0L, 960)
        clock.discontinuity()
        assertEquals(AudioSink.CURRENT_POSITION_NOT_SET, clock.positionUs(960))
        clock.submitted(2_000_000L, 960)
        assertEquals(2_000_000L, clock.positionUs(960))
        assertEquals(2_020_000L, clock.positionUs(1920))
    }
}
