package com.musicone.demo

import kotlinx.coroutines.CancellationException

internal data class QqQualitySpec(val prefix: String, val extension: String)

/** 杜比文件可能使用独立 MID，不能用普通 FLAC/MP3 的文件标识覆盖它。 */
internal fun MusicTrack.qqPlaybackMediaMid(quality: AudioQuality): String =
    if (quality == AudioQuality.DOLBY) qualityIds[quality]?.takeIf(String::isNotBlank) ?: mediaId else mediaId

/** 普通杜比入口优先使用当前移动端规格，旧规格仅作为同一档位的兼容后备。 */
internal fun qqQualitySpecs(quality: AudioQuality): List<QqQualitySpec> = when (quality) {
    AudioQuality.DOLBY -> listOf(
        QqQualitySpec("D008", "mp4"), QqQualitySpec("D009", "mp4"), QqQualitySpec("D004", "mp4"),
    )
    AudioQuality.STANDARD -> listOf(QqQualitySpec("M500", "mp3"))
    AudioQuality.HIGHER -> listOf(QqQualitySpec("C600", "m4a"))
    AudioQuality.EXHIGH -> listOf(QqQualitySpec("M800", "mp3"))
    AudioQuality.LOSSLESS -> listOf(QqQualitySpec("F000", "flac"))
    AudioQuality.HI_RES -> listOf(QqQualitySpec("RS01", "flac"))
}

/** 前缀不存在或媒体不符时尝试兼容规格，账号失效与风控必须立即终止。 */
internal suspend fun resolveQqPlaybackVariant(
    quality: AudioQuality,
    resolve: suspend (QqQualitySpec) -> PlaybackSource,
): PlaybackSource {
    var failure: Exception? = null
    for (spec in qqQualitySpecs(quality)) {
        try {
            return resolve(spec)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error.stopsPlaybackFallback()) throw error
            failure = error
            if (quality == AudioQuality.DOLBY) qqDiagnostic("杜比规格不可用：prefix=${spec.prefix}，error=${error::class.java.simpleName}")
        }
    }
    throw checkNotNull(failure)
}
