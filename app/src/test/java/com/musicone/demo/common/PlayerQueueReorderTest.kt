package com.musicone.demo

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.*
import org.junit.Test

class PlayerQueueReorderTest {
    private val track = MusicTrack("moved", MusicSource.LOCAL, "歌曲", "歌手", "", 180_000, 0, 0, "", "")

    @Test fun reorderWaitsForSourceLayoutToReleaseTheNativeAnimation() {
        var insertions = 0
        val reorder = reorder { insertions++ }
        val exit = exit()
        reorder.exits += exit
        reorder.commit(exit)
        assertEquals(0, insertions)
        assertTrue(reorder.owns(track.id))
        assertFalse(reorder.finish(exit, -66f))
        // 旧布局回调不具备接管标记，不能放行队列更新。
        reorder.positionRow(track.id, 120f, 66, owned = false)
        reorder.commit(exit)
        assertEquals(0, insertions)
        reorder.positionRow(track.id, 120f, 66, owned = true)
        reorder.commit(exit)
        reorder.commit(exit)
        assertEquals(1, insertions)
    }

    @Test fun aPartlyVisibleNativeEndpointCannotReleaseTheHiddenRow() {
        val reorder = reorder()
        val exit = exit()
        reorder.exits += exit
        reorder.positionRow(track.id, 120f, 66, owned = true)
        reorder.commit(exit)
        // 首行滚动偏移为 30px 时，原生退场终点仍可能在顶部露出 30px。
        assertFalse(reorder.finish(exit, -36f))
        assertTrue(reorder.owns(track.id))
        assertTrue(reorder.finish(exit, -66f))
        assertFalse(reorder.owns(track.id))
        reorder.commit(exit)
        assertFalse(reorder.finish(exit, -66f))
    }

    @Test fun dragAndFlingFollowOnlyThePixelsActuallyConsumedByTheList() {
        val reorder = reorder()
        val exit = exit()
        reorder.exits += exit
        reorder.scrollConnection.onPostScroll(Offset(0f, -40f), Offset(0f, -25f), NestedScrollSource.UserInput)
        reorder.scrollConnection.onPostScroll(Offset(0f, -15f), Offset.Zero, NestedScrollSource.SideEffect)
        assertEquals(-55f, reorder.scrollShift(exit), .001f)
        assertEquals(-77f, reorder.destination(exit), .001f)
        reorder.positionRow(track.id, 120f, 66, owned = true)
        reorder.commit(exit)
        // 反向滑动使旧终点回到窗口内，必须续接新目标，不能直接撤层。
        val previousTarget = reorder.destination(exit)
        reorder.scrollConnection.onPostScroll(Offset(0f, 90f), Offset.Zero, NestedScrollSource.UserInput)
        assertFalse(reorder.finish(exit, previousTarget))
        assertTrue(reorder.finish(exit, reorder.destination(exit)))
    }

    @Test fun realDestinationEnteringTheViewportKeepsItsContentCoordinateWhileScrolling() {
        for (height in listOf(280, 680, 1360)) {
            assertEquals(-132f, queueReorderDestination(null, true, height, 66, 0f), 0f)
            assertEquals(height + 66f, queueReorderDestination(null, false, height, 66, 0f), 0f)
            val before = queueReorderDestination(120f, true, height, 66, -40f)
            val after = queueReorderDestination(90f, true, height, 66, -70f)
            assertEquals(before, after, .001f)
            assertEquals(90f, after - 70f, .001f)
        }
    }

    @Test fun scrollingTheSourceAwayDuringPreparationDoesNotLoseOrRepeatTheAction() {
        var insertions = 0
        val reorder = reorder { insertions++ }
        val exit = exit()
        reorder.exits += exit
        reorder.forgetRow(track.id)
        reorder.commit(exit)
        assertFalse(reorder.owns(track.id))
        assertEquals(1, insertions)
    }

