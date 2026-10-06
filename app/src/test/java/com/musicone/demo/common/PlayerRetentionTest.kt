package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlayerRetentionTest {
    @Test fun hiddenPageStaysMountedAfterWarmupAndRepeatedOpening() {
        val state = PlayerRetentionState(true)
        assertFalse(state.launchReady)
        state.markWarmed()
        repeat(3) {
            assertTrue(state.shouldMount(true))
            assertTrue(state.shouldMount(false))
        }
        assertTrue(state.launchReady)
    }

    @Test fun memoryPressureReleasesHiddenPageWithoutBlockingLaunchOrRecreatingIt() {
        val state = PlayerRetentionState(true)
        state.markWarmed()
        state.release()
        state.markWarmed()
        assertFalse(state.warmed)
        assertFalse(state.shouldMount(false))
        assertTrue(state.launchReady)
        assertTrue(state.shouldMount(true))
        assertFalse(state.shouldMount(false))
    }

    @Test fun pressureDuringPlaybackDoesNotRemoveVisiblePage() {
        val state = PlayerRetentionState(true)
        state.release()
        assertTrue(state.shouldMount(true))
        assertFalse(state.shouldMount(false))
    }

    @Test fun backgroundReleasesEvenExpandedPageAndForegroundCanRestoreIt() {
        val state = PlayerRetentionState(true)
        state.foreground = false
        assertFalse(state.shouldMount(true))
        assertFalse(state.shouldMount(false))
        assertTrue(state.launchReady)
        state.release()
        state.foreground = true
        assertTrue(state.shouldMount(true))
        assertFalse(state.shouldMount(false))
    }

    @Test fun lowRamAndSystemPressureSkipPrewarmEvenWithAmpleHeap() {
        assertFalse(safe(lowRam = true))
        assertFalse(safe(systemLow = true))
        val state = PlayerRetentionState(false)
        assertTrue(state.launchReady)
        assertFalse(state.shouldMount(false))
        assertTrue(state.shouldMount(true))
    }

    @Test fun smallHeapKeepsAbsoluteReserveAndLargeHeapKeepsProportionalReserve() {
        assertFalse(safe(maxMb = 128, usedMb = 97))
        assertTrue(safe(maxMb = 128, usedMb = 96))
        assertFalse(safe(maxMb = 512, usedMb = 449))
        assertTrue(safe(maxMb = 512, usedMb = 448))
        assertFalse(safe(maxMb = 128, usedMb = 129))
    }

    private fun safe(lowRam: Boolean = false, systemLow: Boolean = false,
        maxMb: Long = 256, usedMb: Long = 64) =
        playerRetentionMemorySafe(lowRam, systemLow, maxMb * 1024 * 1024, usedMb * 1024 * 1024)
}
