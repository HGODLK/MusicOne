package com.musicone.demo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class QqPlaybackWriterTest {
    @Test fun savingDoesNotSerializeOnTheCallerAndKeepsOnlyTheLatestPendingSeek() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<Long>()
        val writer = QqPlaybackWriter(this) { _, position ->
            started.complete(Unit)
            release.await()
            saved += position
        }
        val state = MusicOneUiState()
        writer.save(state, 0)
        assertTrue(saved.isEmpty())
        assertFalse(started.isCompleted)
        started.await()
        repeat(1000) { writer.save(state, it.toLong() + 1) }
        writer.close()
        release.complete(Unit)
        writer.join()
        assertEquals(listOf(0L, 1000L), saved)
    }

    @Test fun rapidTrackChangesDrainTheLastStateOnClose() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val saved = mutableListOf<String?>()
        val writer = QqPlaybackWriter(this) { state, _ -> gate.await(); saved += state.playbackMessage }
        writer.save(MusicOneUiState(playbackMessage = "旧曲"), 0)
        yield()
        writer.save(MusicOneUiState(playbackMessage = "中间曲"), 10)
        writer.save(MusicOneUiState(playbackMessage = "最终曲"), 20)
        writer.close()
        gate.complete(Unit)
        writer.join()
        assertEquals(listOf("旧曲", "最终曲"), saved)
    }
}
