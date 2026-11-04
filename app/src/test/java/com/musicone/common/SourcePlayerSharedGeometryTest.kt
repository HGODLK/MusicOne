package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.musicone.sourceintegration.sourcePlayerSharedTargetOrigin
import com.musicone.sourceintegration.sourcePlayerSharedTargetValid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePlayerSharedGeometryTest {
    @Test fun portraitTargetIncludesIndependentViewOffset() {
        assertEquals(Offset(30f, 50f), sourcePlayerSharedTargetOrigin(
            Offset(20f, 30f), Offset(10f, 20f), IntSize(400, 800), 0))
    }

    @Test fun clockwiseLandscapeMapsVirtualCanvasIntoPhysicalWindow() {
        assertEquals(Offset(380f, 40f), sourcePlayerSharedTargetOrigin(
            Offset(20f, 30f), Offset(10f, 20f), IntSize(800, 400), 90))
    }

    @Test fun counterclockwiseLandscapeUsesOppositePhysicalOrigin() {
        assertEquals(Offset(40f, 800f), sourcePlayerSharedTargetOrigin(
            Offset(20f, 30f), Offset(10f, 20f), IntSize(800, 400), -90))
    }

    @Test fun declaredSharedTargetsMustHaveVisibleMeasuredArea() {
        assertTrue(sourcePlayerSharedTargetValid(IntSize(120, 32)))
        assertFalse(sourcePlayerSharedTargetValid(IntSize(0, 32)))
        assertFalse(sourcePlayerSharedTargetValid(IntSize(120, 0)))
        assertFalse(sourcePlayerSharedTargetValid(IntSize.Zero))
    }
}
