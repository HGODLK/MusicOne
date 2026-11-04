package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.*
import org.junit.Test

class LyricViewportGeometryTest {
    @Test fun internalCutIsRejectedButNaturalViewportAndWindowClippingAreAllowed() {
        val text = Rect(30f, 170f, 700f, 370f)
        val viewport = Rect(0f, 0f, 800f, 300f)
        val screen = Rect(0f, 0f, 600f, 1000f)
        val visible = text.intersect(viewport).intersect(screen)
        assertTrue(lyricClipCovers(visible, Rect(30f, 170f, 600f, 300f)))
        assertFalse(lyricClipCovers(visible, Rect(30f, 170f, 600f, 240f)))
        assertFalse(lyricClipCovers(visible, Rect(30f, 200f, 600f, 300f)))
        assertFalse(lyricClipCovers(visible, Rect.Zero))
        assertTrue(lyricClipCovers(visible, Rect(30.5f, 170.5f, 599.5f, 299.5f)))
        assertTrue(lyricClipCovers(Rect.Zero, Rect.Zero))
    }

    @Test fun measuredBaselineIncludesPortraitExtensionWithoutChangingTheParagraph() {
        val portrait = phoneLyricsBaselineFraction(Offset(16f, 310f), Offset(40f, -180f), Size(800f, 1100f))!!
        assertEquals(56f, portrait.x * 800f, .001f)
        assertEquals(130f, portrait.y * 1100f, .001f)
        val landscape = phoneLyricsBaselineFraction(Offset(100f, 190f), Offset.Zero, Size(700f, 500f))!!
        assertEquals(100f, landscape.x * 700f, .001f)
        assertEquals(190f, landscape.y * 500f, .001f)
        assertNull(phoneLyricsBaselineFraction(Offset.Zero, Offset.Zero, Size.Zero))
        assertNull(phoneLyricsBaselineFraction(Offset(Float.NaN, 0f), Offset.Zero, Size(800f, 1100f)))
    }
}
