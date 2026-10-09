package com.musicone.demo

import kotlin.math.PI
import kotlin.math.sin

/** 首开缓冲保留可见速度；页眉按同一空间进度提前接住歌词。 */
internal class PhoneLyricsOpeningHandoff(travel: Float, compact: Float, contact: Float, softness: Float) {
    private val contactPoint = minOf(.96f, contact.coerceIn(0f, 1f), (compact / travel.coerceAtLeast(1f)).coerceIn(0f, 1f))
    private val length = minOf((softness * 12f / travel.coerceAtLeast(1f)).coerceIn(.06f, .12f),
        4f * contactPoint / (1f + MIN_SPEED), 4f * (1f - contactPoint) / (11f - MIN_SPEED))
    private val begin = contactPoint - length * (1f + MIN_SPEED) / 4f
    private val end = begin + length
    private val recoveryEnd = end + 2f * length
    private val headerBand = minOf(.04f, contactPoint / 2f, (1f - contactPoint) / 2f)

    fun position(input: Float): Float {
        val u = input.coerceIn(0f, 1f)
        if (length <= .00001f || u <= begin || u >= recoveryEnd) return u
        return if (u <= end) {
            val t = (u - begin) / length
            begin + length * (t - (1f - MIN_SPEED) * integral(t))
        } else {
            val t = (u - end) / (2f * length)
            begin + length * (1f + MIN_SPEED) / 2f +
                2f * length * (t + (1f - MIN_SPEED) / 2f * integral(t))
        }
    }

    fun speed(input: Float): Float {
        if (length <= .00001f || input <= begin || input >= recoveryEnd) return 1f
        val t = if (input <= end) (input - begin) / length else (input - end) / (2f * length)
        val wave = sin(PI * t).toFloat().let { it * it }
        return if (input <= end) 1f - (1f - MIN_SPEED) * wave else 1f + (1f - MIN_SPEED) / 2f * wave
    }

    fun input(position: Float): Float {
        var low = 0f
        var high = 1f
        repeat(24) {
            val middle = (low + high) / 2f
            if (position(middle) < position) low = middle else high = middle
        }
        return (low + high) / 2f
    }

    fun header(position: Float): Float {
        if (position <= 0f) return 0f
        if (position >= 1f) return 1f
        val x = position - contactPoint
        val rounded = when {
            headerBand <= .00001f -> x.coerceAtLeast(0f)
            x <= -headerBand -> 0f
            x >= headerBand -> x
            else -> (x + headerBand) * (x + headerBand) / (4f * headerBand)
        }
        return (rounded / (1f - contactPoint).coerceAtLeast(.00001f)).coerceIn(0f, 1f)
    }

    fun headerSpeed(position: Float): Float {
        val x = position - contactPoint
        val slope = if (headerBand <= .00001f) { if (x < 0f) 0f else 1f }
            else ((x + headerBand) / (2f * headerBand)).coerceIn(0f, 1f)
        return slope / (1f - contactPoint).coerceAtLeast(.00001f)
    }

    private fun integral(t: Float) = t / 2f - (sin(2.0 * PI * t) / (4.0 * PI)).toFloat()

    companion object { const val MIN_SPEED = .35f }
}
