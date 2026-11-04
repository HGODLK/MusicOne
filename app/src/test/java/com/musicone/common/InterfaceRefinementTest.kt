package com.musicone

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InterfaceRefinementTest {
    @Test fun libraryUsesPhoneAndTabletColumns() {
        assertEquals(2, mainSourceLibraryColumns(375.dp))
        assertEquals(3, mainSourceLibraryColumns(800.dp))
        assertEquals(4, mainSourceLibraryColumns(1280.dp))
    }
    @Test fun inkChoosesContrastAndIgnoresOneBrightOutlier() {
        assertTrue(miniPlayerRegionUsesLightInk(List(24) { .07f }, false))
        assertFalse(miniPlayerRegionUsesLightInk(List(24) { .8f }, true))
        assertTrue(miniPlayerRegionUsesLightInk(List(23) { .07f } + .95f, false))
    }
    @Test fun inkRetainsPreviousChoiceNearThreshold() {
        val samples = List(24) { .179f }
        assertTrue(miniPlayerRegionUsesLightInk(samples, true))
        assertFalse(miniPlayerRegionUsesLightInk(samples, false))
    }
    @Test fun pageTouchAndSettleGateColorSampling() {
        val activity = PageInteractionActivity()
        activity.lastTouchEvent = 1000L
        assertFalse(activity.quiet(1030L))
        assertTrue(activity.quiet(1060L))
        activity.pressed = true
        assertFalse(activity.quiet(5000L))
    }
    @Test fun pageFlingKeepsSamplingPausedAfterTouchSettles() {
        val activity = PageInteractionActivity()
        val grid = Any()
        var scrolling = true
        var visible = true
        activity.lastTouchEvent = 1000L
        activity.bindScroll(grid) { visible && scrolling }
        assertFalse(activity.quiet(5000L))
        scrolling = false
        assertTrue(activity.quiet(5000L))
        scrolling = true
        visible = false
        assertTrue(activity.quiet(5000L))
        visible = true
        assertFalse(activity.quiet(5000L))
        activity.unbindScroll(grid)
        assertTrue(activity.quiet(5000L))
    }
    @Test fun removingOnePageDoesNotResumeSamplingWhileAnotherScrolls() {
        val activity = PageInteractionActivity()
        val first = Any()
        val second = Any()
        activity.bindScroll(first) { true }
        activity.bindScroll(second) { true }
        activity.unbindScroll(first)
        assertFalse(activity.quiet(5000L))
        activity.unbindScroll(second)
        assertTrue(activity.quiet(5000L))
    }
}
