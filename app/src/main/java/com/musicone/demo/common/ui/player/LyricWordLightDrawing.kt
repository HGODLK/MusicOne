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

    fun draw(scope: DrawScope, left: Float, right: Float, leftAlpha: Float, rightAlpha: Float) {
        val mask = LinearGradient(left, 0f, right.coerceAtLeast(left + 1f), 0f,
            alphaColor(leftAlpha), alphaColor(rightAlpha), Shader.TileMode.CLAMP)
        paint.shader = ComposeShader(ink, mask, PorterDuff.Mode.DST_IN)
        scope.drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(0f, 0f, scope.size.width, scope.size.height, paint)
        }
    }

    private fun alphaColor(alpha: Float): Int =
        ((alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24) or 0x00ffffff
}
