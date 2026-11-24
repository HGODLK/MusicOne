package com.musicone

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

    @Test fun negativeMaxSpeakerDecibelsAreNormalizedToUnityAtMaxVolume() {
        // 荣耀等机型内置扬声器满档为负分贝时，满档仍应给到满幅增益，中间档相对满档衰减。
        assertEquals(1f, usbSystemVolumeGain(15, 15, -18f, -18f), 0f)
        assertEquals(.501f, usbSystemVolumeGain(12, 15, -24f, -18f), .002f)
        assertEquals(0f, usbSystemVolumeGain(1, 15, Float.NEGATIVE_INFINITY, -18f), 0f)
        // 非满档分贝与满档相同时退回连续软件曲线。
        assertEquals(usbFallbackVolumeGain(10, 15), usbSystemVolumeGain(10, 15, -18f, -18f), 1e-6f)
    }
}
