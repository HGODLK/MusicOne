package com.musicone.demo

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

    fun draw(scope: DrawScope, left: Float, right: Float, progress: Float, strength: Float,
        lightStart: Float, lightEnd: Float, spatial: Boolean) {
        // 把整词的移动柔边映射到当前视觉行，跨行时不从头开始。
        val width = (right - left).coerceAtLeast(1f)
        val span = (lightEnd - lightStart).coerceAtLeast(.0001f)
        val edge = progress * (1f + LYRIC_WORD_SWEEP_WIDTH)
        val start = left + (edge - LYRIC_WORD_SWEEP_WIDTH - lightStart) / span * width
        val end = start + LYRIC_WORD_SWEEP_WIDTH / span * width
        val mask = if (spatial) LinearGradient(start, 0f, end, 0f,
            alphaColor(1f), alphaColor(1f - .3f * strength), Shader.TileMode.CLAMP)
        else LinearGradient(left, 0f, left + width, 0f,
            alphaColor(lyricWordSweepAlpha(progress, 0f, strength, false)),
            alphaColor(lyricWordSweepAlpha(progress, 1f, strength, false)), Shader.TileMode.CLAMP)
        paint.shader = ComposeShader(ink, mask, PorterDuff.Mode.DST_IN)
        scope.drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(0f, 0f, scope.size.width, scope.size.height, paint)
        }
    }

    private fun alphaColor(alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or 0x00ffffff
}
