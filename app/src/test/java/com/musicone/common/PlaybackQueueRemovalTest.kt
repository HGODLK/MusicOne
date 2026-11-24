package com.musicone

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueRemovalTest {
    private val tracks = (1..4).map { track("qq-$it") }

    @Test fun removingAnotherSongKeepsCurrentPlaybackAndNeverRestartsTransport() {
        for (playing in listOf(false, true)) {
            val before = MusicOneUiState(currentTrack = tracks[1], queue = tracks, isPlaying = playing,
                activeQuality = AudioQuality.LOSSLESS)
            val states = MutableStateFlow(before)
            var saves = 0
            PlaybackQueueRemoval(states, { _, _ -> fail("不能切歌") }, { fail("不能停止播放") }, { saves++ })
                .remove(tracks[3])
            assertEquals(tracks.take(3), states.value.queue)
            assertSame(before.currentTrack, states.value.currentTrack)
            assertEquals(playing, states.value.isPlaying)
            assertEquals(before.activeQuality, states.value.activeQuality)
            assertEquals(1, saves)
        }
    }

    @Test fun removingCurrentChoosesItsSuccessorAndPreservesPausedIntent() {
        val before = MusicOneUiState(currentTrack = tracks[1], queue = tracks, isPlaying = false,
            repeatMode = RepeatMode.ONE)
        val states = MutableStateFlow(before)
        var started: MusicTrack? = null
        PlaybackQueueRemoval(states, { next, remaining ->
            started = next
            assertFalse(remaining.isPlaying)
            assertEquals(RepeatMode.ONE, remaining.repeatMode)
            states.value = remaining.forTrackTransition(next, playWhenReady = false)
        }, { fail("队列未空") }, { fail("切曲自行保存") }).remove(tracks[1])
        assertEquals(tracks[2], started)
        assertEquals(listOf(tracks[0], tracks[2], tracks[3]), states.value.queue)
        assertEquals(tracks[2], states.value.currentTrack)
        assertFalse(states.value.isPlaying)
    }

    @Test fun removingLastCurrentWrapsToTheFirstRemainingSong() {
        val plan = playbackQueueRemovalPlan(MusicOneUiState(currentTrack = tracks.last(), queue = tracks), tracks.last())!!
        assertTrue(plan.removedCurrent)
        assertEquals(tracks.first(), plan.nextTrack)
    }

    @Test fun removingOnlySongClearsTransportAndPendingPresentationState() {
        val states = MutableStateFlow(MusicOneUiState(currentTrack = tracks[0], queue = tracks.take(1),
            isPlaying = true, playerExpanded = true, qualityLoading = true, qualityChanging = true,
            activeQuality = AudioQuality.LOSSLESS, radioActive = true, radioLoading = true))
        var cleared = false
        PlaybackQueueRemoval(states, { _, _ -> fail("空队列不能切歌") }, { cleared = true }, { fail("空队列单独保存") })
            .remove(tracks[0])
        assertTrue(cleared)
        assertTrue(states.value.queue.isEmpty())
        assertNull(states.value.currentTrack)
        assertFalse(states.value.isPlaying)
        assertFalse(states.value.playerExpanded)
        assertFalse(states.value.qualityLoading)
        assertFalse(states.value.qualityChanging)
        assertFalse(states.value.radioActive)
        assertFalse(states.value.radioLoading)
        assertNull(states.value.activeQuality)
    }

    @Test fun removalClearsOnlyAnAffectedExplicitNextRequest() {
        val before = MusicOneUiState(currentTrack = tracks[0], queue = tracks).withTrackInsertedNext(tracks[2])
        val ordinary = playbackQueueRemovalPlan(before, tracks[3])!!.state
        assertEquals(tracks[2], ordinary.queue[ordinary.insertedNextIndex()!!])
        val removedNext = playbackQueueRemovalPlan(before, tracks[2])!!.state
        assertNull(removedNext.insertedNextIndex())
        assertNull(removedNext.queueOrder.nextTrackId)
    }

    @Test fun restoringShuffleOrderCannotBringBackRemovedSongs() {
        val before = MusicOneUiState(currentTrack = tracks[1], queue = tracks.reversed(), shuffle = true,
            queueOrder = PlaybackQueueOrder(tracks.map { it.id }))
        val removed = playbackQueueRemovalPlan(before, tracks[2])!!.state
        assertEquals(listOf(tracks[0], tracks[1], tracks[3]), removed.withPlaybackMode(PlayerPlaybackMode.LIST).queue)
    }

    @Test fun missingOrOtherSourceSongsDoNotAlterTheQueue() {
        val before = MusicOneUiState(currentTrack = tracks[0], queue = tracks)
        assertNull(playbackQueueRemovalPlan(before, track("missing")))
        assertNull(playbackQueueRemovalPlan(before, tracks[0].copy(source = MusicSource.LOCAL)))
    }

    @Test fun batchRemovalUpdatesOnceAndSkipsToFirstKeptSuccessor() {
        val queue = (1..6).map { track("qq-$it") }
        val states = MutableStateFlow(MusicOneUiState(currentTrack = queue[1], queue = queue, isPlaying = true))
        var started: MusicTrack? = null
        var updates = 0
        PlaybackQueueRemoval(states, { next, remaining -> started = next; updates++; states.value = remaining },
            { fail("队列未空") }, { fail("切曲自行保存") }).removeAll(listOf(queue[1], queue[2], queue[3]))
        assertEquals(queue[4], started)
        assertEquals(1, updates)
        assertEquals(listOf(queue[0], queue[4], queue[5]), states.value.queue)
    }

    @Test fun batchRemovalOfTailWrapsToFirstAndOthersSaveOnce() {
        val states = MutableStateFlow(MusicOneUiState(currentTrack = tracks[3], queue = tracks))
        var started: MusicTrack? = null
        PlaybackQueueRemoval(states, { next, remaining -> started = next; states.value = remaining },
            { fail("队列未空") }, { fail("切曲自行保存") }).removeAll(listOf(tracks[2], tracks[3]))
        assertEquals(tracks[0], started)
        var saves = 0
        val others = MutableStateFlow(MusicOneUiState(currentTrack = tracks[0], queue = tracks))
        PlaybackQueueRemoval(others, { _, _ -> fail("不能切歌") }, { fail("不能停止播放") }, { saves++ })
            .removeAll(listOf(tracks[1], tracks[2], tracks[3]))
        assertEquals(1, saves)
        assertEquals(listOf(tracks[0]), others.value.queue)
    }

    @Test fun removingEverythingClearsPlaybackOnceAndCollapsesPlayer() {
        val states = MutableStateFlow(MusicOneUiState(currentTrack = tracks[0], queue = tracks, isPlaying = true,
            playerExpanded = true))
        var clears = 0
        PlaybackQueueRemoval(states, { _, _ -> fail("不能切歌") }, { clears++ }, { fail("清空不单独保存") })
            .removeAll(tracks)
        assertEquals(1, clears)
        assertNull(states.value.currentTrack)
        assertTrue(states.value.queue.isEmpty())
        assertFalse(states.value.playerExpanded)
        assertFalse(states.value.isPlaying)
    }

    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, "歌曲", "歌手", "", 240_000, 0, 0, "", "")
}
