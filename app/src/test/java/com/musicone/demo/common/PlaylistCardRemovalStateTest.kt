package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class PlaylistCardRemovalStateTest {
    private val cards = (0..4).map { id ->
        MusicPlaylist("local-$id", MusicSource.LOCAL, "歌单$id", "", "", 0, 0L, 0L, "", emptyList())
    }

    @Test fun databaseDeletionKeepsTheSlotUntilTheEditorHasFinishedClosing() {
        val state = PlaylistCardRemovalState()
        state.hold(cards[1], 1)
        val updated = cards.filterNot { it.id == cards[1].id }
        assertEquals(cards, state.present(updated))
        state.confirmDelete(cards[1].id)
        assertTrue(state.hidden(cards[1].id))
        assertEquals(cards, state.present(updated))
        assertEquals(cards[1].id, state.finish())
        assertEquals(updated, state.present(updated))
        assertTrue(state.hidden(cards[1].id))
        assertEquals(updated, state.present(cards))
    }

    @Test fun cancellingOrRenamingDoesNotRemoveTheCardOrDiscardOtherUpdates() {
        val state = PlaylistCardRemovalState()
        state.hold(cards[2], 2)
        val updated = cards.map { if (it.id == cards[0].id) it.copy(title = "已更新") else it }
        assertEquals(updated, state.present(updated))
        assertNull(state.finish())
        assertFalse(state.hidden(cards[2].id))
        assertEquals(updated, state.present(updated))
    }

    @Test fun successiveDeletesHaveIndependentRetainedSlots() {
        val state = PlaylistCardRemovalState()
        state.hold(cards[4], 4)
        state.confirmDelete(cards[4].id)
        state.finish()
        val four = state.present(cards)
        state.hold(cards[1], 1)
        state.confirmDelete(cards[1].id)
        val three = cards.filterNot { it.id in setOf(cards[1].id, cards[4].id) }
        assertEquals(four, state.present(three))
        state.finish()
        assertEquals(three, state.present(cards))
    }

    @Test fun heightReservationChangesOnlyWhenTheLastGridRowDisappears() {
        assertEquals(3, playlistGridRows(5, 2))
        assertEquals(2, playlistGridRows(4, 2))
        assertEquals(2, playlistGridRows(3, 2))
        assertEquals(1, playlistGridRows(3, 3))
        assertEquals(0, playlistGridRows(0, 4))
    }
}
