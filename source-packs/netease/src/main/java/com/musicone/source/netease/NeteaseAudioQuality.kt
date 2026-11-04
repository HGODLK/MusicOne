package com.musicone.source.netease

import org.json.JSONObject

/**
 * 网易云标准码率梯队门槛映射：
 * 128k -> 标准 (MP3L)
 * 320k -> 极高 (MP3H)
 * 350k -> 无损 SQ（最高 16位/48kHz，精准对应 sqMusic，避免被超配为 HiRes）
 * 999k -> 高解析 Hi-Res（最高 24位/192kHz，对应 hrMusic）
 */
internal fun neteaseQualityBitrate(quality: NeteaseQuality): Int = when (quality) {
    NeteaseQuality.STANDARD -> 128_000
    NeteaseQuality.HIGHER -> 192_000
    NeteaseQuality.EXHIGH -> 320_000
    NeteaseQuality.LOSSLESS -> 350_000
    NeteaseQuality.HI_RES -> 999_000
    NeteaseQuality.DOLBY -> 999_000
}

/** 先识别实际编码；压缩后的 FLAC 码率不能用于推断位深或采样率。 */
internal fun neteaseAudioQuality(level: String, format: String, bitRate: Int,
    info: NeteaseFlacInfo? = null): NeteaseQuality {
    val normalizedLevel = level.lowercase()
    if (info != null) return if (info.highResolution) NeteaseQuality.HI_RES else NeteaseQuality.LOSSLESS
    val encoding = format.lowercase()
    if (encoding in setOf("flac", "alac")) {
        return if (normalizedLevel == "hires") NeteaseQuality.HI_RES else NeteaseQuality.LOSSLESS
    }
    if (encoding in setOf("mp3", "aac", "m4a")) return when {
        normalizedLevel == "standard" -> NeteaseQuality.STANDARD
        normalizedLevel == "higher" -> NeteaseQuality.HIGHER
        normalizedLevel == "exhigh" -> NeteaseQuality.EXHIGH
        bitRate > 192_000 -> NeteaseQuality.EXHIGH
        bitRate > 128_000 -> NeteaseQuality.HIGHER
        else -> NeteaseQuality.STANDARD
    }
    return when (normalizedLevel) {
        "hires" -> NeteaseQuality.HI_RES
        "lossless" -> NeteaseQuality.LOSSLESS
        "exhigh" -> NeteaseQuality.EXHIGH
        "higher" -> NeteaseQuality.HIGHER
        else -> NeteaseQuality.STANDARD
    }
}

internal fun neteasePlaybackSource(value: JSONObject, requested: NeteaseQuality,
    inspect: (String) -> NeteaseFlacInfo? = { readNeteaseFlacInfo(it) }): NeteasePlaybackAddress {
    val url = value.optString("url").takeIf { it.isNotBlank() && it != "null" }?.asNeteasePlaybackUrl()
        ?: throw NeteaseProtocolException("歌曲暂无可用播放地址")
    val format = value.optString("type").ifBlank { value.optString("encodeType") }.ifBlank { "audio" }
    val bitRate = value.optInt("br").coerceAtLeast(0)
    val sampleRate = value.optInt("sr")
    val bitDepth = value.optInt("bitDepth", value.optInt("bitdepth"))
    val info = if (format.equals("flac", true)) inspect(url) ?: if (sampleRate > 0 && bitDepth in 1..32)
        NeteaseFlacInfo(0, sampleRate, bitDepth) else null else null
    return NeteasePlaybackAddress(url, requested,
        neteaseAudioQuality(value.optString("level"), format, bitRate, info),
        bitRate, format, value.has("freeTrialInfo") && !value.isNull("freeTrialInfo"),
        actualFormat = info?.description)
}

/** 按实际规格验证音质，每档必须拿到同档文件；只有 Hi-Res 文件时 FLAC 档不算可用，不接纳试听。 */
internal fun verifiedNeteaseQuality(quality: NeteaseQuality, media: NeteasePlaybackAddress): Boolean =
    !media.trial && media.actualQuality == quality
