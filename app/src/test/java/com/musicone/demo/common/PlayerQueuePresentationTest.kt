package com.musicone.demo

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlayerQueuePresentationTest {
    @Test fun queueKeepsFollowingPlaybackWhileTheCoveredPageIsFrozen() = runBlocking {
        val a = track("a")
        val b = track("b")
        val states = MutableStateFlow(MusicOneUiState(queue = listOf(a, b), currentTrack = a,
            playerExpanded = true))
        val pageVisible = MutableStateFlow(true)
        val pages = mutableListOf<MusicOneUiState>()
        val queue = mutableListOf<PlayerQueuePresentation>()
        val pageJob = launch { visiblePagePlaybackStates(states, pageVisible).collect { pages += it } }
        val queueJob = launch { playerQueuePresentations(states).collect { queue += it } }
        try {
            flush()
            pageVisible.value = false
            flush()
            states.value = states.value.copy(currentTrack = b, isPlaying = true)
            flush()
            assertEquals("a", pages.last().currentTrack?.id)
            assertEquals("b", queue.last().currentTrackId)
            assertTrue(queue.last().isPlaying)
            states.value = states.value.copy(isPlaying = false)
            flush()
            assertFalse(queue.last().isPlaying)
            states.value = states.value.copy(queue = listOf(b, a))
            flush()
            assertEquals(listOf("b", "a"), queue.last().queue.map { it.id })
            states.value = states.value.copy(queue = emptyList(), currentTrack = null)
            flush()
            assertTrue(queue.last().queue.isEmpty())
            assertNull(queue.last().currentTrackId)
        } finally { pageJob.cancelAndJoin(); queueJob.cancelAndJoin() }
    }

    @Test fun unrelatedLyricsQualityAndNavigationDoNotWakeQueueContent() = runBlocking {
        val a = track("a")
        val states = MutableStateFlow(MusicOneUiState(queue = listOf(a), currentTrack = a))
        val received = mutableListOf<PlayerQueuePresentation>()
        val job = launch { playerQueuePresentations(states).collect { received += it } }
        try {
            flush()
            states.value = states.value.copy(currentTrack = a.copy(lyrics = listOf(TimedLyric(0L, "歌词"))),
                activeQuality = AudioQuality.LOSSLESS, qualityLoading = true,
                playerExpanded = true, page = MusicOnePage.MY)
            flush()
            assertEquals(1, received.size)
            states.value = states.value.copy(queue = listOf(a.copy(title = "新曲名")))
            flush()
            assertEquals("新曲名", received.last().queue.single().title)
        } finally { job.cancelAndJoin() }
    }

    private suspend fun flush() { repeat(10) { yield() } }
    private fun track(id: String) = MusicTrack(id, MusicSource.LOCAL, id, "歌手", "专辑",
        180_000L, 0L, 0L, id, "")
}
