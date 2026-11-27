package com.musicone.source.qq.internal

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import kotlin.math.abs

/** 实际读取音频头里的时长来判断是不是完整歌曲（不能光看 URL 参数猜是不是试听）。 */
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
        val info = cached ?: flac ?: runCatchingCancellable {
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
        }.onFailure { error ->
            if (qqPlaybackQuality(url) == AudioQuality.DOLBY) {
                qqDiagnostic("杜比媒体读取失败：track=${track.id}，error=${error::class.java.simpleName}")
            }
        }.getOrNull() ?: return false
        synchronized(durations) { durations[url] = info }
        val formatMatches = when (qqPlaybackQuality(url)) {
            AudioQuality.LOSSLESS, AudioQuality.HI_RES -> info.mime == "audio/flac"
            AudioQuality.STANDARD, AudioQuality.HIGHER, AudioQuality.EXHIGH -> info.mime == "audio/mpeg"
            AudioQuality.DOLBY -> isDolbyAudioMime(info.mime)
            else -> false
        }
        val full = formatMatches && qqMediaDurationMatches(info.duration, track.durationMs)
        // 日志只打歌曲 ID 和时长，不打印带签名的 URL 和账号信息
        Log.d("QqAudioValidation", "track=${track.id} expected=${track.durationMs} actual=${info.duration} mime=${info.mime} full=$full")
        if (qqPlaybackQuality(url) == AudioQuality.DOLBY) {
            qqDiagnostic("杜比媒体验证：track=${track.id} expected=${track.durationMs} actual=${info.duration} mime=${info.mime} full=$full")
        }
        return full
    }
}

internal fun qqMediaDurationMatches(actualMs: Long, expectedMs: Long): Boolean =
    expectedMs > 0L && actualMs > 0L && abs(actualMs - expectedMs) <= maxOf(2_000L, expectedMs / 50L)
