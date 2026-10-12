package com.musicone.demo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaybackPendingSeekTest {
    @Test fun seekingWhileTheNextSourceLoadsOverridesItsCapturedZeroPosition() = runBlocking {
        val key = PlaybackRequestKey("next", 2)
        val source = CompletableDeferred<ResolvedPlayback>()
        val entered = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Long>()
        val pending = PlaybackPendingSeek()
        val song = MusicTrack("next", MusicSource.QQ, "曲", "歌手", "", 240_000, 0, 0, "", "")
        val loader = PlaybackTrackLoader(this, { _, _ -> null }, { _, _, _ ->
            entered.complete(Unit); source.await()
        }, { it }, { it == key }, { true }, { event ->
            if (event is PlaybackTrackEvent.Ready) ready.complete(pending.take(event.key) ?: event.positionMs)
        })
        loader.start(song, key, AudioQuality.LOSSLESS, AudioQuality.LOSSLESS, 0L, true, false)
        entered.await()
        pending.request(key, 60_000)
        pending.request(key, 120_000)
        source.complete(ResolvedPlayback(song, PlaybackSource("offline://next", AudioQuality.LOSSLESS,
            AudioQuality.LOSSLESS, 0, "", false)))
        assertEquals(120_000L, ready.await())
        assertNull(pending.take(key))
        loader.cancel()
    }

    @Test fun lateOldRequestsCannotConsumeTheNewTracksSeek() {
        val pending = PlaybackPendingSeek()
        val old = PlaybackRequestKey("song", 1)
        val current = PlaybackRequestKey("song", 2)
        pending.request(current, 90_000)
        assertNull(pending.take(old))
        assertEquals(90_000L, pending.take(current))
        pending.request(old, -10)
        assertEquals(0L, pending.take(old))
    }

    @Test fun changingTracksDropsThePreviousPendingPosition() {
        val pending = PlaybackPendingSeek()
        pending.request(PlaybackRequestKey("first", 1), 30_000)
        pending.clear()
        assertNull(pending.take(PlaybackRequestKey("first", 1)))
        assertNull(pending.take(PlaybackRequestKey("second", 2)))
    }
}
