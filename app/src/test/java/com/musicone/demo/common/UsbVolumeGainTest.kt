package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class UsbVolumeGainTest {
    @Test fun fallbackCurveIsBoundedAndRespondsToEveryVolumeStep() {
        assertEquals(0f, usbFallbackVolumeGain(0, 15), 0f)
        assertEquals(1f, usbFallbackVolumeGain(15, 15), 0f)
        assertEquals(1f, usbFallbackVolumeGain(20, 15), 0f)
        assertEquals(0f, usbFallbackVolumeGain(-1, 15), 0f)
        assertTrue((0..14).all { usbFallbackVolumeGain(it, 15) < usbFallbackVolumeGain(it + 1, 15) })
    }
}
