package com.musicone.source.kugou

import org.junit.Assert.assertEquals
import org.junit.Test

class KugouPageNavigationTest {
    @Test fun switchingRootUsesTheDestinationOnTheFirstFrame() {
        assertEquals(MusicOnePage.MY, kugouVisiblePage("my", true, MusicOnePage.HOME, MusicOnePage.HOME))
        assertEquals(MusicOnePage.HOME, kugouVisiblePage("home", true, MusicOnePage.MY, MusicOnePage.MY))
        assertEquals(MusicOnePage.MY, kugouVisiblePage("mine", true, MusicOnePage.SEARCH, MusicOnePage.HOME))
    }
    @Test fun inactiveRootNeverShowsTheActiveRootsDetailOrSearch() {
        assertEquals(MusicOnePage.HOME, kugouVisiblePage("home", false, MusicOnePage.PLAYLIST, MusicOnePage.MY))
        assertEquals(MusicOnePage.MY, kugouVisiblePage("my", false, MusicOnePage.SEARCH, MusicOnePage.HOME))
    }
    @Test fun activeRootKeepsItsInternalNavigation() {
        assertEquals(MusicOnePage.SEARCH, kugouVisiblePage("home", true, MusicOnePage.SEARCH, MusicOnePage.HOME))
        assertEquals(MusicOnePage.PLAYLIST, kugouVisiblePage("my", true, MusicOnePage.PLAYLIST, MusicOnePage.MY))
    }
}
