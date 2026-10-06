package com.musicone.demo

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class PlayerVisualTrackTest {
    @Test fun nextPreviewSettlesWithoutReplayingTheVisualTransition() = checkHandoff(TrackTransitionDirection.NEXT)
    @Test fun previousPreviewSettlesWithoutReplayingTheVisualTransition() = checkHandoff(TrackTransitionDirection.PREVIOUS)

    private fun checkHandoff(direction: TrackTransitionDirection) = observe {
        val target = track("b")
        preview.value = presentation(target, direction)
        flush()
        assertEquals(listOf("a", "b"), received.map { it.id })
        preview.value = preview.value!!.copy(phase = RapidTrackSwitchPhase.SETTLING,
            track = target.copy(lyrics = listOf(TimedLyric(0L, "缓存歌词"))))
        playback.value = playback.value.copy(currentTrack = target.copy(previewUrl = "offline://cached",
            album = "补全专辑", mediaId = "新文件", qualityIds = mapOf(AudioQuality.LOSSLESS to "flac")))
        flush()
        preview.value = null
        flush()
        assertEquals(listOf("a", "b"), received.map { it.id })
        val committed = playback.value.currentTrack!!
        assertSame(committed, playerVisualActionTrack(committed, received.last()))
        playback.value = playback.value.copy(currentTrack = committed.copy(title = "补全标题"))
        flush()
        assertEquals(listOf("a", "b", "b"), received.map { it.id })
        assertEquals("补全标题", received.last().title)
    }

    @Test fun rapidCachedReversalsDisplayOnlyEachNewTarget() = observe {
        val expected = mutableListOf("a")
        repeat(24) { index ->
            val target = track("cached-${index % 3}")
            val direction = if (index % 4 < 2) TrackTransitionDirection.NEXT else TrackTransitionDirection.PREVIOUS
            preview.value = presentation(target, direction).copy(token = index.toLong(),
                phase = RapidTrackSwitchPhase.BROWSING)
            flush()
            expected += target.id
            preview.value = preview.value!!.copy(track = target.copy(lyrics = listOf(TimedLyric(0L, "$index"))))
            flush()
        }
        playback.value = playback.value.copy(currentTrack = preview.value!!.track.copy(previewUrl = "offline://final"))
        preview.value = null
        flush()
        assertEquals(expected, received.map { it.id })
    }

    @Test fun previewRemovalReadsCommittedTrackBeforeItsNotificationArrives() = runBlocking {
        val initial = MusicOneUiState(currentTrack = track("a"))
        val notifications = MutableStateFlow(initial)
        var latest = initial
        // 模拟真实播放状态已提交、订阅通知尚未送达，预览先被撤下的交接窗口。
        val playback = object : StateFlow<MusicOneUiState> by notifications {
            override val value: MusicOneUiState get() = latest
        }
        val preview = MutableStateFlow<RapidTrackSwitchPresentation?>(null)
        val received = mutableListOf<MusicTrack>()
        val job = launch { playerVisualTracks(playback, preview).collect { received += it } }
        try {
            repeat(10) { yield() }
            val target = track("b")
            preview.value = presentation(target, TrackTransitionDirection.NEXT)
            repeat(10) { yield() }
            latest = initial.copy(currentTrack = target.copy(album = "补全专辑"))
            preview.value = null
            repeat(10) { yield() }
            assertEquals(listOf("a", "b"), received.map { it.id })
            notifications.value = latest
            repeat(10) { yield() }
            assertEquals(listOf("a", "b"), received.map { it.id })
        } finally {
            job.cancelAndJoin()
        }
    }

    @Test fun cancellingAnUncommittedPreviewReturnsToThePlayingTrack() = observe {
        preview.value = presentation(track("b"), TrackTransitionDirection.PREVIOUS)
        flush()
        preview.value = null
        flush()
        assertEquals(listOf("a", "b", "a"), received.map { it.id })
    }

    @Test fun annotationOnlyMetadataChangesDoNotReplayTheTextTransition() = observe {
        val target = track("b").copy(title = "夜に駆ける（ヨルニカケル）", artists = "YOASOBI（ヨアソビ）")
        preview.value = presentation(target, TrackTransitionDirection.NEXT)
        flush()
        playback.value = playback.value.copy(currentTrack = target.copy(title = "夜に駆ける", artists = "YOASOBI"))
        preview.value = null
        flush()
        assertEquals(listOf("a", "b"), received.map { it.id })
        playback.value = playback.value.copy(currentTrack = playback.value.currentTrack!!.copy(artists = "另一位创作者"))
        flush()
        assertEquals("另一位创作者", received.last().artists)
        assertEquals(3, received.size)
    }

    private fun observe(block: suspend Observation.() -> Unit) = runBlocking {
        val observation = Observation(this, track("a"))
        try {
            observation.flush()
            observation.block()
        } finally {
            observation.job.cancelAndJoin()
        }
    }

    private class Observation(scope: CoroutineScope, initial: MusicTrack) {
        val playback = MutableStateFlow(MusicOneUiState(currentTrack = initial))
        val preview = MutableStateFlow<RapidTrackSwitchPresentation?>(null)
        val received = mutableListOf<MusicTrack>()
        val job = scope.launch { playerVisualTracks(playback, preview).collect { received += it } }
        suspend fun flush() { repeat(10) { yield() } }
    }

    private fun presentation(track: MusicTrack, direction: TrackTransitionDirection) =
        RapidTrackSwitchPresentation(track, direction, RapidTrackSwitchPhase.BROWSING,
            lyricsAvailable = track.lyrics.isNotEmpty(), token = 1L)

    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, id, "歌手", "专辑", 180_000L,
        0L, 0L, id, "", lyrics = listOf(TimedLyric(0L, "首句")))
}
