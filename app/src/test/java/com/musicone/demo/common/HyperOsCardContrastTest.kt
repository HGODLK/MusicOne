package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class HyperOsCardContrastTest {
    @Test fun lightAndDarkCoversChooseOppositeForegrounds() {
        assertEquals(HyperOsCardContrast.BLACK, HyperOsCardContrast.foreground(0xffeeeeee.toInt()))
        assertEquals(HyperOsCardContrast.WHITE, HyperOsCardContrast.foreground(0xff202028.toInt()))
    }

    @Test fun mixedCoverPixelsRemainReadableWithEitherChosenForeground() {
        for (foreground in listOf(HyperOsCardContrast.BLACK, HyperOsCardContrast.WHITE)) {
            val foregroundLuminance = HyperOsCardContrast.luminance(foreground)
            for (red in 0..255 step 17) for (green in 0..255 step 17) for (blue in 0..255 step 17) {
                val pixel = 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
                val background = HyperOsCardContrast.background(pixel, foreground)
                val luminance = HyperOsCardContrast.luminance(background)
                val contrast = (maxOf(luminance, foregroundLuminance) + 0.05) /
                    (minOf(luminance, foregroundLuminance) + 0.05)
                assertTrue("混色后的背景须保持可读：$contrast", contrast >= 4.5)
                assertEquals(255, background ushr 24)
            }
        }
    }
}
