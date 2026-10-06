package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class AppearanceTransitionTest {
    @Test fun darkOverrideAndSystemChanges() {
        assertFalse(useDarkTheme(false, false))
        assertTrue(useDarkTheme(false, true))
        assertTrue(useDarkTheme(true, false))
        assertTrue(useDarkTheme(true, true))
    }

    @Test fun onlySelectedArtistLeavesTheMenuSnapshot() {
        assertFalse(retainMenuArtistSlot("elderbrook", true, "elderbrook"))
        assertTrue(retainMenuArtistSlot("bob-moses", true, "elderbrook"))
        assertTrue(retainMenuArtistSlot("bob-moses", true, null))
        assertFalse(retainMenuArtistSlot("outgoing-row", false, null))
    }

    @Test fun preparingPlayerKeepsUnderlyingStatusContrast() {
        assertTrue(useDarkStatusIcons(1f, 0f))
        assertFalse(useDarkStatusIcons(.1f, 0f))
        assertFalse(useDarkStatusIcons(1f, 1f))
        assertTrue(useDarkStatusIcons(1f, .2f))
    }
}
