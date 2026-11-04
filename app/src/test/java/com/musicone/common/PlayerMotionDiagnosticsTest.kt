package com.musicone

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerMotionDiagnosticsTest {
    @Test fun diagnosticReadsDoNotRepairOrChangeLayerSubscriptions() {
        val state = mutableIntStateOf(0)
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Any(), { _: Any -> invalidations++ }) {
                PlayerMotionDiagnostics.event("test") { "value=${state.intValue}" }
                PlayerMotionDiagnostics.changed("test-change") { "value=${state.intValue}" }
            }
            state.intValue++
            Snapshot.sendApplyNotifications()
            assertEquals(0, invalidations)
        } finally { observer.stop(); observer.clear() }
    }
}
