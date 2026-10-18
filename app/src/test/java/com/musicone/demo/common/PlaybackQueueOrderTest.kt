package com.musicone.demo

import kotlin.random.Random
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackQueueOrderTest {
    @Test fun largePlaylistRetainsEveryTrackThroughShuffleAndSnapshotRestore() {
        val large = (0 until 2_505).map {
            MusicTrack("qq-large-$it", MusicSource.QQ, "歌曲$it", "歌手", "", 180_000, 0, 0, "", "")
        }
        val state = MusicOneUiState().withPlaylistQueue(large, true, Random(9))
            .let { it.copy(currentTrack = it.queue[1_500]) }
        assertEquals(2_505, state.queue.size)
        val encoding = QqPlaybackSnapshotEncoding()
        val saved = JSONObject(encoding.contentIfChanged(state.toQqPlaybackSnapshot(500)!!)!!).toQqPlaybackSnapshot()
        val restored = MusicOneUiState().withQqPlaybackSnapshot(saved).withPlaybackMode(PlayerPlaybackMode.LIST)
        assertEquals(large.map { it.id }, restored.queue.map { it.id })
        val added = large.first().copy(id = "qq-new")
        assertEquals(2_506, insertTrackAfterCurrent(restored.queue, restored.currentTrack, added).size)
    }

    private val tracks = (0..7).map {
        MusicTrack("qq-$it", MusicSource.QQ, "歌曲$it", "歌手", "专辑", 180_000, 0, 0, "歌", "")
    }

    @Test fun playerShuffleChangesVisibleQueueWithoutRestartingCurrentTrack() {
        val initial = MusicOneUiState(currentTrack = tracks[3], queue = tracks, isPlaying = true)
        val shuffled = initial.withPlaybackMode(PlayerPlaybackMode.SHUFFLE, Random(42))
        assertEquals(tracks[3], shuffled.currentTrack)
        assertEquals(tracks[3], shuffled.queue.first())
        assertTrue(shuffled.isPlaying)
        assertEquals(tracks.toSet(), shuffled.queue.toSet())
        assertNotEquals(tracks, shuffled.queue)
        val restored = shuffled.withPlaybackMode(PlayerPlaybackMode.SINGLE).withPlaybackMode(PlayerPlaybackMode.LIST)
        assertEquals(tracks, restored.queue)
        assertEquals(tracks[3], restored.currentTrack)
        assertFalse(restored.shuffle)
        assertEquals(RepeatMode.ALL, restored.repeatMode)
    }

    @Test fun playlistShuffleRestoresItsSourceOrderThroughTheSameModeSwitch() {
        val shuffled = MusicOneUiState().withPlaylistQueue(tracks, shuffle = true, Random(42))
        assertTrue(shuffled.shuffle)
        assertNotEquals(tracks, shuffled.queue)
        assertEquals(tracks, shuffled.withPlaybackMode(PlayerPlaybackMode.LIST).queue)
        val replacement = shuffled.withPlaylistQueue(tracks.reversed(), shuffle = false)
        assertFalse(replacement.shuffle)
        assertEquals(tracks.reversed(), replacement.queue)
    }

    @Test fun previewAndPlaybackFollowTheDisplayedShuffledOrderForACompleteCycle() {
        val shuffled = MusicOneUiState().withPlaylistQueue(tracks, true, Random(5))
        shuffled.queue.indices.forEach { index ->
            val state = shuffled.copy(currentTrack = shuffled.queue[index])
            val expected = shuffled.queue[(index + 1) % tracks.size]
            assertEquals(expected, miniPlayerNeighbor(state, PlaybackHistory(), next = true))
            assertEquals(expected, shuffled.queue[nextQueueIndex(index, tracks.size, true, RepeatMode.ALL, true)!!])
        }
    }

    @Test fun restoredOrderUsesLatestMetadataAndRetainsInsertedSongs() {
        val shuffled = MusicOneUiState(currentTrack = tracks[2], queue = tracks)
            .withPlaybackMode(PlayerPlaybackMode.SHUFFLE, Random(3))
        val added = tracks[0].copy(id = "qq-added")
        val inserted = shuffled.copy(
            queue = insertTrackAfterCurrent(shuffled.queue, tracks[2], added),
            queueOrder = shuffled.queueOrder.insert(shuffled.queue, tracks[2], added),
        )
        val updated = inserted.copy(queue = inserted.queue.map {
            if (it.id == tracks[4].id) it.copy(title = "已更新", lyrics = listOf(TimedLyric(0, "歌词"))) else it
        }).withPlaybackMode(PlayerPlaybackMode.LIST)
        assertEquals(tracks.take(3).map { it.id } + added.id + tracks.drop(3).map { it.id }, updated.queue.map { it.id })
        assertEquals("已更新", updated.queue.first { it.id == tracks[4].id }.title)
        assertEquals(1, updated.queue.first { it.id == tracks[4].id }.lyrics.size)
    }

    @Test fun shuffleSnapshotRetainsOriginalOrderAcrossPlayerRecreation() {
        val shuffled = MusicOneUiState(currentTrack = tracks[4], queue = tracks)
            .withPlaybackMode(PlayerPlaybackMode.SHUFFLE, Random(8))
        val encoding = QqPlaybackSnapshotEncoding()
        val snapshot = JSONObject(encoding.contentIfChanged(shuffled.toQqPlaybackSnapshot(42_000)!!)!!)
            .toQqPlaybackSnapshot()
        val restored = MusicOneUiState().withQqPlaybackSnapshot(snapshot)
        assertEquals(shuffled.queue.map { it.id }, restored.queue.map { it.id })
        assertEquals(tracks.map { it.id }, restored.withPlaybackMode(PlayerPlaybackMode.LIST).queue.map { it.id })
    }

    @Test fun repeatedSeeksReuseEncodedQueueAndChangesStillPersist() {
        val encoding = QqPlaybackSnapshotEncoding()
        val state = MusicOneUiState(currentTrack = tracks[0], queue = tracks)
        assertNotNull(encoding.contentIfChanged(state.toQqPlaybackSnapshot(0)!!))
        repeat(1000) { assertNull(encoding.contentIfChanged(state.toQqPlaybackSnapshot(it.toLong())!!)) }
        val shuffled = state.withPlaybackMode(PlayerPlaybackMode.SHUFFLE, Random(8))
        assertNotNull(encoding.contentIfChanged(shuffled.toQqPlaybackSnapshot(0)!!))
        assertNotNull(encoding.contentIfChanged(shuffled.copy(currentTrack = tracks[1]).toQqPlaybackSnapshot(0)!!))
    }

    @Test fun snapshotsWithoutOriginalOrderStillRestoreTheirVisibleQueue() {
        val state = MusicOneUiState(currentTrack = tracks[0], queue = tracks, shuffle = true)
        val json = JSONObject(QqPlaybackSnapshotEncoding().contentIfChanged(state.toQqPlaybackSnapshot(1000)!!)!!)
        json.remove("originalQueueIds")
        val restored = MusicOneUiState().withQqPlaybackSnapshot(json.toQqPlaybackSnapshot())
        assertEquals(tracks.map { it.id }, restored.withPlaybackMode(PlayerPlaybackMode.LIST).queue.map { it.id })
    }

    @Test fun playlistPlayExplicitlyOverridesShuffleAndRepeatModeToAll() {
        // 原状态为单曲循环 + 随机播放
        val previous = MusicOneUiState(currentTrack = tracks[1], queue = tracks, shuffle = true, repeatMode = RepeatMode.ONE)
        // 点击歌单“播放”：明确设置为列表播放 (shuffle = false)，循环模式强制设为全部循环 (repeatMode = ALL)
        val updated = previous.withPlaylistQueue(tracks, shuffle = false, resetRepeatMode = true)
        assertFalse(updated.shuffle)
        assertEquals(RepeatMode.ALL, updated.repeatMode)
        assertEquals(tracks, updated.queue)
    }

    @Test fun playlistShuffleExplicitlyOverridesRepeatModeToAllAndShuffles() {
        // 原状态为单曲循环 + 普通播放
        val previous = MusicOneUiState(currentTrack = tracks[0], queue = tracks, shuffle = false, repeatMode = RepeatMode.ONE)
        // 点击歌单“随机”：明确设置为随机播放 (shuffle = true)，循环模式强制设为全部循环 (repeatMode = ALL)
        val updated = previous.withPlaylistQueue(tracks, shuffle = true, random = Random(42), resetRepeatMode = true)
        assertTrue(updated.shuffle)
        assertEquals(RepeatMode.ALL, updated.repeatMode)
        assertNotEquals(tracks, updated.queue)
        assertEquals(tracks.toSet(), updated.queue.toSet())
    }

    @Test fun singleTrackPlaybackPreservesExistingShuffleAndRepeatMode() {
        // 点播单曲保持原有模式规则，不自动覆盖为整张歌单播放
        val previous = MusicOneUiState(currentTrack = tracks[2], queue = tracks, shuffle = true, repeatMode = RepeatMode.ONE)
        val updated = previous.withPlaylistQueue(tracks, shuffle = previous.shuffle, resetRepeatMode = false)
        assertTrue(updated.shuffle)
        assertEquals(RepeatMode.ONE, updated.repeatMode)
    }
}
