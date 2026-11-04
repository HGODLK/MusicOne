package com.musicone

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class MenuArtistArtworkClipTest {
    @Test fun avatarOutsideTheExpandingMenuDoesNotDraw() {
        val avatar = Rect(20f, 300f, 52f, 332f)
        assertEquals(Rect.Zero, menuArtistLocalClip(avatar, Rect(20f, 200f, 52f, 250f)))
        assertEquals(Rect.Zero, menuArtistLocalClip(avatar, Rect.Zero))
    }

    @Test fun expandingEdgeRevealsPixelsWithoutResizingTheAvatar() {
        val avatar = Rect(20f, 300f, 52f, 332f)
        for (visibleHeight in listOf(1f, 8f, 16f, 31f, 32f)) {
            val clip = menuArtistLocalClip(avatar, Rect(20f, 300f, 52f, 300f + visibleHeight))
            assertEquals(Rect(0f, 0f, 32f, visibleHeight), clip)
            assertEquals(32f, avatar.width, 0f)
            assertEquals(32f, avatar.height, 0f)
        }
    }

    @Test fun landscapeSideEdgesAndScrolledTopEdgeUsePhysicalAvatarCoordinates() {
        val avatar = Rect(600f, 20f, 632f, 52f)
        assertEquals(Rect(8f, 0f, 32f, 32f), menuArtistLocalClip(avatar, Rect(608f, 0f, 800f, 100f)))
        assertEquals(Rect(0f, 0f, 24f, 32f), menuArtistLocalClip(avatar, Rect(0f, 0f, 624f, 100f)))
        assertEquals(Rect(0f, 12f, 32f, 32f), menuArtistLocalClip(avatar, Rect(0f, 32f, 800f, 100f)))
    }

    @Test fun fullyVisibleAvatarKeepsItsFullDrawingArea() {
        val avatar = Rect(100f, 60f, 132f, 92f)
        assertEquals(Rect(0f, 0f, 32f, 32f), menuArtistLocalClip(avatar, Rect(0f, 0f, 1200f, 900f)))
    }
}
