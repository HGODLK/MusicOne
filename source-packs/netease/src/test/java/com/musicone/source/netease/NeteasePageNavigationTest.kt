package com.musicone.source.netease

import org.junit.Assert.*
import org.junit.Test

class NeteasePageNavigationTest {
    @Test fun switchingToMyNeverRendersThePreviousHomeOrInternalPage() {
        assertEquals("my", neteaseVisiblePage("my", true, "home", "home"))
        assertEquals("my", neteaseVisiblePage("my", true, "playlist", "home"))
        assertEquals("home", neteaseVisiblePage("home", true, "my", "my"))
    }

    @Test fun preloadedRootNeverDisplaysTheActiveRootsInternalPage() {
        assertEquals("home", neteaseVisiblePage("home", false, "authentication", "my"))
        assertEquals("authentication", neteaseVisiblePage("my", true, "authentication", "my"))
    }

    @Test fun backRestoresTheCallingRootAndPrimarySelectionClearsOnlyInternalHistory() {
        val reports = mutableListOf<String>()
        val navigation = NeteasePageNavigation(reports::add)
        navigation.primary("my")
        navigation.open("authentication")
        assertTrue(navigation.back())
        assertEquals("my", navigation.page)
        navigation.open("settings")
        navigation.primary("home")
        assertFalse(navigation.back())
        assertEquals("home", navigation.root)
        assertEquals("home", reports.last())
    }
}
