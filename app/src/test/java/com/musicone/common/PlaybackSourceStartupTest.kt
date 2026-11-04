package com.musicone

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackSourceStartupTest {
    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, "歌曲", "歌手", "专辑",
        240_000, 0, 0, "曲", "https://example.com/$id.flac")

    private fun resolved(track: MusicTrack) = ResolvedPlayback(track,
        PlaybackSource(track.previewUrl, AudioQuality.LOSSLESS, AudioQuality.LOSSLESS, 0, "", false))

    @Test fun qualityIsSelectedAfterStartupForBothCacheAndResolution() = runBlocking {
        for (cached in listOf(false, true)) {
            val startup = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val ready = CompletableDeferred<PlaybackTrackEvent.Ready>()
            val song = track("quality")
            val key = PlaybackRequestKey(song.id, 1)
            var selected = AudioQuality.STANDARD to AudioQuality.STANDARD
            var selections = 0
            var cacheQuality: AudioQuality? = null
            var resolveQuality: AudioQuality? = null
            val loader = PlaybackTrackLoader(this, { _, quality ->
                cacheQuality = quality
                if (cached) resolved(song) else null
            }, { _, quality, _ -> resolveQuality = quality; resolved(song) },
                { it }, { it == key }, { false }, { if (it is PlaybackTrackEvent.Ready) ready.complete(it) },
                awaitSource = { entered.complete(Unit); startup.await() },
                selectQuality = { selections++; selected })
            try {
                loader.start(song, key, AudioQuality.STANDARD, AudioQuality.STANDARD, 0L, false, false)
                entered.await()
                assertEquals(0, selections)
                selected = AudioQuality.HI_RES to AudioQuality.LOSSLESS
                startup.complete(Unit)
                val event = withTimeout(3_000) { ready.await() }
                assertEquals(1, selections)
                assertEquals(AudioQuality.LOSSLESS, cacheQuality)
                assertEquals(if (cached) null else AudioQuality.LOSSLESS, resolveQuality)
                assertEquals(AudioQuality.HI_RES, event.preferred)
                assertEquals(AudioQuality.LOSSLESS, event.effective)
            } finally { loader.cancel() }
        }
    }

    @Test fun cancellationAndSwitchDuringStartupCannotStartTheOldTrack() = runBlocking {
        for (switch in listOf(false, true)) {
            val startup = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val events = mutableListOf<PlaybackTrackEvent>()
            val resolutions = mutableListOf<String>()
            var current = PlaybackRequestKey("old", 1)
            val loader = PlaybackTrackLoader(this, { _, _ -> null }, { song, _, _ ->
                resolutions += song.id
                resolved(song)
            }, { it }, { it == current }, { false }, events::add,
                awaitSource = {
                    // 模拟取消后仍会返回的共享初始化，旧代次仍不得继续解析。
                    withContext(NonCancellable) { entered.complete(Unit); startup.await() }
                })
            try {
                loader.start(track("old"), current, AudioQuality.LOSSLESS, AudioQuality.LOSSLESS,
                    0L, false, false)
                entered.await()
                assertTrue(resolutions.isEmpty())
                assertTrue(events.isEmpty())
                if (switch) {
                    current = PlaybackRequestKey("new", 2)
                    loader.start(track("new"), current, AudioQuality.LOSSLESS, AudioQuality.LOSSLESS,
                        0L, false, false)
                } else loader.cancel()
                startup.complete(Unit)
                repeat(4) { yield() }
                assertFalse(resolutions.contains("old"))
                assertTrue(events.none { it.key.trackId == "old" })
                if (switch) {
                    assertEquals(listOf("new"), resolutions)
                    assertEquals("new", events.filterIsInstance<PlaybackTrackEvent.Ready>().single().key.trackId)
                } else assertTrue(events.isEmpty())
            } finally { loader.cancel() }
        }
    }

    @Test fun sourceAvailabilityIsCheckedAfterStartupBeforeUsingAnyCache() = runBlocking {
        for (finalStatus in listOf(QueueSourceStatus.AVAILABLE, QueueSourceStatus.MISSING,
            QueueSourceStatus.UNAVAILABLE)) {
            val startup = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val completed = CompletableDeferred<Unit>()
            val song = track("song")
            val key = PlaybackRequestKey(song.id, 1)
            val events = mutableListOf<PlaybackTrackEvent>()
            var status = QueueSourceStatus.INITIALIZING
            var checks = 0
            var cacheReads = 0
            var networkReads = 0
            val loader = PlaybackTrackLoader(this, { _, _ -> cacheReads++; resolved(song) },
                { _, _, _ -> networkReads++; resolved(song) }, { it }, { it == key }, { false }, {
                    events += it
                    completed.complete(Unit)
                }, sourceStatus = { checks++; status }, awaitSource = { entered.complete(Unit); startup.await() })
            try {
                loader.start(song, key, AudioQuality.LOSSLESS, AudioQuality.LOSSLESS, 0L, false, false)
                entered.await()
                assertEquals(0, checks)
                assertEquals(0, cacheReads)
                assertTrue(events.isEmpty())
                status = finalStatus
                startup.complete(Unit)
                withTimeout(3_000) { completed.await() }
                assertEquals(1, checks)
                assertEquals(0, networkReads)
                if (finalStatus == QueueSourceStatus.AVAILABLE) {
                    assertEquals(1, cacheReads)
                    assertTrue(events.single() is PlaybackTrackEvent.Ready)
                } else {
                    assertEquals(0, cacheReads)
                    assertTrue(events.single() is PlaybackTrackEvent.Failed)
                }
            } finally { loader.cancel() }
        }
    }
}
