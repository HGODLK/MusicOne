package com.musicone

import org.junit.Assert.*
import org.junit.Test

class LyricBlurSamplingWindowTest {
    @Test fun 选中的窗口始终包含可见带和卷积留白() {
        for (height in listOf(853f, 1920f, 2400f, 2880f)) for (density in listOf(1f, 2.75f, 3.5f)) {
            val radius = 18f * density
            val capacities = listOf(lyricBlurSampleCapacity(420f * density, height, radius), height)
            for (step in (0..200) + (200 downTo 0)) {
                val band = lyricBottomBlurBand(height * step / 200f, height) ?: continue
                verifyWindow(band, capacities, height, radius)
            }
            val top = lyricTopBlurBand(40f * density, 150f * density) ?: continue
            verifyWindow(top, listOf(lyricBlurSampleCapacity(110f * density, height, 6f * density)), height, 6f * density)
        }
    }

    private fun verifyWindow(band: LyricBlurBand, capacities: List<Float>, height: Float, radius: Float) {
        val visibleBottom = band.bottom.coerceAtMost(height)
        val visibleTop = band.top.coerceIn(0f, visibleBottom)
        if (visibleTop == visibleBottom) return
        val original = lyricBlurSampleBounds(visibleTop, visibleBottom, height, radius)
        val capacity = capacities.firstOrNull { lyricBlurSampleFits(visibleBottom - visibleTop, it, radius) }
            ?: capacities.last()
        val origin = lyricBlurSampleOrigin(original.first, capacity, height)
        assertTrue("$band 起点晚于卷积留白", origin <= original.first)
        assertTrue("$band 丢失卷积留白", origin + capacity + .001f >= original.second)
        assertTrue(origin >= 0f && origin + capacity <= height + .001f)
        assertEquals(0f, origin % 80f, .001f)
    }

    @Test fun 平时的局部容量小于整窗且放得下控件一带() {
        val height = 2880f
        val radius = 54f
        val band = lyricBottomBlurBand(2300f, height)!!
        val capacity = lyricBlurSampleCapacity(band.bottom - band.top, height, radius)
        assertTrue(capacity < height)
        assertTrue(lyricBlurSampleFits(band.bottom - band.top, capacity, radius))
        assertFalse(lyricBlurSampleFits(height, capacity, radius))
    }
}
