package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class HyperOsCardBackgroundTest {
    @Test fun aSolidCoverKeepsItsColorWithoutDarkEdges() {
        val color = 0xff8fca56.toInt()
        val pixels = IntArray(HyperOsCardBackground.WIDTH * HyperOsCardBackground.HEIGHT) { color }
        assertArrayEquals(pixels, HyperOsCardBackground.blur(pixels))
    }

    @Test fun largeGaussianBlurSoftensTheBoundaryInBothDirectionsWithoutChangingTheSource() {
        val w = HyperOsCardBackground.WIDTH
        val h = HyperOsCardBackground.HEIGHT
        for (horizontal in listOf(true, false)) {
            val pixels = IntArray(w * h) { index ->
                val farSide = if (horizontal) index % w >= w / 2 else index / w >= h / 2
                if (farSide) -1 else 0xff000000.toInt()
            }
            val original = pixels.copyOf()
            val blurred = HyperOsCardBackground.blur(pixels)
            val center = (h / 2) * w + w / 2
            val stride = if (horizontal) 1 else w
            fun red(index: Int) = (blurred[index] ushr 16) and 255
            assertTrue(red(center - 6 * stride) in 1..100)
            assertTrue(red(center) in 100..160)
            assertTrue(red(center + 6 * stride) in 160..254)
            assertTrue(red(center - stride) < red(center))
            assertArrayEquals(original, pixels)
            assertTrue(blurred.all { it ushr 24 == 255 })
        }
    }
}
