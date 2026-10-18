package com.musicone.demo

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PlaybackAudioInfoTest {
    private val first = "data:audio-first-${java.util.UUID.randomUUID()}"
    private val second = "data:audio-second-${java.util.UUID.randomUUID()}"

    @Before fun reset() { PlaybackAudioInfo.clear() }
    @After fun clean() { PlaybackAudioInfo.clear() }

    @Test fun firstPlaybackPublishesKnownCodecThenAddsRealDecoderSpecifications() {
        PlaybackAudioInfo.remember(first, "AAC", mergeDecoded = true, bitrate = 192000)
        assertNull(PlaybackAudioInfo.current.value)
        PlaybackAudioInfo.activate(first)
        assertEquals(first to "AAC · 192kbps", PlaybackAudioInfo.current.value)
        PlaybackAudioInfo.publish(first, "audio/mp4a-latm", 44100, -1)
        assertEquals(first to "AAC · 192kbps · 44.1kHz", PlaybackAudioInfo.current.value)
        PlaybackAudioInfo.publish(first, "audio/mp4a-latm", -1, -1)
        assertTrue(PlaybackAudioInfo.current.value!!.second.contains("44.1kHz"))
    }

    @Test fun delayedOldTrackMetadataCannotReplaceActiveTrackOrValidation() {
        PlaybackAudioInfo.activate(first)
        PlaybackAudioInfo.publish(first, "audio/flac", 96000, 0, 24)
        PlaybackAudioInfo.activate(second)
        PlaybackAudioInfo.remember(first, "旧曲规格")
        PlaybackAudioInfo.publish(first, "audio/flac", 44100, 0, 16)
        PlaybackAudioInfo.verify(first, true)
        assertNull(PlaybackAudioInfo.current.value)
        assertNull(PlaybackAudioInfo.validation.value)
        PlaybackAudioInfo.publish(second, "audio/flac", 192000, 0, 24)
        assertEquals(second to "FLAC · 24bit / 192kHz", PlaybackAudioInfo.current.value)
    }

    @Test fun preciseFileHeaderIsRetainedAndDolbyStillReceivesRealSampleRate() {
        PlaybackAudioInfo.remember(first, "FLAC · 24bit / 96kHz")
        PlaybackAudioInfo.activate(first)
        PlaybackAudioInfo.publish(first, "audio/flac", 96000, -1)
        assertEquals(first to "FLAC · 24bit / 96kHz", PlaybackAudioInfo.current.value)
        PlaybackAudioInfo.remember(second, "Dolby Atmos", mergeDecoded = true, bitrate = 384000)
        PlaybackAudioInfo.activate(second)
        PlaybackAudioInfo.publish(second, "audio/eac3-joc", 48000, -1)
        assertEquals(second to "Dolby Atmos · E-AC-3 · 384kbps · 48kHz", PlaybackAudioInfo.current.value)
    }
}
