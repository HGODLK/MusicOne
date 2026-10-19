package com.musicone.demo

import android.util.Log
import org.json.JSONObject

/** 先识别实际编码；压缩后的 FLAC 码率不能用于推断位深或采样率。 */
internal fun neteaseAudioQuality(level: String, format: String, bitRate: Int,
    info: QqFlacInfo? = null): AudioQuality {
    val normalizedLevel = level.lowercase()
    if (info != null) return if (info.highResolution) AudioQuality.HI_RES else AudioQuality.LOSSLESS
    val encoding = format.lowercase()
    if (encoding in setOf("flac", "alac")) {
        return if (normalizedLevel == "hires") AudioQuality.HI_RES else AudioQuality.LOSSLESS
    }
    if (encoding in setOf("mp3", "aac", "m4a")) return when {
        normalizedLevel == "standard" -> AudioQuality.STANDARD
        normalizedLevel == "higher" -> AudioQuality.HIGHER
        normalizedLevel == "exhigh" -> AudioQuality.EXHIGH
        bitRate > 192_000 -> AudioQuality.EXHIGH
        bitRate > 128_000 -> AudioQuality.HIGHER
        else -> AudioQuality.STANDARD
    }
    return when (normalizedLevel) {
        "hires" -> AudioQuality.HI_RES
        "lossless" -> AudioQuality.LOSSLESS
        "exhigh" -> AudioQuality.EXHIGH
        "higher" -> AudioQuality.HIGHER
        else -> AudioQuality.STANDARD
    }
}

internal fun neteasePlaybackSource(value: JSONObject, requested: AudioQuality,
    inspect: (String, Map<String, String>) -> QqFlacInfo? = { url, headers ->
        readQqFlacInfo(url, timeoutMs = 4_000, headers = headers)
    }): PlaybackSource {
    val url = value.optString("url").asNeteasePlaybackUrl()
        ?: throw PlatformApiException("歌曲暂无可用播放地址")
    val requestHeaders = neteasePlaybackRequestHeaders()
    val format = value.optString("type").ifBlank { value.optString("encodeType") }.ifBlank { "audio" }
    val bitRate = value.optInt("br").coerceAtLeast(0)
    val sampleRate = value.optInt("sr")
    val bitDepth = value.optInt("bitDepth", value.optInt("bitdepth"))
    val info = if (format.equals("flac", true)) inspect(url, requestHeaders) ?: if (sampleRate > 0 && bitDepth in 1..32)
        QqFlacInfo(0, sampleRate, bitDepth) else null else null
    val actualQuality = neteaseAudioQuality(value.optString("level"), format, bitRate, info)
    val trial = value.has("freeTrialInfo") && !value.isNull("freeTrialInfo")
    Log.i(NETEASE_PLAYBACK_LOG_TAG,
        "source song=${value.optString("id")} requested=${requested.providerLevel} " +
            "returned=${value.optString("level")} actual=${actualQuality.providerLevel} format=$format " +
            "bitrate=$bitRate sampleRate=$sampleRate bitDepth=$bitDepth trial=$trial")
    return PlaybackSource(url, requested, actualQuality, bitRate, format, trial,
        actualFormat = info?.description, requestHeaders = requestHeaders)
}

internal const val NETEASE_PLAYBACK_LOG_TAG = "NeteasePlayback"
