package com.musicone.demo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAudioCapabilitiesTest {
    @Test fun eac3DecoderCanUseTheJocCompatibilityPath() {
        assertTrue(listOf("audio/eac3").supportsDolbyPlayback())
        assertTrue(listOf("AUDIO/EAC3").supportsDolbyPlayback())
    }

    @Test fun eac3JocAndAc4AreDolbyDecoderCandidates() {
        assertTrue(listOf("audio/mp4a-latm", "audio/eac3-joc").supportsDolbyPlayback())
        assertTrue(listOf("AUDIO/EAC3-JOC").supportsDolbyPlayback())
        assertTrue(listOf("audio/ac4").supportsDolbyPlayback())
        assertTrue(listOf("AUDIO/AC4").supportsDolbyPlayback())
    }

    @Test fun ac3AndOrdinaryAudioDoNotEnableDolbyPlayback() {
        assertFalse(listOf("audio/ac3", "audio/mp4a-latm", "audio/mpeg").supportsDolbyPlayback())
        assertFalse(emptyList<String>().supportsDolbyPlayback())
    }
}
