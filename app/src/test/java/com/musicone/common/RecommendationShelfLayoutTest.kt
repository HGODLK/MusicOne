package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationShelfLayoutTest {
    @Test fun phoneAndTabletEdgeExtensionPreservesOriginalCardSizes() {
        val phone = recommendationShelfCardLayout(390f, 20f)
        assertTrue(phone.compact)
        assertEquals(259f, phone.widthDp, .01f)
        assertTrue(phone.heightDp < 320f)
        val portraitTablet = recommendationShelfCardLayout(792f, 32f)
        assertFalse(portraitTablet.compact)
        assertEquals(260f, portraitTablet.widthDp, .01f)
        val landscapeTablet = recommendationShelfCardLayout(1048f, 32f)
        assertFalse(landscapeTablet.compact)
        assertEquals(300f, landscapeTablet.widthDp, .01f)
    }

    @Test fun viewportExtensionDoesNotPromoteCompactContentAcrossTabletBreakpoint() {
        val layout = recommendationShelfCardLayout(640f, 32f)
        assertTrue(layout.compact)
        assertEquals(recommendationCardLayout(576f), layout)
    }

    @Test fun legacyViewportKeepsOriginalCardSizeWithoutEdgeExtension() {
        assertEquals(recommendationCardLayout(350f), recommendationShelfCardLayout(350f, 0f))
        assertEquals(recommendationCardLayout(728f), recommendationShelfCardLayout(728f, 0f))
    }
}
