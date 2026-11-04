package com.musicone.sourceintegration

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.*
import org.junit.Test

class SourceRootCoordinatesTest {
    @Test fun publishingSurfaceContextDoesNotSubscribeThePageToMotion() {
        val coordinates = SourceRootCoordinates()
        var reads = 0
        lateinit var context: SourcePublicSurfaceContext
        Snapshot.observe(readObserver = { reads++ }) {
            context = SourcePublicSurfaceContext(true, true, Offset.Zero, coordinates = coordinates)
        }
        assertEquals(0, reads)
        val reader = coordinates.read
        coordinates.offset = Offset(240f, 32f)
        assertSame(reader, coordinates.read)
        assertEquals(Offset(240f, 32f), context.currentRootOffset)
        coordinates.offset = Offset(-80f, 24f)
        assertEquals(Offset(-80f, 24f), reader())
        assertEquals(Offset(-80f, 24f), context.currentRootOffset)
    }

    @Test fun otherSurfaceModesKeepTheirOriginalOffset() {
        val context = SourcePublicSurfaceContext(true, true, Offset(12f, 48f))
        assertEquals(context.rootOffset, context.currentRootOffset)
        assertNull(context.rootIdentity)
        assertNull(context.coordinates)
    }
}
