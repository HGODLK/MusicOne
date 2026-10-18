package com.musicone.demo

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerTextGeometryHandoffTest {
    @Test fun closingBeforeRecompositionUsesTheNewLyricsHeaderAnchor() {
        val source = MotionAnchor(Rect(10f, 1800f, 150f, 1830f))
        val coverTitle = MotionAnchor(Rect(20f, 1400f, 300f, 1440f))
        val sizedTitle = coverTitle.copy(bounds = Rect(20f, 1400f, 260f, 1440f))
        val geometry = PlayerTextGeometry(source, sizedTitle, source, coverTitle)
        val lyricsTitle = MotionAnchor(Rect(100f, 70f, 352f, 106f), contentScale = .9f)

        assertEquals(lyricsTitle, geometry.targetFor(lyricsTitle))
        assertEquals(sizedTitle, geometry.targetFor(coverTitle))
        val newSource = source.copy(bounds = Rect(15f, 1800f, 155f, 1830f))
        assertEquals(newSource, geometry.sourceFor(newSource))
    }
}
