package com.musicone

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LyricDisplayPreparationTest {
    @Test fun childWaitUsesTheSameRemainingDeadlineInsteadOfRestartingThreeSeconds() = runBlocking {
        var now = 10_000_000_000L
        val budget = LyricPreparationBudget({ now })
        assertEquals(3_000L, budget.remainingMillis())
        now += 2_400_000_000L
        assertEquals(600L, budget.remainingMillis())
        assertEquals(200L, budget.remainingMillis(now + 200_000_000L))
        now += 600_000_000L
        assertFalse(budget.await(CompletableDeferred()))
        assertEquals(0L, budget.remainingMillis())
    }

    @Test fun cancelledProofFailsWithoutConsumingAnotherBudgetAndCompletedProofNeedsNoWait() = runBlocking {
        val budget = LyricPreparationBudget({ 10L }, 0L)
        val cancelled = CompletableDeferred<Unit>().apply { cancel() }
        assertFalse(budget.await(cancelled))
        assertTrue(budget.await(CompletableDeferred(Unit)))
    }

    @Test fun cancellingTheOwnerCannotPublishEvenAnAlreadyCompletedOldProof() = runBlocking {
        var published = false
        val parent = Job().apply { cancel() }
        val job = launch(parent) {
            if (LyricPreparationBudget().await(CompletableDeferred(Unit))) published = true
        }
        job.join()
        assertFalse(published)
    }

    @Test fun oldSongPaneMountViewportAndDirectionCannotReleaseANewOpening() = runBlocking {
        val pane = newLyricWindowPane(1, 0)
        val stamp = LyricDisplayStamp(pane, "a", 1, IntSize(340, 600), 180, 0)
        val proof = LyricOpeningDrawProof()
        val wait = launch(start = CoroutineStart.UNDISPATCHED) { proof.awaitDraw(0, stamp) }
        for (old in listOf(stamp.copy(track = "b"), stamp.copy(pane = newLyricWindowPane(1, 0)),
            stamp.copy(mount = 0), stamp.copy(viewport = IntSize(340, 300)), stamp.copy(rotation = 90))) {
            proof.drawn(0, true, true, false, old)
            yield()
            assertTrue(wait.isActive)
        }
        proof.drawn(0, true, true, false, stamp)
        wait.join()
        assertTrue(wait.isCompleted)
    }

    @Test fun repeatedOpeningCreatesANewRecordingRequestOnTheSamePane() = runBlocking {
        val proof = LyricOpeningDrawProof()
        val first = launch(start = CoroutineStart.UNDISPATCHED) { proof.awaitDraw(0) }
        val firstRevision = proof.revision
        proof.drawn(0, true, true, false)
        first.join()
        val second = launch(start = CoroutineStart.UNDISPATCHED) { proof.awaitDraw(0) }
        assertTrue(proof.revision > firstRevision)
        assertTrue(second.isActive)
        proof.drawn(0, true, true, false)
        second.join()
    }

    @Test fun recordingReferencesChangeForMountAndPreparationButNotForOrdinaryPlaybackFrames() {
        val epoch = LyricDrawEpoch()
        val pane = Any()
        val owner = Any()
        val opening = Any()
        epoch.observe(owner, listOf(pane, 1, IntSize(340, 600), opening))
        val first = epoch.revision
        repeat(120) { epoch.observe(owner, listOf(pane, 1, IntSize(340, 600), opening)) }
        assertEquals(first, epoch.revision)
        epoch.observe(owner, listOf(pane, 2, IntSize(340, 600), opening))
        assertEquals(first + 1, epoch.revision)
        epoch.observe(owner, listOf(pane, 2, IntSize(340, 600), Any()))
        assertEquals(first + 2, epoch.revision)
    }

    @Test fun invisibleSourceWithoutRealTextGeometryCannotBecomeTheRotationOrigin() {
        val anchor = PhoneLyricsReadingAnchor()
        assertFalse(anchor.captureFrame())
        assertFalse(anchor.held)
    }
}
