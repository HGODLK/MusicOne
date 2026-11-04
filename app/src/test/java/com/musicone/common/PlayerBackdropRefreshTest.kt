package com.musicone

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class PlayerBackdropRefreshTest {
    @Test fun closingOnlyInvalidatesWhenSamplingCrossesTheOriginalQuarterThreshold() = runBlocking {
        val motion = PageMotion(CoroutineScope(coroutineContext + BroadcastFrameClock()), true, 320, 280)
        motion.request(false)
        yield()
        motion.progress.snapTo(.8f)
        val refresh = playerBackdropRefreshState(motion)
        val observer = SnapshotStateObserver { it() }
        var invalidations = 0
        val onChanged: (Unit) -> Unit = { invalidations++ }
        observer.start()
        try {
            observer.observeReads(Unit, onChanged) { assertFalse(refresh.value) }
            for (progress in listOf(.6f, .4f, .25f)) {
                motion.progress.snapTo(progress)
                Snapshot.sendApplyNotifications()
            }
            assertEquals(0, invalidations)
            motion.progress.snapTo(.24f)
            Snapshot.sendApplyNotifications()
            assertEquals(1, invalidations)
            observer.observeReads(Unit, onChanged) { assertTrue(refresh.value) }
            for (progress in listOf(.2f, .1f, 0f)) {
                motion.progress.snapTo(progress)
                Snapshot.sendApplyNotifications()
            }
            assertEquals(1, invalidations)
        } finally { observer.stop(); observer.clear(); coroutineContext.cancelChildren() }
    }

    @Test fun openingProgressDoesNotInvalidateTheHomepageSamplingSwitch() = runBlocking {
        val motion = PageMotion(this, true, 320, 280)
        motion.beginDrag()
        val refresh = playerBackdropRefreshState(motion)
        val observer = SnapshotStateObserver { it() }
        var invalidations = 0
        observer.start()
        try {
            observer.observeReads(Unit, { _: Unit -> invalidations++ }) { assertFalse(refresh.value) }
            for (step in 0..100) {
                motion.dragTo(step / 100f)
                Snapshot.sendApplyNotifications()
            }
            assertEquals(0, invalidations)
        } finally { observer.stop(); observer.clear() }
    }
}
