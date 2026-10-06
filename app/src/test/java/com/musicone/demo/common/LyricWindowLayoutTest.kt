package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class LyricWindowLayoutTest {
    @Test fun eightyRapidNextRequestsDoNotQueueEightyScreens() = checkBoundedPending(TrackTransitionDirection.NEXT)
    @Test fun eightyRapidPreviousRequestsDoNotQueueEightyScreens() = checkBoundedPending(TrackTransitionDirection.PREVIOUS)

    @Test fun wrappingQueueCannotReturnToAnOldWindowInTheWrongDirection() {
        assertFalse(canReturnLyricWindow(0f, 2f, -.4f, TrackTransitionDirection.NEXT))
        assertFalse(canReturnLyricWindow(0f, -2f, .4f, TrackTransitionDirection.PREVIOUS))
    }

    @Test fun deliberateReversalCanReuseTheVisibleOriginalPage() {
        assertTrue(canReturnLyricWindow(0f, 1f, -.4f, TrackTransitionDirection.PREVIOUS))
        assertTrue(canReturnLyricWindow(0f, -1f, .4f, TrackTransitionDirection.NEXT))
        assertFalse(canReturnLyricWindow(0f, 2f, -1.4f, TrackTransitionDirection.PREVIOUS))
    }

    @Test fun tabletLyricsLeaveThePhysicalScreenWithUnequalSystemInsets() {
        checkScreenEdges(top = 32f, height = 900f, screenHeight = 980f)
        checkScreenEdges(top = 48f, height = 1500f, screenHeight = 1600f)
    }

    @Test fun phoneLyricsAlsoLeaveTheScreenBelowTheHeader() {
        checkScreenEdges(top = 160f, height = 600f, screenHeight = 840f)
    }

    @Test fun fullScreenViewportDoesNotGainAnExtraGap() {
        assertEquals(1000f, lyricWindowTravelPx(0f, 1000f, 1000f), 0f)
    }

    @Test fun visibleButUnalignedLyricsDoNotReleaseTheIncomingWindow() {
        assertFalse(lyricWindowAligned(45, false, 0f))
        assertFalse(lyricWindowAligned(0, true, 0f))
        assertFalse(lyricWindowAligned(0, false, 600f))
        assertFalse(lyricWindowAligned(null, false, 0f))
        assertTrue(lyricWindowAligned(0, false, 0f))
    }

    private fun checkBoundedPending(direction: TrackTransitionDirection) {
        val sign = if (direction == TrackTransitionDirection.NEXT) 1f else -1f
        var slot = 0f
        var offset = 0f
        var replaced = 0
        repeat(80) {
            val next = nextLyricWindowSlot(slot, offset, direction)
            if (next == slot) replaced++
            slot = next
            assertTrue("连点不能积累屏外页面", (slot + offset) * sign <= 2f)
            offset -= .08f * sign
        }
        assertTrue(replaced > 60)
    }

    private fun checkScreenEdges(top: Float, height: Float, screenHeight: Float) {
        val travel = lyricWindowTravelPx(top, height, screenHeight)
        assertTrue(top + height - travel <= 0f)
        assertTrue(top + travel >= screenHeight)
        // 仅移动歌词栏高度时仍在屏幕内的尾部，不能作为撤层终点。
        assertTrue(top + height - height > 0f)
    }
}
