package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 比较原渐变与矩阵复用的真实像素，涵盖半透明文字和屏外边界。 */
@RunWith(AndroidJUnit4::class)
class LyricBackdropMaskDrawingTest {
    @Test fun normalizedTopMasksPreserveTheOriginalPremultipliedPixels() {
        for (top in listOf(-12.25f, 0f, 7.5f, 38f)) for (height in listOf(.5f, 8.25f, 44f)) {
            for (strength in listOf(0f, .13f, .6f, 1f)) for (blurred in listOf(false, true)) {
                for (canBlur in listOf(false, true)) {
                    val old = render(top, height, strength, blurred, canBlur, false).toPixelMap()
                    val actual = render(top, height, strength, blurred, canBlur, true).toPixelMap()
                    for (y in 0 until 64) for (x in 0 until 48) {
                        val a = old[x, y]
                        val b = actual[x, y]
                        val message = "top=$top height=$height strength=$strength blur=$blurred/$canBlur pixel=$x,$y"
                        assertEquals(message, a.alpha, b.alpha, .012f)
                        assertEquals(message, a.red * a.alpha, b.red * b.alpha, .012f)
                        assertEquals(message, a.blue * a.alpha, b.blue * b.alpha, .012f)
                    }
                }
            }
        }
    }

    private fun render(top: Float, height: Float, strength: Float, blurred: Boolean,
        canBlur: Boolean, normalized: Boolean): ImageBitmap = ImageBitmap(48, 64).also { bitmap ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(48f, 64f)) {
            val bounds = Rect(0f, top, size.width, top + height)
            clipRect(top = top, bottom = top + height) {
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(bounds, Paint())
                    drawRect(Color.Red.copy(alpha = .55f))
                    drawRect(Color.Blue.copy(alpha = .7f), topLeft = Offset(9f, 5f), size = Size(24f, 50f))
                    if (normalized) drawLyricBackdropMask(
                        lyricBackdropTopMask(blurred, canBlur, .6f, 1f, strength), top, height.coerceAtLeast(1f))
                    else translate(top = top) {
                        drawRect(lyricBackdropTopMask(blurred, canBlur, .6f, height, strength), blendMode = BlendMode.DstIn)
                    }
                    canvas.restore()
                }
            }
        }
    }
}
