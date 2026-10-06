package com.musicone.demo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAudioCapabilitiesTest {
    @Test fun onlyEac3JocCountsAsDolbyAtmosSupport() {
        assertFalse(listOf("audio/eac3", "audio/ac3").supportsDolbyAtmos())
        assertTrue(listOf("audio/mp4a-latm", "audio/eac3-joc").supportsDolbyAtmos())
        assertTrue(listOf("AUDIO/EAC3-JOC").supportsDolbyAtmos())
    }
}
