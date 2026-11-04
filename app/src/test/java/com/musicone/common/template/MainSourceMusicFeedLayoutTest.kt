package com.musicone

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class MainSourceMusicFeedLayoutTest {
    @Test fun artworkSizesCoverVisiblePixelsOnPhonesAndTablets() {
        val phone = mainSourceFeedArtworkSizes(375.dp, 800.dp, 3f)
        assertEquals(512, phone.card)
        assertEquals(192, phone.thumbnail)
        val tablet = mainSourceFeedArtworkSizes(1280.dp, 800.dp, 2f)
        assertEquals(768, tablet.card)
        assertEquals(128, tablet.thumbnail)
        assertTrue(tablet.thumbnail < tablet.card)
    }

    @Test fun batchTracksViewportInsteadOfFixedCardCount() {
        val phone = mainSourceHomeBatchSize(375.dp, 700.dp)
        val shortTablet = mainSourceHomeBatchSize(1280.dp, 520.dp)
        val tallTablet = mainSourceHomeBatchSize(1280.dp, 900.dp)
        assertTrue(phone in 10..16)
        assertTrue(shortTablet in 6..12)
        assertTrue(tallTablet > shortTablet)
    }

    @Test fun miniPlayerContrastHysteresisAvoidsFlickeringAroundThreshold() {
        assertTrue(miniPlayerUsesLightInk(.08f, false))
        assertFalse(miniPlayerUsesLightInk(.8f, true))
        assertTrue(miniPlayerUsesLightInk(.18f, true))
        assertFalse(miniPlayerUsesLightInk(.18f, false))
    }

}
