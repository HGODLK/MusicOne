package com.musicone
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToInt
/** 同一个歌曲或歌单名称始终生成相同的双色占位封面。 */
internal fun mainSourceArtworkColors(name: String): Pair<Long, Long> {
    val normalized = Normalizer.normalize(name.trim(), Normalizer.Form.NFKC).lowercase().ifBlank { "music" }
    val hash = normalized.fold(0x811C9DC5u) { value, char ->
        (value xor char.code.toUInt()) * 0x01000193u
    }
    val hue = (hash % 360u).toFloat()
    val hueDistance = 34f + ((hash shr 9) % 58u).toFloat()
    val startSaturation = .54f + ((hash shr 17) % 13u).toFloat() / 100f
    val startLightness = .50f + ((hash shr 22) % 8u).toFloat() / 100f
    val endSaturation = .48f + ((hash shr 5) % 13u).toFloat() / 100f
    val endLightness = .29f + ((hash shr 13) % 8u).toFloat() / 100f
    return hslToMainSourceArtworkArgb(hue, startSaturation, startLightness) to
        hslToMainSourceArtworkArgb((hue + hueDistance) % 360f, endSaturation, endLightness)
}

private fun hslToMainSourceArtworkArgb(hue: Float, saturation: Float, lightness: Float): Long {
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val sector = hue / 60f
    val second = chroma * (1f - abs(sector % 2f - 1f))
    val (red, green, blue) = when (sector.toInt()) {
        0 -> Triple(chroma, second, 0f)
        1 -> Triple(second, chroma, 0f)
        2 -> Triple(0f, chroma, second)
        3 -> Triple(0f, second, chroma)
        4 -> Triple(second, 0f, chroma)
        else -> Triple(chroma, 0f, second)
    }
    val match = lightness - chroma / 2f
    fun channel(value: Float) = ((value + match) * 255f).roundToInt().coerceIn(0, 255).toLong()
    return 0xFF000000L or (channel(red) shl 16) or (channel(green) shl 8) or channel(blue)
}
