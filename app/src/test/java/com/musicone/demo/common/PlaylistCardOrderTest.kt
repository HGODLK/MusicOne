package com.musicone.demo

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistCardOrderTest {
    @Test fun savedOrderSurvivesServerListRefresh() {
        assertEquals(listOf("c", "a", "b"), reconcilePlaylistCardOrder(listOf("c", "a", "b"), listOf("a", "b", "c")))
    }

    @Test fun newCardsAppendWithoutMovingExistingCards() {
        assertEquals(listOf("b", "a", "c"), reconcilePlaylistCardOrder(listOf("b", "a"), listOf("a", "b", "c")))
    }

    @Test fun removedCardsAndDuplicateSavedIdsDoNotReappear() {
        assertEquals(listOf("b", "a"), reconcilePlaylistCardOrder(listOf("gone", "b", "b", "a"), listOf("a", "b")))
    }

    @Test fun emptyOrderUsesCurrentCardOrder() {
        assertEquals(listOf("b", "a"), reconcilePlaylistCardOrder(emptyList(), listOf("b", "a")))
    }

    @Test fun emptyLibraryDropsAllSavedCards() {
        assertEquals(emptyList<String>(), reconcilePlaylistCardOrder(listOf("a", "b"), emptyList()))
    }
}
