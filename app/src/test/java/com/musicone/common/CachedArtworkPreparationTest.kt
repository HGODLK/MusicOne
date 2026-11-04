package com.musicone

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CachedArtworkPreparationTest {
    @Test fun readsOnlyDistinctNonEmptyCachedUrls() = runBlocking {
        val read = mutableListOf<String>()
        prepareCachedArtwork(listOf(null, "", " ", "first", "second", "first")) { read += it }
        assertEquals(setOf("first", "second"), read.toSet())
        assertEquals(2, read.size)
    }

    @Test fun missingOrBrokenImageDoesNotHoldTheRestOfTheBatch() = runBlocking {
        val completed = mutableSetOf<String>()
        prepareCachedArtwork(listOf("cached", "missing", "broken")) { url ->
            if (url == "broken") throw IOException("缓存损坏")
            completed += url
        }
        assertEquals(setOf("cached", "missing"), completed)
    }

    @Test fun switchingOwnerCancelsOutstandingCachedReads() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var released = false
        val preparation = launch {
            prepareCachedArtwork(listOf("old-account")) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { released = true }
            }
        }
        entered.await()
        preparation.cancelAndJoin()
        assertTrue(preparation.isCancelled)
        assertTrue(released)
    }
}
