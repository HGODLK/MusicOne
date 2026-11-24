package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistCardSelectionTest {
    @Test
    fun gridSlotsContinueRowsAcrossSectionsWithHeadingGap() {
        val slots = playlistGridSlots(listOf(listOf("a", "b", "c"), emptyList(), listOf("d")), columns = 2)
        assertEquals(PlaylistGridSlot(0, 0), slots["a"])
        assertEquals(PlaylistGridSlot(0, 1), slots["b"])
        assertEquals(PlaylistGridSlot(1, 0), slots["c"])
        // 第一分区占两行，第二个非空分区的标题再占一行。
        assertEquals(PlaylistGridSlot(3, 0), slots["d"])
    }

    @Test
    fun revealDelayGrowsByRingAndIsCapped() {
        val origin = PlaylistGridSlot(2, 1)
        assertEquals(0, playlistSelectionRevealDelay(origin, origin))
        assertEquals(25, playlistSelectionRevealDelay(PlaylistGridSlot(3, 0), origin))
        assertEquals(50, playlistSelectionRevealDelay(PlaylistGridSlot(0, 1), origin))
        assertEquals(250, playlistSelectionRevealDelay(PlaylistGridSlot(40, 0), origin))
        assertEquals(0, playlistSelectionRevealDelay(null, origin))
    }

    @Test
    fun beginWithoutOriginStartsFromFirstCard() {
        val selection = PlaylistCardSelection()
        selection.slots = playlistGridSlots(listOf(listOf("a", "b", "c", "d")), columns = 2)
        selection.begin(null)
        assertEquals(PlaylistGridSlot(0, 0), selection.origin)
        assertTrue(selection.ids.isEmpty())
        assertEquals(25, selection.revealDelay("d"))
    }

    @Test
    fun leavingCardsArePrunedOnceTheyLeaveTheList() {
        val selection = PlaylistCardSelection()
        selection.markLeaving(listOf("a", "b"))
        selection.retain(listOf("b", "c"))
        assertEquals(setOf("b"), selection.leaving)
    }
}
