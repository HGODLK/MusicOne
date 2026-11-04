package com.musicone

import androidx.media3.common.C
import androidx.media3.common.Format
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun isLgAudioDevice(manufacturer: String): Boolean =
    manufacturer.trim().lowercase() in setOf("lge", "lg", "lg electronics")

internal fun isLgG7G8(model: String): Boolean =
    Regex("(?:LM-|LG-)?G(?:710|820)[A-Z0-9-]*", RegexOption.IGNORE_CASE).matches(model.trim()) ||
        Regex("LG G[78](?: ThinQ)?", RegexOption.IGNORE_CASE).matches(model.trim())

internal fun lgAudioDeviceName(model: String): String = when {
    isLgG7G8(model) && model.contains("G7", ignoreCase = true) -> "LG G7 ThinQ"
    isLgG7G8(model) -> "LG G8 ThinQ"
    else -> model.trim()
}

internal fun lgPcmOutputDepth(format: Format, sourceDepth: Int): Int? {
    if (format.sampleRate !in 8000..384000 || format.channelCount !in 1..2) return null
    if (format.pcmEncoding !in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT)) return null
    return if (sourceDepth > 24) 32 else 24
}

/** 只做整数容器重排及必要的补零，不插值、不抖动、不改变采样率。 */
internal fun packLgPcm(input: ByteBuffer, encoding: Int, outputDepth: Int, output: ByteBuffer): Int {
    require(outputDepth == 24 || outputDepth == 32)
    val source = input.duplicate().order(ByteOrder.LITTLE_ENDIAN)
    output.clear()
    val inputBytes = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> error("不支持的 LG PCM 编码")
    }
    while (source.remaining() >= inputBytes && output.remaining() >= outputDepth / 8) {
        val sample = when (encoding) {
            C.ENCODING_PCM_16BIT -> source.short.toInt() shl 16
            C.ENCODING_PCM_24BIT -> ((source.get().toInt() and 255) shl 8) or
                ((source.get().toInt() and 255) shl 16) or (source.get().toInt() shl 24)
            C.ENCODING_PCM_32BIT -> source.int
            C.ENCODING_PCM_FLOAT -> {
                val value = source.float.toDouble()
                if (value.isNaN()) 0 else (value.coerceIn(-1.0, 1.0) * 2147483648.0)
                    .coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toLong().toInt()
            }
            else -> error("不支持的 LG PCM 编码")
        }
        if (outputDepth == 32) output.put(sample.toByte())
        output.put((sample shr 8).toByte()).put((sample shr 16).toByte()).put((sample shr 24).toByte())
    }
    output.flip()
    return source.position() - input.position()
}

/** 一次补数尽量填满原生队列；只有已接收的完整帧才消费输入，队列满时立即回压。 */
internal fun fillLgPcmQueue(input: ByteBuffer, encoding: Int, outputDepth: Int, channels: Int,
    packed: ByteBuffer, enqueue: (ByteBuffer) -> Int, submitted: (Long) -> Unit): Int {
    while (input.hasRemaining()) {
        val inputBytes = packLgPcm(input, encoding, outputDepth, packed)
        check(inputBytes > 0) { "LG PCM 缓冲未按采样对齐" }
        val packedBytes = packed.remaining()
        val written = enqueue(packed)
        if (written < 0) return -1
        if (written == 0) return 0
        val frameBytes = outputDepth / 8 * channels
        check(written <= packedBytes && written % frameBytes == 0) { "LG PCM 写入未按帧对齐" }
        input.position(input.position() + (inputBytes.toLong() * written / packedBytes).toInt())
        submitted((written / frameBytes).toLong())
    }
    return 1
}
