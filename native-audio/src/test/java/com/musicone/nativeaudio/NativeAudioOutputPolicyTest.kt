package com.musicone.nativeaudio

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeAudioOutputPolicyTest {

    @Test
    fun testSelectDsdSampleRate() {
        assertEquals(192000, NativeAudioOutputPolicy.selectDsdPcmSampleRate())
        assertEquals(192000, NativeAudioOutputPolicy.selectDsdPcmSampleRate(setOf(44100, 48000, 96000, 176400, 192000)))
        assertEquals(96000, NativeAudioOutputPolicy.selectDsdPcmSampleRate(setOf(44100, 48000, 88200, 96000)))
        assertEquals(96000, NativeAudioOutputPolicy.selectDsdPcmSampleRate(setOf(44100, 48000, 96000)))
        assertEquals(176400, NativeAudioOutputPolicy.selectDsdPcmSampleRate(setOf(176400, 384000)))
        assertEquals(384000, NativeAudioOutputPolicy.selectDsdPcmSampleRate(setOf(384000)))
    }

    @Test
    fun testSelectPcmEncoding() {
        assertEquals(C.ENCODING_PCM_16BIT, NativeAudioOutputPolicy.selectPcmEncoding(16, isDsd = false))
        assertEquals(C.ENCODING_PCM_32BIT, NativeAudioOutputPolicy.selectPcmEncoding(24, isDsd = false))
        assertEquals(C.ENCODING_PCM_32BIT, NativeAudioOutputPolicy.selectPcmEncoding(32, isDsd = false))
        assertEquals(C.ENCODING_PCM_32BIT, NativeAudioOutputPolicy.selectPcmEncoding(1, isDsd = true))
    }

    @Test
    fun testFormatDescription() {
        val alacInfo = NativeAudioSourceInfo(
            formatName = "ALAC",
            sourceBitDepth = 24,
            sourceSampleRate = 96000,
            sourceChannels = 2,
            isDsd = false,
            outputSampleRate = 96000,
            outputChannels = 2,
            outputEncoding = C.ENCODING_PCM_32BIT,
            outputBytesPerFrame = 8,
            durationUs = 10_000_000L,
        )
        assertEquals("ALAC · 24bit / 96kHz", alacInfo.formatDescription())

        val dsdInfo = NativeAudioSourceInfo(
            formatName = "DSD",
            sourceBitDepth = 1,
            sourceSampleRate = 2822400,
            sourceChannels = 2,
            isDsd = true,
            dsdRateMultiplier = 64,
            outputSampleRate = 192000,
            outputChannels = 2,
            outputEncoding = C.ENCODING_PCM_32BIT,
            outputBytesPerFrame = 8,
            durationUs = 10_000_000L,
        )
        assertEquals("DSD64 → PCM 32bit / 192kHz", dsdInfo.formatDescription())
        for (level in listOf(64, 128, 256, 512)) {
            assertEquals("DSD$level → PCM 32bit / 192kHz",
                dsdInfo.copy(dsdRateMultiplier = level).formatDescription())
        }

        val apeInfo = NativeAudioSourceInfo(
            formatName = "APE",
            sourceBitDepth = 16,
            sourceSampleRate = 44100,
            sourceChannels = 2,
            isDsd = false,
            outputSampleRate = 44100,
            outputChannels = 2,
            outputEncoding = C.ENCODING_PCM_16BIT,
            outputBytesPerFrame = 4,
            durationUs = 60_000_000L,
        )
        assertEquals("APE · 16bit / 44.1kHz", apeInfo.formatDescription())

        val wvInfo = NativeAudioSourceInfo(
            formatName = "WavPack",
            sourceBitDepth = 24,
            sourceSampleRate = 48000,
            sourceChannels = 2,
            isDsd = false,
            outputSampleRate = 48000,
            outputChannels = 2,
            outputEncoding = C.ENCODING_PCM_32BIT,
            outputBytesPerFrame = 8,
            durationUs = 20_000_000L,
        )
        assertEquals("WavPack · 24bit / 48kHz", wvInfo.formatDescription())
    }
}
