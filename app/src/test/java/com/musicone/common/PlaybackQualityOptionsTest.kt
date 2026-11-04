package com.musicone

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackQualityOptionsTest {
    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, "歌曲", "歌手", "专辑",
        240_000, 0, 0, "曲", "https://example.com/$id.flac")
    private fun source() = PlaybackSource("https://example.com/a.flac", AudioQuality.LOSSLESS,
        AudioQuality.LOSSLESS, AudioQuality.LOSSLESS.bitRate, "", false)

    private fun CoroutineScope.controller(
        key: PlaybackRequestKey,
        events: MutableList<PlaybackQualityEvent>,
        options: suspend (MusicTrack, (List<AudioQuality>) -> Unit) -> List<AudioQuality>,
    ) = PlaybackQualityController(this, options, { _, _ -> source() },
        { song, _ -> ResolvedPlayback(song, source()) }, { _, _ -> }, { AudioQuality.LOSSLESS },
        { it == key }, { false }, events::add)

    @Test fun openingMenuReusesRunningQueryAndMarksUserWaiting() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val events = mutableListOf<PlaybackQualityEvent>()
        val key = PlaybackRequestKey("song", 1)
        var calls = 0
        val quality = controller(key, events) { _, _ -> calls++; ready.await(); listOf(AudioQuality.LOSSLESS) }
        quality.loadOptions(track("song"), key)
        yield()
        assertFalse(quality.waitingForUser)
        quality.ensureOptions(track("song"), key)
        assertTrue(quality.waitingForUser)
        ready.complete(Unit)
        yield()
        assertEquals(1, calls)
        assertFalse(quality.waitingForUser)
        quality.cancel()
    }

    @Test fun openingMenuSkipsStartupDelayAndCompletedQueryIsNotRepeated() = runBlocking {
        val events = mutableListOf<PlaybackQualityEvent>()
        val key = PlaybackRequestKey("song", 1)
        var calls = 0
        val quality = controller(key, events) { _, _ -> calls++; listOf(AudioQuality.LOSSLESS) }
        quality.loadOptions(track("song"), key, delayMs = 60_000)
        yield()
        quality.ensureOptions(track("song"), key)
        yield()
        assertEquals(1, calls)
        quality.ensureOptions(track("song"), key)
        yield()
        assertEquals(1, calls)
        quality.cancel()
    }

    @Test fun failedQueryIsRetriedWhenMenuOpensAgain() = runBlocking {
        val events = mutableListOf<PlaybackQualityEvent>()
        val key = PlaybackRequestKey("song", 1)
        var calls = 0
        val quality = controller(key, events) { _, _ ->
            if (++calls == 1) throw PlatformApiException("网络失败") else listOf(AudioQuality.LOSSLESS)
        }
        quality.ensureOptions(track("song"), key)
        yield()
        quality.ensureOptions(track("song"), key)
        yield()
        assertEquals(2, calls)
        quality.cancel()
    }

    @Test fun progressFromBackgroundThreadIsPublishedBeforeCompleteResult() = runBlocking {
        val events = mutableListOf<PlaybackQualityEvent>()
        val key = PlaybackRequestKey("song", 1)
        val quality = controller(key, events) { _, progress ->
            withContext(Dispatchers.IO) { progress(listOf(AudioQuality.STANDARD)) }
            listOf(AudioQuality.STANDARD, AudioQuality.LOSSLESS)
        }
        quality.ensureOptions(track("song"), key)
        withTimeout(5_000) {
            while (events.none { it is PlaybackQualityEvent.Options && it.complete }) delay(10)
        }
        val options = events.filterIsInstance<PlaybackQualityEvent.Options>()
        assertEquals(listOf(false, true), options.map { it.complete })
        assertEquals(listOf(AudioQuality.STANDARD), options.first().values)
        quality.cancel()
    }

    @Test fun cachedTwentyFourBitLosslessKeepsRecordedQuality() {
        val lossless48k = FlacMediaInfo(240_000, 48_000, 24)
        assertEquals(AudioQuality.LOSSLESS, offlineStandardQuality(AudioQuality.LOSSLESS, true, lossless48k))
        assertEquals(AudioQuality.LOSSLESS, offlineStandardQuality(AudioQuality.LOSSLESS, false, lossless48k))
        assertEquals(AudioQuality.HI_RES, offlineStandardQuality(AudioQuality.HI_RES, true, lossless48k))
    }

    @Test fun cachedHeaderOnlyCorrectsDownwardOrFillsLegacyRecords() {
        val cd = FlacMediaInfo(240_000, 44_100, 16)
        val studio = FlacMediaInfo(240_000, 96_000, 24)
        assertEquals(AudioQuality.LOSSLESS, offlineStandardQuality(AudioQuality.HI_RES, true, cd))
        assertEquals(AudioQuality.LOSSLESS, offlineStandardQuality(AudioQuality.LOSSLESS, true, studio))
        assertEquals(AudioQuality.HI_RES, offlineStandardQuality(AudioQuality.LOSSLESS, false, studio))
        assertEquals(AudioQuality.EXHIGH, offlineStandardQuality(AudioQuality.EXHIGH, true, null))
    }
}
