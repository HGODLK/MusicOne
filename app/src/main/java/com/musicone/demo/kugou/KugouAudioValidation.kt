package com.musicone.demo

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlin.math.abs

internal data class KugouMediaInfo(
    val durationMs: Long,
    val mime: String,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
)

/** 酷狗换票成功不代表档位和完整时长正确，播放前以媒体本身再次确认。 */
internal object KugouAudioValidation {
    private val cache = object : LinkedHashMap<String, KugouMediaInfo>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KugouMediaInfo>?): Boolean = size > 32
    }

    private fun inspect(url: String, flacExpected: Boolean): KugouMediaInfo? {
        synchronized(cache) { cache[url] }?.let { return it }
        val flac = if (flacExpected) readQqFlacInfo(url)?.let {
            KugouMediaInfo(it.durationMs, "audio/flac", it.sampleRate, it.bitDepth)
        } else null
        val info = flac ?: runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(url, emptyMap())
                (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
                    val format = extractor.getTrackFormat(index)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/") && format.containsKey(MediaFormat.KEY_DURATION)) {
                        KugouMediaInfo(
                            durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1_000L,
                            mime = mime,
                            sampleRate = format.intValue(MediaFormat.KEY_SAMPLE_RATE),
                        )
                    } else null
                }
            } finally {
                extractor.release()
            }
        }.getOrNull() ?: return null
        synchronized(cache) { cache[url] = info }
        return info
    }

    fun verify(url: String, track: MusicTrack, quality: AudioQuality): KugouMediaInfo? {
        val info = inspect(url, quality == AudioQuality.LOSSLESS || quality == AudioQuality.HI_RES) ?: return null
        val valid = kugouMediaMatches(info, track.durationMs, quality)
        // 只记录歌曲标识和媒体规格，避免把签名地址或账号信息写入日志。
        Log.d(
            "KugouAudioValidation",
            "track=${track.id} expected=${track.durationMs} actual=${info.durationMs} " +
                "mime=${info.mime} sampleRate=${info.sampleRate} bitDepth=${info.bitDepth} valid=$valid",
        )
        return info.takeIf { valid }
    }
}

private fun MediaFormat.intValue(key: String): Int =
    if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

internal fun kugouMediaDurationMatches(actualMs: Long, expectedMs: Long): Boolean =
    expectedMs > 0L && actualMs > 0L && abs(actualMs - expectedMs) <= maxOf(2_500L, expectedMs / 40L)

internal fun kugouMediaMatches(info: KugouMediaInfo, expectedDurationMs: Long, quality: AudioQuality): Boolean {
    val formatMatches = when (quality) {
        AudioQuality.LOSSLESS, AudioQuality.HI_RES -> info.mime == "audio/flac"
        AudioQuality.STANDARD, AudioQuality.HIGHER, AudioQuality.EXHIGH ->
            info.mime == "audio/mpeg" || info.mime == "audio/ogg"
        AudioQuality.DOLBY -> false
    }
    return formatMatches && kugouMediaDurationMatches(info.durationMs, expectedDurationMs) &&
        (quality != AudioQuality.HI_RES || info.bitDepth > 16)
}

internal fun kugouPlaybackQuality(
    responseHash: String,
    playUrl: String,
    format: String,
    bitRate: Int,
    track: MusicTrack,
): AudioQuality? {
    val normalizedHash = responseHash.trim().lowercase()
    val normalizedUrl = playUrl.substringBefore('?').lowercase()
    val byHash = track.qualityIds.entries.firstOrNull { (_, hash) ->
        val value = hash.lowercase()
        value.isNotBlank() && (normalizedHash == value || normalizedUrl.contains(value))
    }?.key
    if (byHash != null) return byHash
    val extension = format.trim().lowercase().removePrefix("audio/")
        .ifBlank { normalizedUrl.substringAfterLast('.', "") }
    return when {
        extension.contains("flac") -> AudioQuality.LOSSLESS
        extension.contains("mp3") || extension.contains("mpeg") || extension.contains("ogg") ->
            if (bitRate >= 256_000 || bitRate in 256..999) AudioQuality.EXHIGH else AudioQuality.STANDARD
        else -> null
    }
}
