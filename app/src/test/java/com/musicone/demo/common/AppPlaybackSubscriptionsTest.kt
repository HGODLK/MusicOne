package com.musicone.demo

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class AppPlaybackSubscriptionsTest {
    @Test fun trackCommitsDoNotWakeAppNavigation() = runBlocking {
        val states = MutableStateFlow(MusicOneUiState())
        val received = mutableListOf<AppPlaybackNavigation>()
        val job = launch { appPlaybackNavigation(states).collect { received += it } }
        try {
            flush()
            repeat(20) {
                states.value = states.value.copy(currentTrack = track("$it"), isPlaying = true)
                flush()
            }
            assertEquals(1, received.size)
            states.value = states.value.copy(playerExpanded = true)
            flush()
            states.value = states.value.copy(page = MusicOnePage.MY)
            flush()
            states.value = states.value.copy(playerExpanded = false)
            flush()
            assertEquals(listOf(
                AppPlaybackNavigation(MusicOnePage.HOME, false),
                AppPlaybackNavigation(MusicOnePage.HOME, true),
                AppPlaybackNavigation(MusicOnePage.MY, true),
                AppPlaybackNavigation(MusicOnePage.MY, false),
            ), received)
        } finally { job.cancelAndJoin() }
    }

    @Test fun coveredPageCoalescesSwitchesAndResumesWithLatestTrackBeforeReveal() = runBlocking {
        val initial = MusicOneUiState(currentTrack = track("a"))
        val states = MutableStateFlow(initial)
        val visible = MutableStateFlow(true)
        val received = mutableListOf<MusicOneUiState>()
        val job = launch { visiblePagePlaybackStates(states, visible).collect { received += it } }
        try {
            flush()
            visible.value = false
            flush()
            repeat(40) {
                val target = track("cached-${it % 3}")
                states.value = states.value.copy(currentTrack = target, queue = listOf(target),
                    isPlaying = it % 2 == 0, activeQuality = AudioQuality.LOSSLESS)
                flush()
            }
            assertEquals(listOf(initial), received)
            assertNotEquals(initial.currentTrack, states.value.currentTrack)
            visible.value = true
            flush()
            assertEquals(listOf(initial, states.value), received)
            visible.value = false
            flush()
            states.value = states.value.copy(currentTrack = track("a"), isPlaying = true)
            flush()
            visible.value = true
            flush()
            assertEquals(states.value, received.last())
            assertEquals(3, received.size)
        } finally { job.cancelAndJoin() }
    }

    @Test fun miniPlayerKeepsVisibleContentCurrentWithoutReactingToLyricsAndQuality() = runBlocking {
        val original = track("a")
        val states = MutableStateFlow(MusicOneUiState(currentTrack = original, queue = listOf(original)))
        val received = mutableListOf<BottomPlayerPresentation>()
        val job = launch { bottomPlayerPresentations(states).collect { received += it } }
        try {
            flush()
            states.value = states.value.copy(currentTrack = original.copy(previewUrl = "offline://cached",
                lyrics = listOf(TimedLyric(0L, "缓存歌词"))), activeQuality = AudioQuality.LOSSLESS,
                playerExpanded = true, qualityLoading = true)
            flush()
            assertEquals(1, received.size)
            states.value = states.value.copy(currentTrack = track("b"))
            flush()
            assertEquals(listOf("a", "b"), received.map { it.currentTrack?.id })
            states.value = states.value.copy(isPlaying = true)
            flush()
            assertTrue(received.last().isPlaying)
            states.value = states.value.copy(currentTrack = track("b").copy(artworkUrl = "new-cover"))
            flush()
            assertEquals("new-cover", received.last().currentTrack?.artworkUrl)
            states.value = states.value.copy(queue = emptyList(), page = MusicOnePage.MY)
            flush()
            assertFalse(received.last().hasQueue)
            assertEquals(MusicOnePage.MY, received.last().page)
        } finally { job.cancelAndJoin() }
    }

    private suspend fun flush() { repeat(10) { yield() } }
    private fun track(id: String) = MusicTrack(id, MusicSource.QQ, id, "歌手", "专辑", 180_000L,
        0L, 0L, id, "")
}
