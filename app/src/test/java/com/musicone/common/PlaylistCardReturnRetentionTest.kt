package com.musicone

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaylistCardReturnRetentionTest {
    private val cards = (0..3).map {
        MusicPlaylist("card-$it", MusicSource.QQ, "歌单$it", "", "", 0, 0L, 0L, "", emptyList())
    }
    private val prefix = "qq:my-library:false:"
    private val sourceKey = "$prefix${cards[1].id}"

    @Test fun removedCardSurvivesExitAndInformationFadeThenLeavesTheGrid() = runBlocking {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(coroutineContext + clock)
        val transition = PlaylistCardTransition(scope)
        val motion = PageMotion(scope, false, 320, 280)
        motion.waitForTarget = false
        val navigation = RecommendationNavigation(mutableStateOf(null), motion, transition)
        var current = cards
        var presented = cards
        var time = 0L
        suspend fun frames(count: Int) {
            repeat(count) {
                yield()
                time += 16_666_667L
                clock.sendFrame(time)
                yield()
                presented = retainReturningPlaylist(current, presented, transition.activeSourceKey, prefix)
                if (transition.activeSourceKey != null) assertEquals(cards, presented)
            }
        }
        transition.open(sourceKey, null, {}) { navigation.open(cards[1]) }
        frames(80)
        assertEquals(MotionPhase.SHOWN, motion.phase)
        current = cards.filterNot { it.id == cards[1].id }
        frames(2)
        assertEquals(cards, presented)
        navigation.close()
        frames(20)
        assertEquals(MotionPhase.HIDDEN, motion.phase)
        assertTrue(transition.restoring)
        assertEquals(cards, presented)
        frames(60)
        assertNull(transition.activeSourceKey)
        assertEquals(current, presented)
        coroutineContext.cancelChildren()
    }

    @Test fun restoringCollectionBeforeReturnKeepsOneCardAndItsOriginalSlot() {
        val removed = cards.filterNot { it.id == cards[1].id }
        val held = retainReturningPlaylist(removed, cards, sourceKey, prefix)
        val restored = listOf(cards[1].copy(title = "重新收藏")) + removed
        val presented = retainReturningPlaylist(restored, held, sourceKey, prefix)
        assertEquals(cards.map { it.id }, presented.map { it.id })
        assertEquals("重新收藏", presented[1].title)
        assertEquals(restored, retainReturningPlaylist(restored, presented, null, prefix))
    }

    @Test fun failedRemovalAndOtherCardUpdatesAreNotFrozen() {
        val updated = cards.map { if (it.id == cards[2].id) it.copy(title = "新标题") else it }
        assertEquals(updated, retainReturningPlaylist(updated, cards, sourceKey, prefix))
        assertEquals(updated, retainReturningPlaylist(updated, updated, null, prefix))
    }

    @Test fun OtherEntrancesDoNotRetainAnUnrelatedCollectionCard() {
        val removed = cards.drop(1)
        assertEquals(removed, retainReturningPlaylist(removed, cards, "search:${cards[0].id}", prefix))
        assertEquals(removed, retainReturningPlaylist(removed, emptyList(), sourceKey, prefix))
    }

    @Test fun onlyTabletPagesWithTheCollectionButtonReserveItsFullHeight() {
        assertEquals(72.dp, playlistCollectionTopInset(tablet = true, showCollection = true))
        assertEquals(0.dp, playlistCollectionTopInset(tablet = true, showCollection = false))
        assertEquals(0.dp, playlistCollectionTopInset(tablet = false, showCollection = true))
        assertEquals(0.dp, playlistCollectionTopInset(tablet = false, showCollection = false))
    }
}
