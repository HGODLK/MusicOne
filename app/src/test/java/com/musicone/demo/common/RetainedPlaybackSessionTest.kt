package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class RetainedPlaybackSessionTest {
    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, "歌曲", "歌手", "专辑",
        240_000, 0, 0, "曲", "musicone-cache://audio/song.flac",
        lyrics = listOf(TimedLyric(0, "缓存歌词")))

    @Test fun recreatedPageAdoptsLiveTrackQueueLyricsQualityAndRealProgress() {
        val store = RetainedPlaybackState()
        val song = track("qq-current")
        val state = MusicOneUiState(currentTrack = song, queue = listOf(song, track("qq-next")),
            isPlaying = true, playerExpanded = true, activeQuality = AudioQuality.LOSSLESS,
            lyricLoadState = LyricLoadState.READY, qualityLoading = true)
        store.remember(state, song.id)
        val freshPage = MusicOneUiState(page = MusicOnePage.MY)
        val restored = requireNotNull(store.restore(MusicSource.QQ, song.id, freshPage, true))
        assertSame(song, restored.currentTrack)
        assertEquals(state.queue, restored.queue)
        assertEquals(AudioQuality.LOSSLESS, restored.activeQuality)
        assertEquals(LyricLoadState.READY, restored.lyricLoadState)
        assertEquals(MusicOnePage.MY, restored.page)
        assertFalse(restored.playerExpanded)
        assertFalse(restored.qualityLoading)
        val owner = PlaybackPositionOwner()
        owner.attach(1, song.id)
        assertEquals(95_000L, owner.position(1, song.id, song.id, 95_000L, 80_000L))
        assertTrue(restored.isPlaying)
        assertFalse(requireNotNull(store.restore(MusicSource.QQ, song.id, freshPage, false)).isPlaying)
    }

    @Test fun staleOrDifferentPlatformStateCannotReplaceActualServiceTrack() {
        val store = RetainedPlaybackState()
        val song = track("qq-current")
        store.remember(MusicOneUiState(currentTrack = song), song.id)
        store.remember(MusicOneUiState(currentTrack = track("qq-loading")), song.id)
        assertEquals(song, store.restore(MusicSource.QQ, song.id, MusicOneUiState(), true)?.currentTrack)
        assertNull(store.restore(MusicSource.NETEASE, song.id, MusicOneUiState(), true))
        assertNull(store.restore(MusicSource.QQ, null, MusicOneUiState(), true))
        assertNull(store.restore(MusicSource.QQ, "qq-next", MusicOneUiState(), true))
        store.clear()
        assertNull(store.restore(MusicSource.QQ, song.id, MusicOneUiState(), true))
    }
}
