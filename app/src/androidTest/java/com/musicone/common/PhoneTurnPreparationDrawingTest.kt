package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 在测试内存画布验证透明像素交接，不采集设备屏幕。 */
@RunWith(AndroidJUnit4::class)
class PhoneTurnPreparationDrawingTest {
    private fun source(): ImageBitmap = ImageBitmap(12, 12).also { bitmap ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(12f, 12f)) {
            drawRect(Color.Blue, size = Size(4f, 12f))
        }
    }

    @Test fun transparentSourcePixelsHideTheUnfinishedTargetButPreserveTheParentBackground() {
        var targetDraws = 0
        val output = ImageBitmap(12, 12)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(output), Size(12f, 12f)) {
            drawRect(Color.Green)
            drawPhoneTurnPreparedFrame(source(), 1f, preparing = true, isolationPaint = Paint()) {
                targetDraws++
                drawRect(Color.Red, topLeft = Offset(6f, 0f), size = Size(6f, 12f))
            }
        }
        assertEquals(1, targetDraws)
        val pixels = output.toPixelMap()
        assertEquals(Color.Blue, pixels[2, 6])
        assertEquals(Color.Green, pixels[8, 6])
    }

    @Test fun fallbackKeepsTheOriginalSourceOverFadeAndNoSourceKeepsLiveContent() {
        for (preparing in listOf(false, true)) {
            val output = ImageBitmap(12, 12)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(output), Size(12f, 12f)) {
                drawPhoneTurnPreparedFrame(if (preparing) null else source(), .5f, preparing, Paint()) {
                    drawRect(Color.Red)
                }
            }
            val pixels = output.toPixelMap()
            assertEquals(Color.Red, pixels[8, 6])
            if (!preparing) {
                assertEquals(.5f, pixels[2, 6].red, .01f)
                assertEquals(.5f, pixels[2, 6].blue, .01f)
            }
        }
    }
}