    @Test fun sourceDisposalAfterCommitCannotRepeatTheInsertion() {
        var insertions = 0
        val reorder = reorder { insertions++ }
        val exit = exit()
        reorder.exits += exit
        reorder.positionRow(track.id, 120f, 66, owned = true)
        reorder.commit(exit)
        reorder.forgetRow(track.id)
        assertEquals(1, insertions)
        assertTrue(reorder.owns(track.id))
    }

    @Test fun finishingRequiresTheNewQueueToHaveCompletedLayout() {
        val tracks = (0..19).map { if (it == 8) track else track.copy(id = "track-$it") }
        var state = MusicOneUiState(queue = tracks, currentTrack = tracks.first())
        val reorder = PlayerQueueReorder(LazyListState(firstVisibleItemIndex = 8),
            { PlayerQueuePresentation(state.queue, state.currentTrack?.id, true) },
            PlaybackQueueActions({ state = state.withTrackInsertedNext(it) }, {}))
        reorder.positionViewport(0f, 680)
        reorder.layoutQueue(state.queue)
        val exit = exit()
        reorder.exits += exit
        reorder.positionRow(track.id, 120f, 66, owned = true)
        reorder.commit(exit)
        assertFalse(reorder.layoutReady(exit))
        assertFalse(reorder.finish(exit, -66f))
        reorder.layoutQueue(state.queue)
        assertTrue(reorder.layoutReady(exit))
        assertTrue(reorder.finish(exit, -66f))
    }

    @Test fun delegateThatDoesNotChangeTheQueueCannotLeaveTheRowHiddenForever() {
        val tracks = (0..19).map { if (it == 8) track else track.copy(id = "track-$it") }
        val reorder = PlayerQueueReorder(LazyListState(firstVisibleItemIndex = 8),
            { PlayerQueuePresentation(tracks, tracks.first().id, true) }, PlaybackQueueActions({}, {}))
        val exit = exit()
        reorder.exits += exit
        reorder.positionRow(track.id, 120f, 66, owned = true)
        reorder.commit(exit)
        assertFalse(reorder.owns(track.id))
        assertFalse(reorder.layoutReady(exit))
    }

    @Test fun crossingTheViewportEndsTheFlightBeforeItsOffscreenTarget() {
        val reorder = reorder()
        val exit = exit()
        reorder.exits += exit
        reorder.positionRow(track.id, 120f, 66, owned = true)
        reorder.commit(exit)
        assertFalse(reorder.finish(exit, -65f))
        assertTrue(reorder.finish(exit, -66f))
        assertFalse(reorder.owns(track.id))
    }

    @Test fun scrollingAnInFlightRowOutsideTheViewportReleasesItImmediately() {
        val reorder = reorder()
        val exit = exit()
        reorder.exits += exit
        reorder.positionRow(track.id, 120f, 66, owned = true)
        reorder.commit(exit)
        reorder.scrollConnection.onPostScroll(Offset(0f, -200f), Offset.Zero, NestedScrollSource.UserInput)
        assertTrue(reorder.finish(exit, 120f))
        assertFalse(reorder.owns(track.id))
    }

    private fun reorder(insert: () -> Unit = {}): PlayerQueueReorder {
        val tracks = (0..19).map { if (it == 8) track else track.copy(id = "track-$it") }
        var state = MusicOneUiState(queue = tracks, currentTrack = tracks.first())
        lateinit var reorder: PlayerQueueReorder
        reorder = PlayerQueueReorder(LazyListState(firstVisibleItemIndex = 8),
            { PlayerQueuePresentation(state.queue, state.currentTrack?.id, true) },
            PlaybackQueueActions({ selected ->
                insert()
                state = state.withTrackInsertedNext(selected)
                reorder.layoutQueue(state.queue)
            }, {}))
        reorder.positionViewport(0f, 680)
        return reorder
    }

    private fun exit() = PlayerQueueRowExit(track, 8, 120f, 66, true, 0f)
}
