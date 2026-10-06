package com.musicone.demo

import androidx.media3.common.C
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 在 USB 独占输出前应用软件音量；满音量时不改写原始整数 PCM。 */
internal object PcmSoftwareGain {
    fun apply(samples: FloatArray, gain: Float) {
        val resolved = gain.coerceIn(0f, 1f)
        if (resolved >= UNITY_GAIN) return
        if (resolved <= 0f) {
            samples.fill(0f)
            return
        }
        samples.indices.forEach { index -> samples[index] *= resolved }
    }

    fun apply(bytes: ByteArray, encoding: Int, gain: Float) {
        val resolved = gain.coerceIn(0f, 1f)
        if (resolved >= UNITY_GAIN) return
        if (resolved <= 0f) {
            bytes.fill(0)
            return
        }
        when (encoding) {
            C.ENCODING_PCM_16BIT -> scale16Bit(bytes, resolved)
            C.ENCODING_PCM_24BIT -> scale24Bit(bytes, resolved)
            C.ENCODING_PCM_32BIT -> scale32Bit(bytes, resolved)
        }
    }

    private fun scale16Bit(bytes: ByteArray, gain: Float) {
        var offset = 0
        while (offset + 1 < bytes.size) {
            val sample = ((bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)).toShort().toInt()
            val scaled = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bytes[offset] = scaled.toByte()
            bytes[offset + 1] = (scaled shr 8).toByte()
            offset += 2
        }
    }

    private fun scale24Bit(bytes: ByteArray, gain: Float) {
        var offset = 0
        while (offset + 2 < bytes.size) {
            val raw = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16)
            val sample = if (raw and 0x800000 != 0) raw or -0x1000000 else raw
            val scaled = (sample * gain).roundToInt().coerceIn(-0x800000, 0x7FFFFF)
            bytes[offset] = scaled.toByte()
            bytes[offset + 1] = (scaled shr 8).toByte()
            bytes[offset + 2] = (scaled shr 16).toByte()
            offset += 3
        }
    }

    private fun scale32Bit(bytes: ByteArray, gain: Float) {
        var offset = 0
        while (offset + 3 < bytes.size) {
            val sample = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                (bytes[offset + 3].toInt() shl 24)
            val scaled = (sample.toDouble() * gain).roundToLong()
                .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            bytes[offset] = scaled.toByte()
            bytes[offset + 1] = (scaled shr 8).toByte()
            bytes[offset + 2] = (scaled shr 16).toByte()
            bytes[offset + 3] = (scaled shr 24).toByte()
            offset += 4
        }
    }

    private const val UNITY_GAIN = .999999f
}
