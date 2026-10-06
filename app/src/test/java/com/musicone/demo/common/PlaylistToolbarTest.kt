package com.musicone.demo

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistToolbarTest {
    @Test
    fun playlistContentKeepsAHiddenPrewarmLayer() {
        assertEquals(.001f, playlistContentAlpha(0f))
        assertEquals(.5f, playlistContentAlpha(.5f))
        assertEquals(1f, playlistContentAlpha(2f))
    }

    @Test
    fun topButtonsSlideSymmetricallyFromOutsideTheScreen() {
        assertEquals(-76f, playlistBackButtonOffset(0f, 76f))
        assertEquals(76f, playlistForwardButtonOffset(0f, 76f))
        assertEquals(-38f, playlistBackButtonOffset(.5f, 76f))
        assertEquals(38f, playlistForwardButtonOffset(.5f, 76f))
        assertEquals(0f, playlistBackButtonOffset(1f, 76f))
        assertEquals(0f, playlistForwardButtonOffset(1f, 76f))
    }

    @Test
    fun movingGlassKeepsSamplingAnchoredAtItsFinalPosition() {
        assertEquals(12f, backdropSampleTranslation(
            backdropStart = 0f,
            surfaceStart = 20f,
            sampleInset = 32f,
        ))
    }

    @Test
    fun floatingToolsTravelFullyBeyondTheRightEdge() {
        assertEquals(256f, playlistFloatingToolsOffset(0f, 236f, 20f))
        assertEquals(128f, playlistFloatingToolsOffset(.5f, 236f, 20f))
        assertEquals(0f, playlistFloatingToolsOffset(1f, 236f, 20f))
    }

    @Test
    fun playlistSearchContentWaitsForTheContainerAndThenFadesIn() {
        assertEquals(0f, playlistSearchContentAlpha(0f))
        assertEquals(0f, playlistSearchContentAlpha(.18f))
        assertEquals(.5f, playlistSearchContentAlpha(.59f), .0001f)
        assertEquals(1f, playlistSearchContentAlpha(1f))
    }

    @Test
    fun playlistSearchStaysAboveTheKeyboardAndKeepsItsEdgeGap() {
        assertEquals(320.dp, playlistFloatingToolsBottomPadding(96.dp, 300.dp, 20.dp))
        assertEquals(116.dp, playlistFloatingToolsBottomPadding(96.dp, 0.dp, 20.dp))
    }
}
