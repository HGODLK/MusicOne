package com.musicone

import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTransitionTest {
    @Test fun returningFromManagementToSupplementalIsBackEvenWhenItsOrdinalIncreases() {
        val navigation = SettingsNavigation()
        navigation.openSettings()
        navigation.openSupplemental()
        navigation.openSourceManagement()
        navigation.openSupplementalSource("source-a")
        assertTrue(navigation.pageTarget().forward)
        navigation.backPage()
        assertEquals(SettingsDestination.SOURCE_MANAGEMENT, navigation.destination)
        assertFalse(navigation.pageTarget().forward)
    }

    @Test fun exitingCachePageKeepsTheOriginalPlaylistAndScrollIdentity() {
        val navigation = SettingsNavigation()
        navigation.openCachedPlaylist("first")
        val exiting = navigation.pageTarget()
        navigation.backPage()
        navigation.openCachedPlaylist("second")
        assertEquals("first", exiting.cachedPlaylistId)
        assertNotEquals(exiting.stateKey, navigation.pageTarget().stateKey)
        navigation.backPage()
        assertNull(navigation.pageTarget().cachedPlaylistId)
    }

    @Test fun exitingSupplementalSourceDoesNotFollowNewEntryArguments() {
        val navigation = SettingsNavigation()
        navigation.openSupplementalSource("source-a")
        val exiting = navigation.pageTarget()
        navigation.openSupplementalSource("source-b")
        assertEquals("source-a", exiting.supplementalSourceId)
        assertEquals("source-b", navigation.pageTarget().supplementalSourceId)
        assertNotEquals(exiting.supplementalEntryRevision, navigation.pageTarget().supplementalEntryRevision)
    }
}
