package com.musicone.demo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class QqSimilarRecommendationRequestsTest {
    @Test fun independentRequestsOverlapButResultsKeepSeedOrder() = runBlocking {
        withTimeout(5_000) {
            val secondStarted = CompletableDeferred<Unit>()
            val delivered = mutableListOf<String>()
            var running = 0
            var maximum = 0
            loadOrderedQqSimilarRecommendations(listOf(track("first"), track("second"), track("third")), { base ->
                running++
                maximum = maxOf(maximum, running)
                try {
                    if (base.id == "first") secondStarted.await()
                    if (base.id == "second") secondStarted.complete(Unit)
                    recommendation(base)
                } finally {
                    running--
                }
            }) { page ->
                delivered += page.baseTrack.id
                true
            }
            assertEquals(listOf("first", "second", "third"), delivered)
            assertEquals(2, maximum)
        }
    }

    @Test fun stoppingAfterFirstPageCancelsPendingRequests() = runBlocking {
        withTimeout(5_000) {
            val pendingStarted = CompletableDeferred<Unit>()
            var cancelled = false
            loadOrderedQqSimilarRecommendations(listOf(track("first"), track("pending")), { base ->
                if (base.id == "first") {
                    pendingStarted.await()
                    recommendation(base)
                } else {
                    pendingStarted.complete(Unit)
                    try { awaitCancellation() } finally { cancelled = true }
                }
            }) { false }
            assertTrue(cancelled)
        }
    }

    @Test fun laterFailureDoesNotDiscardEarlierOrderedResult() = runBlocking {
        withTimeout(5_000) {
            val laterStarted = CompletableDeferred<Unit>()
            val delivered = mutableListOf<String>()
            try {
                loadOrderedQqSimilarRecommendations(listOf(track("first"), track("failed")), { base ->
                    if (base.id == "first") {
                        laterStarted.await()
                        recommendation(base)
                    } else {
                        laterStarted.complete(Unit)
                        error("后续请求失败")
                    }
                }) { page -> delivered += page.baseTrack.id; true }
                fail("应保留原有失败行为")
            } catch (error: IllegalStateException) {
                assertEquals("后续请求失败", error.message)
            }
            assertEquals(listOf("first"), delivered)
        }
    }

    private fun recommendation(base: MusicTrack) = QqSimilarRecommendation(base, "推荐", listOf(QqSimilarSong(base)))

    private fun track(id: String) = MusicTrack(
        id = id, source = MusicSource.QQ, title = id, artists = "歌手", album = "专辑",
        durationMs = 180_000, artworkStart = 0, artworkEnd = 0, artworkMark = id, previewUrl = "",
    )
}
