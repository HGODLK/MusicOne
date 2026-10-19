package com.musicone.demo

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreloadedPlaybackHandoffTest {
    @Test fun nextHiResStartsFromPreloadWhileDownloadAndMetadataAreStillPending() = runBlocking {
        val song = MusicTrack("next", MusicSource.NETEASE, "下一首", "歌手", "专辑",
            240_000L, 0, 0, "next", "")
        val resolved = ResolvedPlayback(song.copy(previewUrl = "https://example.com/hires.flac"),
            PlaybackSource("https://example.com/hires.flac", AudioQuality.HI_RES, AudioQuality.HI_RES,
                4_600_000, "flac", false))
        val entries = PlaybackPreloadEntries<String, ResolvedPlayback>(::preloadedPlaybackFresh, ::preloadedPlaybackRefreshDue)
        entries.work(song.id, 0L)
        entries.resolved(song.id, resolved, 0L)
        // 片段下载还未完成，取源结果已可交付，后补元数据也不能挡住起播。
        val download = launch { awaitCancellation() }
        val key = PlaybackRequestKey(song.id, 1L)
        val ready = CompletableDeferred<PlaybackTrackEvent.Ready>()
        var onlineRequests = 0
        val loader = PlaybackTrackLoader(this,
            { track, _ -> entries.take(track.id, 1_000L) },
            { _, _, _ -> onlineRequests++; error("预加载命中后不应重新联网取源") },
            { awaitCancellation() }, { it == key }, { true },
            { if (it is PlaybackTrackEvent.Ready) ready.complete(it) })
        try {
            loader.start(song, key, AudioQuality.HI_RES, AudioQuality.HI_RES, 0L, true, false)
            val event = withTimeout(1_000L) { ready.await() }
            assertEquals(resolved, event.playback)
            assertEquals(0, onlineRequests)
            assertTrue(event.playWhenReady)
            assertTrue(download.isActive)
        } finally {
            loader.cancel()
            download.cancelAndJoin()
        }
    }
}
