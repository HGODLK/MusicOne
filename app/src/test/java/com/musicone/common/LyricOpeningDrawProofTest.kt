package com.musicone

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class LyricOpeningDrawProofTest {
    @Test fun preparationWaitsForMeasuredDrawOfTheCurrentFocus() = runBlocking {
        val proof = LyricOpeningDrawProof()
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { proof.awaitDraw(8) }
        try {
            proof.drawn(7, true, true, false)
            proof.drawn(8, false, true, false)
            proof.drawn(8, true, false, false)
            proof.drawn(8, true, true, true)
            yield()
            assertTrue(waiting.isActive)
            proof.drawn(8, true, true, false)
            waiting.join()
            assertTrue(waiting.isCompleted)
        } finally { waiting.cancelAndJoin() }
    }

    @Test fun cancelledOldFocusCannotReleaseTheReplacement() = runBlocking {
        val proof = LyricOpeningDrawProof()
        val old = launch(start = CoroutineStart.UNDISPATCHED) { proof.awaitDraw(8) }
        val latest = launch(start = CoroutineStart.UNDISPATCHED) { proof.awaitDraw(2) }
        try {
            old.cancelAndJoin()
            proof.drawn(8, true, true, false)
            yield()
            assertTrue(latest.isActive)
            proof.drawn(2, true, true, false)
            latest.join()
            assertTrue(latest.isCompleted)
        } finally { latest.cancelAndJoin() }
    }

    @Test fun emptyLyricsFallbackCanCompleteTheSameEntrance() = runBlocking {
        val proof = LyricOpeningDrawProof()
        val waiting = launch(start = CoroutineStart.UNDISPATCHED) { proof.awaitDraw(0) }
        try {
            proof.drawn(0, true, true, false)
            waiting.join()
            assertTrue(waiting.isCompleted)
        } finally { waiting.cancelAndJoin() }
    }
}
