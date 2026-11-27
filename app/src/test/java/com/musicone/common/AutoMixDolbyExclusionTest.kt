package com.musicone

import org.junit.Assert.*
import org.junit.Test

class AutoMixDolbyExclusionTest {
    @Test fun autoMixDisablesDolbyWithReason() {
        val on = DolbyPlaybackEligibility(decoderSupported = true, unlocked = false,
            usbOutputEnabled = false, usbAudioConnected = false, autoMixEnabled = true)
        assertFalse(on.allowed)
        assertFalse(on.automaticAllowed)
        assertEquals("已开启自然过渡，暂不可用", on.unavailableReason)
        val off = on.copy(autoMixEnabled = false)
        assertTrue(off.allowed)
        assertNull(off.unavailableReason)
    }

    @Test fun usbExclusiveReasonStillTakesPriority() {
        val both = DolbyPlaybackEligibility(true, false, usbOutputEnabled = true, usbAudioConnected = true, autoMixEnabled = true)
        assertEquals("USB独占输出时不可用", both.unavailableReason)
    }
}
