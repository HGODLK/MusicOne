package com.musicone

import androidx.media3.common.C
import androidx.media3.common.Format
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun isLgAudioDevice(manufacturer: String): Boolean =
    manufacturer.trim().lowercase() in setOf("lge", "lg", "lg electronics")

/** 带 Hi-Fi Quad DAC 的 LG 机型；codes 为型号代码，aliases 为部分系统直接报出的机型名。 */
private class LgQuadDacModel(val name: String, codes: String, aliases: String, val verified: Boolean = false) {
    private val code = Regex("(?:LG-|LGM-|LM-)?(?:$codes)[A-Z0-9-]*", RegexOption.IGNORE_CASE)
    private val alias = Regex("LG (?:$aliases)(?: ThinQ)?", RegexOption.IGNORE_CASE)
    fun matches(model: String) = code.matches(model) || alias.matches(model)
}

// 只有 G7／G8 实机验证过；其余同为 Quad DAC 机型，输出路径相同，初始化失败时自动回退系统输出。
// G6 只有部分地区版带 Quad DAC，型号代码无法区分，不列入。
private val LG_QUAD_DAC_MODELS = listOf(
    LgQuadDacModel("LG G7 ThinQ", "G710", "G7", verified = true),
    LgQuadDacModel("LG G8 ThinQ", "G820", "G8", verified = true),
    LgQuadDacModel("LG G8S ThinQ", "G810", "G8S"),
    LgQuadDacModel("LG G8X ThinQ", "G850", "G8X"),
    LgQuadDacModel("LG V20", "H990|H910|H915|H918|F800|LS997|VS995|US996", "V20"),
    LgQuadDacModel("LG V30", "H930|H931|H932|H933|LS998|VS996|US998|V300", "V30|V30\\+|V30S"),
    LgQuadDacModel("LG V35 ThinQ", "V350", "V35"),
    LgQuadDacModel("LG V40 ThinQ", "V405|V409", "V40"),
    LgQuadDacModel("LG V50 ThinQ", "V450|V500", "V50"),
    LgQuadDacModel("LG V50S ThinQ", "V510", "V50S"),
    LgQuadDacModel("LG V60 ThinQ", "V600", "V60"),
)

private fun lgQuadDacModel(model: String) = model.trim().let { value -> LG_QUAD_DAC_MODELS.firstOrNull { it.matches(value) } }

internal fun isLgQuadDacModel(model: String): Boolean = lgQuadDacModel(model) != null

/** 是否为实机验证过的机型；其他 Quad DAC 机型在设置里标注未实测。 */
internal fun isLgVerifiedModel(model: String): Boolean = lgQuadDacModel(model)?.verified == true

internal fun lgAudioDeviceName(model: String): String = lgQuadDacModel(model)?.name ?: model.trim()

/** 开关关闭时的说明：区分已验证、未实测和没有 Quad DAC 的 LG 机型。 */
internal fun lgHiResIdleDescription(model: String): String = when {
    isLgVerifiedModel(model) -> "适配原厂 3.5mm 耳机口，需开启系统 Hi-Fi Quad DAC"
    isLgQuadDacModel(model) -> "本机型未实测；适配原厂 3.5mm 耳机口，需开启系统 Hi-Fi Quad DAC"
    else -> "本机没有 Hi-Fi Quad DAC，开启后仍走系统输出"
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
