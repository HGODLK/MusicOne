package com.musicone.demo

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OfflinePlaylistPlaybackTest {
    private val cached = MusicTrack("qq-cached", MusicSource.QQ, "歌曲", "歌手", "",
        180_000, 0, 0, "", "")
    private val partial = cached.copy(id = "qq-partial")
    private val playlist = MusicPlaylist("qq-list", MusicSource.QQ, "歌单", "", "", 500,
        0, 0, "", listOf(partial, cached))

    @Test fun offlinePlaybackUsesVisibleCachedSongsWithoutCompletingNetworkPages() = runBlocking {
        val snapshot = playlistForPlayback(playlist, false, { setOf(cached.id) },
            { error("离线播放不能请求歌单分页") })
        assertEquals(listOf(cached), snapshot.tracks)
        assertEquals(1, snapshot.count)
    }

    @Test fun onlinePlaybackStillCompletesPlaylistAndDoesNotScanOfflineIndex() = runBlocking {
        val full = playlist.copy(tracks = listOf(cached, partial), count = 2)
        assertEquals(full, playlistForPlayback(playlist, true,
            { error("在线播放无需扫描离线索引") }, { full }))
    }

    @Test fun offlineMixedPlaylistKeepsReadableLocalFilesAndRejectsMissingFiles() = runBlocking {
        val local = cached.copy(id = "local", source = MusicSource.LOCAL)
        val missing = local.copy(id = "missing", playable = false)
        val mixed = playlist.copy(tracks = listOf(local, partial, missing, cached))
        assertEquals(listOf(local, cached), playlistForPlayback(mixed, false, { setOf(cached.id) },
            { error("不应请求网络") }).tracks)
        assertTrue(runCatching {
            playlistForPlayback(playlist, false, { emptySet() }, { error("不应请求网络") })
        }.isFailure)
    }
}
