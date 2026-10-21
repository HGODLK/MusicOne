package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaylistPlaybackPreparationTest {
    private val track = MusicTrack("qq-loaded", MusicSource.QQ, "歌曲", "歌手", "", 180_000, 0, 0, "", "")
    private val snapshot = MusicPlaylist("qq-list", MusicSource.QQ, "歌单", "", "", 1_462,
        0, 0, "", listOf(track))

    @Test fun loadedTrackRemainsPlayableButSelectionStillFailsAndCanRetry() = runBlocking {
        val loader = PlaylistPagingController { _, cursor ->
            when (cursor?.offset) {
                null -> PlaylistTrackPage(snapshot, PlaylistTrackCursor(100))
                100 -> PlaylistTrackPage(snapshot.copy(tracks = listOf(track.copy(id = "qq-next"))),
                    PlaylistTrackCursor(200))
                else -> error("歌曲分页返回了重复内容，请重试")
            }
        }
        loader.first(snapshot)
        val loaded = completePlaylistForSelectedTrack({ loader.entry(snapshot).page!!.playlist }, track) {
            loader.complete(snapshot)
        }
        assertEquals(listOf(track, track.copy(id = "qq-next")), loaded.tracks)
        assertEquals(1_462, loaded.count)
        assertEquals(200, loader.entry(snapshot).page?.next?.offset)
        assertNotNull(loader.entry(snapshot).error)
        assertTrue(runCatching { loader.complete(snapshot) }.isFailure)
    }

    @Test fun wholePlaylistPlaybackAndUnknownTrackDoNotUsePartialResults() = runBlocking {
        for (selected in listOf(null, track.copy(id = "qq-not-loaded"))) {
            assertTrue(runCatching {
                completePlaylistForSelectedTrack({ snapshot }, selected) { error("加载失败") }
            }.isFailure)
        }
    }

    @Test fun successfulCompletionUsesAllTracksAndCancellationNeverStartsPlayback() = runBlocking {
        val full = snapshot.copy(tracks = listOf(track, track.copy(id = "qq-next")))
        assertEquals(full, completePlaylistForSelectedTrack({ snapshot }, track) { full })
        val cancelled = runCatching {
            completePlaylistForSelectedTrack({ snapshot }, track) { throw CancellationException("已切换账号") }
        }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
    }

    @Test fun aNetworkFailureAfterAccountInvalidationCannotFallbackToTheOldSnapshot() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loader = PlaylistPagingController { _, cursor ->
            if (cursor == null) PlaylistTrackPage(snapshot, PlaylistTrackCursor(100)) else {
                started.complete(Unit)
                release.await()
                error("旧账号请求失败")
            }
        }
        loader.first(snapshot)
        val entry = loader.entry(snapshot)
        val pending = async {
            runCatching {
                completePlaylistForSelectedTrack({
                    loader.checkCurrent(snapshot, entry)
                    entry.page!!.playlist
                }, track) { loader.complete(snapshot) }
            }
        }
        started.await()
        loader.clear()
        release.complete(Unit)
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
    }
}
