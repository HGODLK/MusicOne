package com.musicone.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class LyricPresentationTest {
    @Test fun playbackMetadataDoesNotChangeLyricPresentation() {
        val original = track("a")
        val resolved = original.copy(previewUrl = "offline://cached", title = "补全标题",
            album = "补全专辑", artworkUrl = "cover", qualityIds = mapOf(AudioQuality.LOSSLESS to "flac"))
        assertEquals(request(original), request(resolved))
    }

    @Test fun lyricsTimingDurationAndIdentityRemainObservable() {
        val original = track("a")
        val expected = request(original)
        for (changed in listOf(
            original.copy(lyrics = listOf(TimedLyric(0L, "修正歌词", "翻译"))),
            original.copy(lyrics = listOf(TimedLyric(500L, "首句"))),
            original.copy(durationMs = 200_000L),
            original.copy(id = "b"),
            original.copy(source = MusicSource.NETEASE),
        )) assertNotEquals(expected, request(changed))
    }

    @Test fun nextPreviewHandoffDoesNotEmitAnotherLyricRequest() = checkHandoff(TrackTransitionDirection.NEXT)
    @Test fun previousPreviewHandoffDoesNotEmitAnotherLyricRequest() = checkHandoff(TrackTransitionDirection.PREVIOUS)

    @Test fun committedPreviewDoesNotReturnToPreviousSongWhileUiCompositionLags() = runBlocking {
        val initial = MusicOneUiState().forTrackTransition(track("a"), playWhenReady = true)
        val playback = MutableStateFlow(initial)
        val preview = MutableStateFlow<RapidTrackSwitchPresentation?>(null)
        val received = mutableListOf<LyricPresentationRequest>()
        val job = launch {
            lyricPresentationRequests(lyricPlaybackRequests(playback), preview).collect { received += it }
        }
        try {
            repeat(10) { yield() }
            val target = track("c")
            preview.value = previewFor(target).copy(phase = RapidTrackSwitchPhase.SETTLING)
            repeat(10) { yield() }
            // 正式状态先更新，页面参数仍可能留在 a；QQ 歌词解析期间先发布 loading。
            playback.value = initial.forTrackTransition(target.copy(lyrics = emptyList()), playWhenReady = true)
            preview.value = null
            repeat(10) { yield() }
            playback.value = playback.value.copy(currentTrack = target, lyricLoadState = LyricLoadState.READY)
            repeat(10) { yield() }
            assertEquals("a", received.first().trackId)
            assertEquals("c", received[1].trackId)
            assertTrue(received.drop(1).all { it.trackId == "c" })
            assertEquals(target.lyrics, received.last().presentation?.lyrics)
        } finally {
            job.cancelAndJoin()
        }
    }

    private fun checkHandoff(direction: TrackTransitionDirection) = observe(direction) {
        val target = track("b")
        preview.value = previewFor(target, direction)
        flush()
        assertEquals(listOf("a", "b"), received.map { it.trackId })
        val entering = received.last()
        preview.value = preview.value!!.copy(phase = RapidTrackSwitchPhase.SETTLING)
        playback.value = request(target.copy(previewUrl = "offline://cached"), direction)
        flush()
        preview.value = null
        flush()
        assertEquals(2, received.size)
        assertSame(entering, received.last())
    }

    @Test fun rapidCachedSwitchesAndReversalsKeepEveryNewTargetWithoutHandoffDuplicates() = observe {
        val expected = mutableListOf("a")
        repeat(40) { index ->
            val direction = if (index % 4 < 2) TrackTransitionDirection.NEXT else TrackTransitionDirection.PREVIOUS
            val target = track("cached-${index % 3}")
            preview.value = previewFor(target, direction).copy(token = index.toLong(),
                phase = RapidTrackSwitchPhase.BROWSING)
            flush()
            expected += target.id
            playback.value = request(target.copy(previewUrl = "offline://$index"), direction)
            preview.value = preview.value!!.copy(phase = RapidTrackSwitchPhase.SETTLING)
            flush()
        }
        preview.value = null
        flush()
        assertEquals(expected, received.map { it.trackId })
    }

    @Test fun lateCachedLyricsAreDeliveredWithoutAnEmptyEnteringWindow() = observe {
        val target = track("b")
        preview.value = previewFor(target.copy(lyrics = emptyList()))
        flush()
        assertNull(received.last().presentation)
        preview.value = previewFor(target)
        flush()
        assertEquals(target.lyrics, received.last().presentation?.lyrics)
        playback.value = request(target, state = LyricLoadState.LOADING)
        flush()
        assertEquals(3, received.size)
        playback.value = request(target)
        flush()
        preview.value = null
        flush()
        assertEquals(3, received.size)
    }

    @Test fun naturalSwitchAndRealLyricUpdatesStillReachTheWindow() = observe {
        val target = track("b")
        playback.value = request(target, state = LyricLoadState.LOADING)
        flush()
        assertNull(received.last().presentation)
        playback.value = request(target)
        flush()
        val corrected = target.copy(lyrics = listOf(TimedLyric(0L, "修正歌词")))
        playback.value = request(corrected)
        flush()
        assertEquals(corrected.lyrics, received.last().presentation?.lyrics)
        assertEquals(4, received.size)
    }

    @Test fun loadingAndUnavailableRetainTheirDifferentMeaning() {
        val target = track("a")
        assertNull(request(target, state = LyricLoadState.LOADING).presentation)
        assertNull(request(target.copy(lyrics = emptyList())).presentation)
        assertEquals(emptyList<TimedLyric>(), request(target, state = LyricLoadState.UNAVAILABLE).presentation?.lyrics)
    }

    private fun observe(
        direction: TrackTransitionDirection = TrackTransitionDirection.NEXT,
        block: suspend Observation.() -> Unit,
    ) = runBlocking {
        val observation = Observation(this, request(track("a"), direction))
        try {
            observation.flush()
            observation.block()
        } finally {
            observation.job.cancelAndJoin()
        }
    }

    private class Observation(scope: CoroutineScope, initial: LyricPresentationRequest) {
        val playback = MutableStateFlow(initial)
        val preview = MutableStateFlow<RapidTrackSwitchPresentation?>(null)
        val received = mutableListOf<LyricPresentationRequest>()
        val job = scope.launch { lyricPresentationRequests(playback, preview).collect { received += it } }
        suspend fun flush() { repeat(10) { yield() } }
    }

    private fun request(track: MusicTrack, direction: TrackTransitionDirection = TrackTransitionDirection.NEXT,
        state: LyricLoadState = LyricLoadState.READY) = lyricPresentationRequest(track, state, direction)

    private fun previewFor(track: MusicTrack, direction: TrackTransitionDirection = TrackTransitionDirection.NEXT) =
        RapidTrackSwitchPresentation(track, direction, RapidTrackSwitchPhase.BROWSING,
            track.lyrics.isNotEmpty(), token = 1L)

    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, id, "歌手", "专辑", 180_000L,
        0L, 0L, id, "", lyrics = listOf(TimedLyric(0L, "首句")))
}
