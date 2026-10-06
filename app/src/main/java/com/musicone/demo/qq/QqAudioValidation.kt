package com.musicone.demo

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlin.math.abs

/** 对带重定向参数的已签发音源读取媒体时长，参数本身不能证明它是试听。 */
internal object QqAudioValidation {
    private data class MediaInfo(val duration: Long, val mime: String)
    private val durations = object : LinkedHashMap<String, MediaInfo>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaInfo>?): Boolean = size > 32
    }

    fun isFullLength(url: String, track: MusicTrack): Boolean {
        val cached = synchronized(durations) { durations[url] }
        val flac = if (cached == null && qqPlaybackQuality(url) in listOf(AudioQuality.LOSSLESS, AudioQuality.HI_RES)) {
            readQqFlacDuration(url)?.let { MediaInfo(it, "audio/flac") }
        } else null
        val info = cached ?: flac ?: runCatching {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(url, emptyMap())
                (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
                    val format = extractor.getTrackFormat(index)
                    if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true &&
                        format.containsKey(MediaFormat.KEY_DURATION)) {
                        MediaInfo(format.getLong(MediaFormat.KEY_DURATION) / 1_000L,
                            format.getString(MediaFormat.KEY_MIME).orEmpty())
                    } else null
                }
            } finally {
                extractor.release()
            }
        }.getOrNull() ?: return false
        synchronized(durations) { durations[url] = info }
        val formatMatches = when (qqPlaybackQuality(url)) {
            AudioQuality.LOSSLESS, AudioQuality.HI_RES -> info.mime == "audio/flac"
            AudioQuality.STANDARD, AudioQuality.HIGHER, AudioQuality.EXHIGH -> info.mime == "audio/mpeg"
            AudioQuality.DOLBY -> info.mime == "audio/eac3" || info.mime == "audio/eac3-joc"
            else -> false
        }
        val full = formatMatches && qqMediaDurationMatches(info.duration, track.durationMs)
        // 仅记录歌曲标识和时长，不输出签名地址或账号凭证。
        Log.d("QqAudioValidation", "track=${track.id} expected=${track.durationMs} actual=${info.duration} mime=${info.mime} full=$full")
        return full
    }
}

internal fun qqMediaDurationMatches(actualMs: Long, expectedMs: Long): Boolean =
    expectedMs > 0L && actualMs > 0L && abs(actualMs - expectedMs) <= maxOf(2_000L, expectedMs / 50L)
