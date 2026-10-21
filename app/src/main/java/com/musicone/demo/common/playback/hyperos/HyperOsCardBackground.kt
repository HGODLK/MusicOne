package com.musicone.demo

import kotlin.math.exp
import kotlin.math.roundToInt

/** 在 64×32 缩略图上做可分离高斯卷积，半径随最终放大同比放大。 */
internal object HyperOsCardBackground {
    const val WIDTH = 64
    const val HEIGHT = 32
    private const val SIGMA = 6.0
    private const val RADIUS = 18
    private val kernel = DoubleArray(RADIUS * 2 + 1) { index ->
        val distance = index - RADIUS
        exp(-distance * distance / (2 * SIGMA * SIGMA))
    }.also { weights ->
        val total = weights.sum()
        weights.indices.forEach { weights[it] /= total }
    }

    fun blur(pixels: IntArray): IntArray {
        require(pixels.size == WIDTH * HEIGHT)
        val horizontal = convolve(pixels, horizontal = true)
        return convolve(horizontal, horizontal = false)
    }

    private fun convolve(pixels: IntArray, horizontal: Boolean): IntArray =
        IntArray(pixels.size) { index ->
            val x = index % WIDTH
            val y = index / WIDTH
            var red = 0.0
            var green = 0.0
            var blue = 0.0
            kernel.forEachIndexed { offset, weight ->
                // 边界延展防止模糊后出现透明或黑色边缘。
                val sx = if (horizontal) (x + offset - RADIUS).coerceIn(0, WIDTH - 1) else x
                val sy = if (horizontal) y else (y + offset - RADIUS).coerceIn(0, HEIGHT - 1)
                val pixel = pixels[sy * WIDTH + sx]
                red += ((pixel ushr 16) and 255) * weight
                green += ((pixel ushr 8) and 255) * weight
                blue += (pixel and 255) * weight
            }
            0xff000000.toInt() or (red.roundToInt().coerceIn(0, 255) shl 16) or
                (green.roundToInt().coerceIn(0, 255) shl 8) or blue.roundToInt().coerceIn(0, 255)
        }
}
