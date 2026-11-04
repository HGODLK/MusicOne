package com.musicone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLyricsBlurSamplingTest {
    @Test fun oddPhoneAndTabletSizesKeepTheWholeSourceCovered() {
        for ((width, height) in listOf(1081f to 2401f, 2560f to 1600f, 1439.5f to 901.25f)) {
            val sampled = lyricBlurSampleSize(width, height)
            assertTrue(sampled.width >= width && sampled.width - width < 1f)
            assertTrue(sampled.height >= height && sampled.height - height < 1f)
        }
    }

    @Test fun zeroExtentNeverCreatesAnEmptyRenderTarget() {
        val sampled = lyricBlurSampleSize(0f, 0f)
        assertEquals(1, sampled.width)
        assertEquals(1, sampled.height)
    }

    @Test fun disablingEitherBlurSwitchBypassesBackdropBlur() {
        for (api in listOf(23, 30, 31, 37)) {
            assertEquals(false, lyricBackdropBlurEnabled(api, disabled = true, controlsDisabled = false))
            assertEquals(false, lyricBackdropBlurEnabled(api, disabled = false, controlsDisabled = true))
            assertEquals(false, lyricBackdropBlurEnabled(api, disabled = true, controlsDisabled = true))
            assertEquals(api >= 31, lyricBackdropBlurEnabled(api, disabled = false, controlsDisabled = false))
        }
    }

    @Test fun 底部和顶部都只有一档且空范围不绘制() {
        assertEquals(LyricBlurBand(1800f, 2400f), lyricBottomBlurBand(1800f, 2400f))
        assertNull(lyricBottomBlurBand(2400f, 2400f))
        assertEquals(LyricBlurBand(160f, 400f), lyricTopBlurBand(160f, 400f))
        assertNull(lyricTopBlurBand(400f, 400f))
    }

    @Test fun movingBandSamplesKeepEnoughBlurPaddingWithoutFullWindow() {
        val height = 2400f
        val (top, bottom) = lyricBlurSampleBounds(1800f, 2000f, height, 54f)
        assertTrue(top <= 1800f - 170f)
        assertTrue(bottom >= 2000f + 170f)
        assertTrue(bottom - top < height)
    }
}
