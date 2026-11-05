package com.musicone

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackTaskOwnershipTest {
    @Test fun anyResolutionFailureRetriesTwiceBeforeFailing() = runBlocking {
        for (failure in listOf(java.io.IOException("network"), TrackUnavailableException("无版权"))) {
            val song = track("kugou-song").copy(source = MusicSource.KUGOU)
            val key = PlaybackRequestKey(song.id, 1)
            val events = mutableListOf<PlaybackTrackEvent>()
            var calls = 0
            val loader = PlaybackTrackLoader(this, { _, _ -> null }, { _, _, _ -> calls++; throw failure },
                { it }, { it == key }, { false }, events::add,
                retryPolicy = PlaybackRetryPolicy(intervalMs = 0L))
            loader.start(song, key, AudioQuality.STANDARD, AudioQuality.STANDARD, 0, true, false)
            while (loader.isResolving) yield()
            assertEquals(3, calls)
            assertEquals(1, events.filterIsInstance<PlaybackTrackEvent.Failed>().size)
            loader.cancel()
        }
    }

    @Test fun slowResolutionCountsAsTimeoutAndRetries() = runBlocking {
        val song = track("slow")
        val key = PlaybackRequestKey(song.id, 1)
        val events = mutableListOf<PlaybackTrackEvent>()
        var calls = 0
        val loader = PlaybackTrackLoader(this, { _, _ -> null }, { _, _, _ -> calls++; awaitCancellation() },
            { it }, { it == key }, { false }, events::add,
            retryPolicy = PlaybackRetryPolicy(intervalMs = 0L, attemptTimeoutMs = 10L))
        loader.start(song, key, AudioQuality.STANDARD, AudioQuality.STANDARD, 0, true, false)
        while (loader.isResolving) delay(5)
        assertEquals(3, calls)
        assertEquals("音源加载超时", events.filterIsInstance<PlaybackTrackEvent.Failed>().single().message)
    }

    @Test fun retrySucceedsWithoutReportingFailure() = runBlocking {
        val song = track("flaky")
        val key = PlaybackRequestKey(song.id, 1)
        val events = mutableListOf<PlaybackTrackEvent>()
        var calls = 0
        val loader = PlaybackTrackLoader(this, { _, _ -> null }, { track, _, _ ->
            if (++calls < 3) throw java.io.IOException("network") else ResolvedPlayback(track, source())
        }, { it }, { it == key }, { false }, events::add, retryPolicy = PlaybackRetryPolicy(intervalMs = 0L))
        loader.start(song, key, AudioQuality.STANDARD, AudioQuality.STANDARD, 0, false, false)
        while (loader.isResolving) yield()
        assertTrue(events.none { it is PlaybackTrackEvent.Failed })
        assertEquals(1, events.filterIsInstance<PlaybackTrackEvent.Ready>().size)
        loader.cancel()
    }

    @Test fun definitiveMissStopsRetrying() = runBlocking {
        val song = track("no-copyright")
        val key = PlaybackRequestKey(song.id, 1)
        val events = mutableListOf<PlaybackTrackEvent>()
        var calls = 0
        val loader = PlaybackTrackLoader(this, { _, _ -> null }, { _, _, _ ->
            calls++
            // 模拟解析器：唯一档位被音源明确拒绝，降档结束。
            val session = requireNotNull(currentCoroutineContext()[PlaybackResolveSession])
            session.missed(AudioQuality.STANDARD)
            session.finish(listOf(AudioQuality.STANDARD))
            throw PlatformApiException("歌曲暂无可用播放地址")
        }, { it }, { it == key }, { false }, events::add, retryPolicy = PlaybackRetryPolicy(intervalMs = 0L))
        loader.start(song, key, AudioQuality.STANDARD, AudioQuality.STANDARD, 0, true, false)
        while (loader.isResolving) yield()
        assertEquals(1, calls)
        assertEquals(1, events.filterIsInstance<PlaybackTrackEvent.Failed>().size)
    }

    @Test fun resolveSessionKeepsMissedTiersAndTreatsTimeoutsAsUncertain() {
        val session = PlaybackResolveSession(3_000L)
        session.beginAttempt()
        session.missed(AudioQuality.HI_RES)
        session.uncertain()
        session.finish(listOf(AudioQuality.HI_RES, AudioQuality.LOSSLESS))
        assertFalse(session.definitive)
        session.beginAttempt()
        assertTrue(session.skips(AudioQuality.HI_RES))
        session.missed(AudioQuality.LOSSLESS)
        session.finish(listOf(AudioQuality.LOSSLESS))
        assertTrue(session.definitive)
        assertFalse(java.io.IOException("断网").isDefinitiveTierMiss())
        assertTrue(PlatformApiException("歌曲暂无可用播放地址").isDefinitiveTierMiss())
    }

    @Test fun threeConsecutiveFailuresPauseInsteadOfSkipping() {
        val queue = List(5) { track("song$it") }
        val recovery = PlaybackFailureRecovery()
        assertEquals(PlaybackFailureOutcome.Skip(queue[1]), recovery.fail(queue[0], queue))
        assertEquals(PlaybackFailureOutcome.Skip(queue[2]), recovery.fail(queue[1], queue))
        assertEquals(PlaybackFailureOutcome.LimitReached, recovery.fail(queue[2], queue))
        recovery.reset()
        assertEquals(PlaybackFailureOutcome.Skip(queue[3]), recovery.fail(queue[2], queue))
    }

    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, "歌曲", "歌手", "专辑",
        240_000, 0, 0, "曲", "https://example.com/$id.flac")
    private fun source(quality: AudioQuality = AudioQuality.LOSSLESS) =
        PlaybackSource("https://example.com/updated.flac", quality, quality, quality.bitRate, "", false)

    @Test fun cancelledLyricsForSameTrackCannotOverwriteNewLoad() = runBlocking {
        val first = CompletableDeferred<List<TimedLyric>>()
        val entered = CompletableDeferred<Unit>()
        val key = PlaybackRequestKey("song", 1)
        val updates = mutableListOf<PlaybackLyricsUpdate>()
        var calls = 0
        val loader = PlaybackLyricsLoader(this, {
            if (++calls == 1) withContext(NonCancellable) { entered.complete(Unit); first.await() }
            else listOf(TimedLyric(0, "新歌词"))
        }, { it == key }, updates::add)
        loader.load(track("song"), key)
        entered.await()
        loader.load(track("song"), key)
        yield()
        first.complete(listOf(TimedLyric(0, "旧歌词")))
        yield()
        assertEquals(listOf("新歌词"), updates.mapNotNull { it.lyrics?.firstOrNull()?.text })
        loader.cancel()
    }

    @Test fun trackSwitchDropsLateQualityResultAndPreferenceWrite() = runBlocking {
        val ready = CompletableDeferred<PlaybackSource>()
        val entered = CompletableDeferred<Unit>()
        val events = mutableListOf<PlaybackQualityEvent>()
        var key = PlaybackRequestKey("first", 1)
        var saved = 0
        val quality = PlaybackQualityController(this, { _, _ -> emptyList() }, { _, _ ->
            withContext(NonCancellable) { entered.complete(Unit); ready.await() }
        }, { song, _ -> ResolvedPlayback(song, source()) }, { _, _ -> saved++ },
            { AudioQuality.LOSSLESS }, { it == key }, { false }, events::add)
        quality.select(track("first"), key, AudioQuality.LOSSLESS, AudioQuality.EXHIGH, listOf(AudioQuality.LOSSLESS))
        entered.await()
        key = PlaybackRequestKey("second", 2)
        quality.cancel()
        ready.complete(source())
        yield()
        assertEquals(0, saved)
        assertTrue(events.none { it is PlaybackQualityEvent.Selected })
    }

    @Test fun lateProgressFromCancelledQualityQueryCannotReplaceNewOptions() = runBlocking {
        val ready = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val events = mutableListOf<PlaybackQualityEvent>()
        val key = PlaybackRequestKey("song", 1)
        var calls = 0
        val quality = PlaybackQualityController(this, { _, progress ->
            if (++calls == 1) withContext(NonCancellable) {
                entered.complete(Unit); ready.await()
                progress(listOf(AudioQuality.EXHIGH))
                listOf(AudioQuality.EXHIGH)
            } else listOf(AudioQuality.LOSSLESS)
        }, { _, _ -> source() }, { song, _ -> ResolvedPlayback(song, source()) }, { _, _ -> },
            { AudioQuality.LOSSLESS }, { it == key }, { false }, events::add)
        quality.loadOptions(track("song"), key)
        entered.await()
        quality.loadOptions(track("song"), key)
        yield()
        ready.complete(Unit)
        yield()
        assertEquals(listOf(listOf(AudioQuality.LOSSLESS)),
            events.filterIsInstance<PlaybackQualityEvent.Options>().map { it.values })
        quality.cancel()
    }

    @Test fun sourceResolutionAfterRapidSwitchCannotStartOldTrack() = runBlocking {
        val ready = CompletableDeferred<ResolvedPlayback>()
        val entered = CompletableDeferred<Unit>()
        val events = mutableListOf<PlaybackTrackEvent>()
        val key = PlaybackRequestKey("song", 1)
        val loader = PlaybackTrackLoader(this, { _, _ -> null }, { _, _, _ ->
            withContext(NonCancellable) { entered.complete(Unit); ready.await() }
        }, { it }, { it == key }, { true }, events::add)
        loader.start(track("song"), key, AudioQuality.LOSSLESS, AudioQuality.LOSSLESS, 50_000, true, false)
        entered.await()
        loader.cancel()
        ready.complete(ResolvedPlayback(track("song"), source()))
        yield()
        assertTrue(events.isEmpty())
    }

    @Test fun metadataCannotRevertCurrentQualityOrLyrics() {
        val song = track("song").copy(lyrics = listOf(TimedLyric(0, "当前歌词")))
        val state = MusicOneUiState(currentTrack = song, queue = listOf(song))
        val original = song.copy(previewUrl = "old", lyrics = emptyList())
        val result = state.withPlaybackMetadata(PlaybackTrackEvent.Metadata(
            PlaybackRequestKey(song.id, 1), original, original.copy(title = "补全标题")))
        assertEquals(song.previewUrl, result.currentTrack?.previewUrl)
        assertEquals(song.lyrics, result.currentTrack?.lyrics)
        assertEquals("补全标题", result.queue.single().title)
    }

    @Test fun resolvedSourceCannotOverwritePauseOrResumeRequestedDuringLoading() {
        val song = track("final")
        for (playing in listOf(false, true)) {
            val state = MusicOneUiState(currentTrack = song, queue = listOf(song), isPlaying = playing)
            val event = PlaybackTrackEvent.Ready(PlaybackRequestKey(song.id, 2L),
                ResolvedPlayback(song, source()), AudioQuality.LOSSLESS, AudioQuality.LOSSLESS,
                0L, playWhenReady = !playing)
            val result = state.withResolvedPlayback(event)
            assertEquals(playing, result.isPlaying)
            assertEquals("final", result.currentTrack?.id)
        }
    }

    @Test fun radioExitRejectsLateInitialQueue() = runBlocking {
        val ready = CompletableDeferred<List<MusicTrack>>()
        val entered = CompletableDeferred<Unit>()
        val events = mutableListOf<SourceRadioEvent>()
        val radio = SourceRadioController(this, { _, _ ->
            withContext(NonCancellable) { entered.complete(Unit); ready.await() }
        }, { SourceRadioQueueSnapshot(emptyList(), null) }, events::add)
        radio.start()
        entered.await()
        radio.stop()
        ready.complete(List(6) { track("song$it") })
        yield()
        assertTrue(events.none { it is SourceRadioEvent.Started || it is SourceRadioEvent.Appended })
        assertEquals(SourceRadioEvent.Mode(false), events.last())
    }

    @Test fun radioAdvanceDuringPrefetchIsAppliedOnceToLatestQueue() = runBlocking {
        val song = track("current")
        var snapshot = SourceRadioQueueSnapshot(listOf(song), song)
        val ready = CompletableDeferred<List<MusicTrack>>()
        val entered = CompletableDeferred<Unit>()
        val appends = mutableListOf<SourceRadioEvent.Appended>()
        var requests = 0
        val radio = SourceRadioController(this, { _, _ ->
            ++requests; entered.complete(Unit); ready.await()
        }, { snapshot }) { event ->
            if (event is SourceRadioEvent.Appended) {
                appends += event
                snapshot = snapshot.copy(queue = event.queue)
            }
        }
        radio.restore(snapshot.queue, song.id)
        radio.prefetch()
        entered.await()
        radio.advance()
        radio.advance()
        ready.complete(List(6) { track("next$it") })
        yield()
        assertEquals(1, requests)
        assertEquals(1, appends.size)
        assertTrue(appends.single().advance)
        assertEquals(song, appends.single().queue.first())
        radio.stop()
    }
}
