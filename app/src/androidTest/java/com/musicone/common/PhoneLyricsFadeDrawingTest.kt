package com.musicone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** 对比内存画布的合成结果，不采集或操作用户设备。 */
@RunWith(AndroidJUnit4::class)
class PhoneLyricsFadeDrawingTest {
    private val topRemoval = Brush.verticalGradient(listOf(Color.White, Color.Transparent), startY = 0f, endY = 1f)
    private val bottomRemoval = Brush.verticalGradient(listOf(Color.Transparent, Color.White), startY = 0f, endY = 1f)

    @Test fun bandCompositionMatchesTheOriginalFullLayerThroughUnevenProgressAndEdgeOverlap() {
        val frames = listOf(
            PhoneLyricsFadeFrame(10f, 54f, 8f, 8f, 25f, 1f),
            PhoneLyricsFadeFrame(-4.25f, 59.5f, 8.5f, 10.25f, 25f, 1f),
            PhoneLyricsFadeFrame(26f, 36f, 14f, 14f, 30f, 1f),
            PhoneLyricsFadeFrame(-20f, 90f, 8f, 8f, 25f, 1f),
            PhoneLyricsFadeFrame(20f, 18f, 8f, .5f, 25f, 1f),
        )
        for (base in frames) for (strength in listOf(0f, .01f, .04f, .38f, .4f, .82f, .98f, 1f)) {
            for (fadeBottom in listOf(false, true)) for (background in listOf(Color.Transparent, Color.Green)) {
                val frame = base.copy(strength = strength)
                val expected = render(frame, fadeBottom, background, original = true).toPixelMap()
                val actual = render(frame, fadeBottom, background, original = false).toPixelMap()
                for (y in 0 until 64) for (x in 0 until 48) {
                    val a = expected[x, y]
                    val b = actual[x, y]
                    val message = "$frame bottom=$fadeBottom background=$background pixel=$x,$y"
                    assertEquals(message, a.alpha, b.alpha, .012f)
                    // 极低透明度下颜色反预乘会放大量化误差；比较实际参与合成的预乘颜色。
                    assertEquals(message, a.red * a.alpha, b.red * b.alpha, .012f)
                    assertEquals(message, a.green * a.alpha, b.green * b.alpha, .012f)
                    assertEquals(message, a.blue * a.alpha, b.blue * b.alpha, .012f)
                }
            }
        }
    }

    private fun DrawScope.content() {
        drawRect(Color.Red.copy(alpha = .55f))
        drawRect(Color.Blue.copy(alpha = .7f), topLeft = Offset(9f, 5f), size = Size(24f, 50f))
        drawRect(Color.White.copy(alpha = .6f), topLeft = Offset(15f, 17f), size = Size(25f, 31f))
    }

    private fun render(frame: PhoneLyricsFadeFrame, fadeBottom: Boolean, background: Color,
        original: Boolean): ImageBitmap = ImageBitmap(48, 64).also { bitmap ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(48f, 64f)) {
            drawRect(background)
            if (original) drawOriginal(frame, fadeBottom)
            else drawPhoneLyricsTurnFade(frame, fadeBottom, Paint(), topRemoval, bottomRemoval) { content() }
        }
    }

    /** 保留旧实现作为像素基准，包含整窗隔离、屏外擦除及两段渐变相乘。 */
    private fun DrawScope.drawOriginal(frame: PhoneLyricsFadeFrame, fadeBottom: Boolean) {
        drawIntoCanvas { canvas ->
            canvas.saveLayer(Rect(Offset.Zero, size), Paint())
            content()
            val strength = frame.strength.coerceIn(0f, 1f)
            if (strength > 0f) {
                val upper = frame.top.coerceIn(0f, size.height)
                val lower = frame.bottom.coerceIn(0f, size.height)
                drawRect(Color.White, size = Size(size.width, upper), alpha = strength, blendMode = BlendMode.DstOut)
                drawRect(Color.White, topLeft = Offset(0f, lower), size = Size(size.width, size.height - lower),
                    alpha = strength, blendMode = BlendMode.DstOut)
                translate(top = frame.top) {
                    scale(1f, frame.topHeight.coerceAtLeast(1f), pivot = Offset.Zero) {
                        drawRect(topRemoval, size = Size(size.width, 1f), alpha = strength, blendMode = BlendMode.DstOut)
                    }
                }
                if (fadeBottom && frame.bottomHeight > 0f) translate(top = frame.bottom - frame.bottomHeight) {
                    scale(1f, frame.bottomHeight.coerceAtLeast(1f), pivot = Offset.Zero) {
                        drawRect(bottomRemoval, size = Size(size.width, 1f), alpha = strength, blendMode = BlendMode.DstOut)
                    }
                }
            }
            canvas.restore()
        }
    }
}
