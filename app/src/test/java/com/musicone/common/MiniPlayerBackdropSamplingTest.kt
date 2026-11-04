package com.musicone

import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test

class MiniPlayerBackdropSamplingTest {
    @Test fun hostDrawNotifiesOnlyCurrentOwnersWithoutRequestingAnotherFrame() {
        val updates = MiniPlayerBackdropUpdates()
        val root = Any()
        val other = Any()
        val rootFrames = mutableListOf<Long>()
        val otherFrames = mutableListOf<Long>()
        updates.register(root) { rootFrames += it }
        updates.register(other) { otherFrames += it }
        assertEquals(0L, updates.version())
        assertTrue(rootFrames.isEmpty())
        updates.hostDraw()
        updates.hostDraw()
        assertEquals(listOf(1L, 2L), rootFrames)
        assertEquals(listOf(1L, 2L), otherFrames)
        updates.remove(root)
        updates.hostDraw()
        assertEquals(listOf(1L, 2L), rootFrames)
        assertEquals(listOf(1L, 2L, 3L), otherFrames)
        assertEquals(3L, updates.version())
    }

    @Test fun callbackAndOwnRedrawDoNotRecordTheSameHostFrameTwice() {
        val updates = MiniPlayerBackdropUpdates()
        val recording = MiniPlayerGlassRecording()
        var records = 0
        recording.configure(key()) { records++ }
        updates.register(recording, recording::refresh)
        updates.hostDraw()
        recording.refresh(updates.version())
        assertEquals(1, records)
        // 前景重组产生新闭包，但采样几何及宿主帧没变，不能重录背景。
        recording.configure(key()) { records += 10 }
        recording.refresh(updates.version())
        assertEquals(1, records)
        updates.hostDraw()
        assertEquals(11, records)
    }

    @Test fun geometryRadiusDensityAndSourceBoundsChangesRefreshWithoutANewHostFrame() {
        val recording = MiniPlayerGlassRecording()
        var records = 0
        val original = key()
        val configurations = listOf(original,
            original.copy(bounds = Rect(24f, 800f, 464f, 880f)),
            original.copy(sourceBounds = Rect(0f, 0f, 480f, 960f)),
            original.copy(radius = 40f), original.copy(density = Density(3f)),
            original.copy(direction = LayoutDirection.Rtl), original.copy(fallback = Color.Black),
            original.copy(size = IntSize(160, 40)))
        configurations.forEachIndexed { index, config ->
            recording.configure(config) { records++ }
            recording.refresh(7L)
            recording.refresh(7L)
            assertEquals(index + 1, records)
        }
    }

    @Test fun missingConfigurationAndDisposalDoNotLeaveAnOldRecordingSubscribed() {
        val updates = MiniPlayerBackdropUpdates()
        val recording = MiniPlayerGlassRecording()
        var records = 0
        updates.register(recording, recording::refresh)
        updates.hostDraw()
        recording.configure(key()) { records++ }
        recording.refresh(updates.version())
        assertEquals(1, records)
        updates.remove(recording)
        recording.clear()
        updates.hostDraw()
        recording.refresh(100L)
        assertEquals(1, records)
        recording.configure(key()) { records++ }
        recording.refresh(100L)
        assertEquals(2, records)
    }

    @Test fun sampleReadsDoNotMakeTheSourceDrawingObservePlayerProgress() {
        val updates = MiniPlayerBackdropUpdates()
        val progress = mutableFloatStateOf(.2f)
        var reads = 0
        var observed = 0f
        updates.register(Any()) { observed = progress.floatValue }
        Snapshot.observe({ reads++ }, null) { updates.hostDraw() }
        assertEquals(.2f, observed, 0f)
        assertEquals(0, reads)
        progress.floatValue = .7f
        assertEquals(.2f, observed, 0f)
        Snapshot.observe({ reads++ }, null) { updates.hostDraw() }
        assertEquals(.7f, observed, 0f)
        assertEquals(0, reads)
    }

    private fun key() = MiniPlayerGlassSampleKey(Rect(12f, 800f, 468f, 880f), Rect(0f, 24f, 480f, 960f),
        IntSize(150, 36), Density(2.75f), LayoutDirection.Ltr, 49.5f, Color(0xFFF6F7F8))
}
