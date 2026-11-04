package com.musicone

import android.graphics.BitmapShader
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.roundToInt

/** 在缓存字形上调制透明度，兼容 API 23，不重绘字体，也不增加离屏遮罩。 */
internal class LyricWordLightDrawing(bitmap: ImageBitmap, origin: Offset) {
    private val ink = BitmapShader(bitmap.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
        setLocalMatrix(Matrix().apply { setTranslate(origin.x, origin.y) })
    }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or
        android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val maskMatrix = Matrix()
    private var mask: LinearGradient? = null
    private var maskStartColor = 0
    private var maskEndColor = 0

    fun draw(scope: DrawScope, left: Float, right: Float, progress: Float, strength: Float,
        lightStart: Float, lightEnd: Float, spatial: Boolean) {
        // 把整词的移动柔边映射到当前视觉行，跨行时不从头开始。
        val width = (right - left).coerceAtLeast(1f)
        val span = (lightEnd - lightStart).coerceAtLeast(.0001f)
        val edge = progress * (1f + LYRIC_WORD_SWEEP_WIDTH)
        val start = left + (edge - LYRIC_WORD_SWEEP_WIDTH - lightStart) / span * width
        val end = start + LYRIC_WORD_SWEEP_WIDTH / span * width
        val first = alphaColor(if (spatial) 1f else lyricWordSweepAlpha(progress, 0f, strength, false))
        val last = alphaColor(if (spatial) 1f - .3f * strength else lyricWordSweepAlpha(progress, 1f, strength, false))
        // 颜色不变时只移动同一柔边；仍由词段重新录制提交矩阵，避免显示列表停留在旧位置。
        val gradient = mask?.takeIf { first == maskStartColor && last == maskEndColor }
            ?: LinearGradient(0f, 0f, 1f, 0f, first, last, Shader.TileMode.CLAMP).also {
                mask = it
                maskStartColor = first
                maskEndColor = last
                paint.shader = ComposeShader(ink, it, PorterDuff.Mode.DST_IN)
            }
        maskMatrix.setScale(if (spatial) end - start else width, 1f)
        maskMatrix.postTranslate(if (spatial) start else left, 0f)
        gradient.setLocalMatrix(maskMatrix)
        scope.drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(0f, 0f, scope.size.width, scope.size.height, paint)
        }
    }

    private fun alphaColor(alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or 0x00ffffff
}
