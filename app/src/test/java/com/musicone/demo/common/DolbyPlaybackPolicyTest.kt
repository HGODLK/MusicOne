package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class DolbyPlaybackPolicyTest {
    @Test fun usbExclusiveBlocksEveryDecoderAndUnlockCombination() {
        for (supported in listOf(false, true)) for (unlocked in listOf(false, true)) {
            val blocked = DolbyPlaybackEligibility(supported, unlocked, usbOutputEnabled = true, usbAudioConnected = true)
            assertFalse(blocked.allowed)
            assertEquals("USB独占输出时不可用", blocked.unavailableReason)
        }
    }

    @Test fun unsupportedDeviceRequiresExplicitUnlock() {
        assertFalse(DolbyPlaybackEligibility(false, false, false, false).allowed)
        assertTrue(DolbyPlaybackEligibility(false, true, false, false).allowed)
        assertTrue(DolbyPlaybackEligibility(true, false, false, false).allowed)
        assertNull(DolbyPlaybackEligibility(true, false, false, false).unavailableReason)
    }

    @Test fun enabledUsbWithoutAnAudioDeviceStillAllowsDolby() {
        assertTrue(DolbyPlaybackEligibility(true, false, true, false).allowed)
        assertTrue(DolbyPlaybackEligibility(false, true, true, false).allowed)
        assertFalse(DolbyPlaybackEligibility(false, false, true, false).allowed)
    }

    @Test fun connectingAndDisconnectingAudioOnlyBlocksWhileUsbOutputIsEnabled() {
        val enabled = DolbyPlaybackEligibility(true, false, true, false)
        assertTrue(enabled.allowed)
        assertFalse(enabled.copy(usbAudioConnected = true).allowed)
        assertTrue(enabled.copy(usbAudioConnected = true, usbOutputEnabled = false).allowed)
        assertTrue(enabled.copy(usbAudioConnected = true).copy(usbAudioConnected = false).allowed)
    }

    @Test fun dolbyIsOnlyOfferedForQqAndKeepsItsOwnName() {
        assertTrue(AudioQuality.DOLBY in playerQualitiesForSource(MusicSource.QQ))
        assertFalse(AudioQuality.DOLBY in playerQualitiesForSource(MusicSource.NETEASE))
        assertFalse(AudioQuality.DOLBY in playerQualitiesForSource(MusicSource.KUGOU))
        assertEquals("Dolby全景", AudioQuality.DOLBY.displayLabel(MusicSource.QQ))
        assertEquals("Dolby", AudioQuality.DOLBY.displayCompactLabel(MusicSource.QQ))
        assertEquals(AudioQuality.DOLBY, playerDisplayedQuality(AudioQuality.DOLBY, AudioQuality.DOLBY))
        assertEquals(AudioQuality.LOSSLESS, playerDisplayedQuality(AudioQuality.DOLBY, AudioQuality.LOSSLESS))
    }

    @Test fun dolbyDecodingMustNotEnterPcmUsbExclusiveEvenWhenDownmixed() {
        assertTrue(isDolbyAudioMime("audio/eac3-joc"))
        assertTrue(isDolbyAudioMime("audio/eac3"))
        assertTrue(isDolbyAudioMime("audio/ac4"))
        assertFalse(isDolbyAudioMime("audio/flac"))
        assertFalse(isDolbyAudioMime(null))
    }
}
