package com.musicone.demo

/** 首开歌词沿原弹簧连续移动，页眉按同一空间进度提前让位。 */
internal class PhoneLyricsOpeningHandoff(travel: Float, compact: Float, contact: Float,
    geometry: PhoneLyricsOpeningGeometry? = null) {
    private val contactPoint = minOf(.96f, contact.coerceIn(0f, 1f),
        (compact / travel.coerceAtLeast(1f)).coerceIn(0f, 1f), geometry?.slowdown ?: 1f)
    private val originalHeaderBand = minOf(.04f, contactPoint / 2f, (1f - contactPoint) / 2f)
    private val headerBand = minOf(originalHeaderBand, geometry?.let { it.headerStartup / 2f } ?: 1f)
    // 新接棒只提前，不低于原空间限制需要的让位量，避免窗口补偿再次压停歌词。
    private val headerContactPoint = minOf(contactPoint - originalHeaderBand + headerBand,
        geometry?.let { it.headerBegin + headerBand } ?: 1f)

    fun header(position: Float): Float {
        if (position <= 0f) return 0f
        if (position >= 1f) return 1f
        val x = position - headerContactPoint
        val rounded = when {
            headerBand <= .00001f -> x.coerceAtLeast(0f)
            x <= -headerBand -> 0f
            x >= headerBand -> x
            else -> (x + headerBand) * (x + headerBand) / (4f * headerBand)
        }
        return (rounded / (1f - headerContactPoint).coerceAtLeast(.00001f)).coerceIn(0f, 1f)
    }

    fun headerSpeed(position: Float): Float {
        val x = position - headerContactPoint
        val slope = if (headerBand <= .00001f) { if (x < 0f) 0f else 1f }
            else ((x + headerBand) / (2f * headerBand)).coerceIn(0f, 1f)
        return slope / (1f - headerContactPoint).coerceAtLeast(.00001f)
    }
}
