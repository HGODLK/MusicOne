package com.musicone.demo

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MusicFavoriteRefreshTest {
    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, "歌", "歌手", "专辑", 180000,
        0L, 0L, "", "")

    @Test fun remoteChangesReplaceSettledLocalOverridesButKeepDeferredRemoval() {
        val old = track("qq-old"); val held = track("qq-held")
        val state = MusicFavoriteUiState(ids = setOf(old.id), changes = mapOf(old.id to (old to true)))
            .stageFavoriteRemoval(held)
        val refreshed = state.withRefreshedFavorites(setOf(held.id, "qq-new"), state)
        assertFalse(old.id in refreshed.ids)
        assertFalse(held.id in refreshed.ids)
        assertTrue("qq-new" in refreshed.ids)
        assertEquals(setOf(held.id), refreshed.changes.keys)
        assertEquals(state.deferredRemovalIds, refreshed.deferredRemovalIds)
    }

    @Test fun writeDuringReadCannotBeOverwrittenEvenIfItSettlesBeforeResponse() {
        val song = track("qq-song")
        val started = MusicFavoriteUiState()
        val changed = started.copy(ids = setOf(song.id), changes = mapOf(song.id to (song to true)))
        assertTrue(song.id in changed.withRefreshedFavorites(emptySet(), started).ids)
        val baseline = changed
        // 来回点击后最终值与起点相同，仍需靠操作代次保护。
        assertTrue(song.id in changed.withRefreshedFavorites(emptySet(), baseline, setOf(song.id)).ids)
    }

    @Test fun sharedReadIsCoalescedAndAccountCancellationRejectsOldResult() = runBlocking {
        val refresh = MusicFavoriteRefresh(this)
        val ready = CompletableDeferred<Set<String>>()
        var reads = 0; var result = emptySet<String>()
        refresh.request({ reads++; ready.await() }) { result = it }
        yield()
        refresh.request({ reads++; setOf("duplicate") }) { result = it }
        assertEquals(1, reads)
        refresh.cancel()
        ready.complete(setOf("old-account")); yield()
        assertTrue(result.isEmpty())
        refresh.request({ setOf("new-account") }) { result = it }
        yield(); assertEquals(setOf("new-account"), result)
        refresh.request({ error("网络失败") }) { result = it }
        yield(); assertEquals(setOf("new-account"), result)
    }
}
