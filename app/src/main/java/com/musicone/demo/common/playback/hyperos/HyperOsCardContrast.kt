package com.musicone.demo

import kotlin.math.pow

/** 先选择更清晰的黑白前景，再把封面向反方向混色，保证每个背景像素的对比度。 */
internal object HyperOsCardContrast {
    const val BLACK: Int = -0x1000000
    const val WHITE: Int = -0x1

    fun luminance(color: Int): Double {
        fun linear(shift: Int): Double {
            val value = ((color ushr shift) and 255) / 255.0
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)
    }

    fun foreground(average: Int): Int = if (luminance(average) > 0.179) BLACK else WHITE

    fun background(pixel: Int, foreground: Int): Int {
        val light = foreground == BLACK
        fun channel(shift: Int): Int {
            val value = (pixel ushr shift) and 255
            return if (light) (value * 0.5 + 255 * 0.5).toInt() else (value * 0.44).toInt()
        }
        return BLACK or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
