package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** 在内存画布比较旧着色与原生图层滤镜，不读取设备屏幕。 */
@RunWith(AndroidJUnit4::class)
class PlayerFlightTintDrawingTest {
    @Test fun nativeFilterMatchesSourceInOverlayIncludingContentAndTintAlpha() {
        val colors = listOf(
            Color.Black to Color.White.copy(alpha = .62f),
            Color(0x994320A0) to Color(0xBB20C080),
            Color.Transparent to Color.White,
        )
        for ((from, to) in colors) {
            for (progress in listOf(0f, .08f, .25f, .5f, .75f, .92f, 1f)) {
                val original = render(from, to, progress, nativeFilter = false).toPixelMap()
                val filtered = render(from, to, progress, nativeFilter = true).toPixelMap()
                for (y in 0 until 32) for (x in 0 until 48) {
                    val expected = original[x, y].toArgb()
                    val actual = filtered[x, y].toArgb()
                    for (shift in listOf(0, 8, 16, 24)) {
                        assertTrue("progress=$progress pixel=$x,$y channel=$shift",
                            abs((expected ushr shift and 255) - (actual ushr shift and 255)) <= 1)
                    }
                }
            }
        }
    }

    @Test fun transparentPixelsAndChangedContentKeepTheirAlpha() {
        for (native in listOf(false, true)) {
            val first = render(Color.Black, Color.White, .5f, native, changed = false).toPixelMap()
            val changed = render(Color.Black, Color.White, .5f, native, changed = true).toPixelMap()
            assertEquals(0f, first[46, 30].alpha, 0f)
            assertEquals(0f, changed[46, 30].alpha, 0f)
            assertEquals(0f, first[40, 27].alpha, 0f)
            assertTrue(changed[40, 27].alpha > 0f)
        }
    }

    private fun render(from: Color, to: Color, progress: Float, nativeFilter: Boolean,
        changed: Boolean = false): ImageBitmap = ImageBitmap(48, 32).also { bitmap ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(48f, 32f)) {
            val paint = Paint()
            if (nativeFilter) paint.colorFilter = playerFlightTintFilter(from, to, progress)
            drawContext.canvas.saveLayer(Rect(Offset.Zero, size), paint)
            content(changed)
            if (!nativeFilter) {
                drawRect(lerp(from, to, playerMaterialProgress(progress)), blendMode = BlendMode.SrcIn)
            }
            drawContext.canvas.restore()
        }
    }

    private fun DrawScope.content(changed: Boolean) {
        drawRect(Color.Red.copy(alpha = .35f), topLeft = Offset(2f, 2f), size = Size(20f, 17f))
        drawCircle(Color.Blue.copy(alpha = .7f), radius = 10.25f, center = Offset(24.5f, 13.5f))
        drawLine(Color.Green.copy(alpha = .45f), Offset(3.25f, 25.25f), Offset(32.5f, 21.5f), 2.5f)
        if (changed) drawRect(Color.White.copy(alpha = .6f), Offset(38f, 25f), Size(5f, 4f))
    }
}
