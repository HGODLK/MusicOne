package com.musicone

import com.musicone.source.sdk.SourceLyricTiming
import com.musicone.source.sdk.SourcePresentation
import com.musicone.sourceintegration.SourcePackageController
import com.musicone.sourceintegration.toSourceTrack

internal const val LYRIC_DISPLAY_LEAD_MS = 0L
internal const val LYRIC_SEEK_PREROLL_MS = 350L
private val sourceTimingCache = SourceLyricTimingCache()

/** 音源逐曲声明优先；没有声明或包未加载时严格采用零提前量。 */
internal fun declaredLyricTiming(presentation: SourcePresentation? = null,
    trackTiming: SourceLyricTiming? = null, libraryTimeline: Boolean = false): SourceLyricTiming =
    if (libraryTimeline) SourceLyricTiming() else trackTiming ?: SourceLyricTiming(
    lineLeadMs = presentation?.lineLeadMs ?: 0L,
    wordLeadMs = presentation?.wordLeadMs ?: 0L,
)

internal fun sourceLyricTiming(track: MusicTrack): SourceLyricTiming {
    val entry = SourcePackageController.current()?.running(track.source.id)?.entry
    return runCatching {
        sourceTimingCache.read(track, entry) {
            if (track.lyrics.any { it.libraryTimeline }) declaredLyricTiming(libraryTimeline = true)
            else declaredLyricTiming(entry?.descriptor?.presentation,
                entry?.lyricProvider?.timing(track.toSourceTrack()))
        }
    }.getOrElse { declaredLyricTiming(entry?.descriptor?.presentation) }
}

// 保留源别入口供无需逐曲资料的调用使用，不再在宿主维护任何平台时间表。
internal fun lyricDisplayPosition(progressMs: Long, source: MusicSource? = null): Long {
    val presentation = source?.let { SourcePackageController.current()?.running(it.id)?.entry?.descriptor?.presentation }
    return lyricDisplayPosition(progressMs, declaredLyricTiming(presentation))
}

internal fun lyricWordDisplayPosition(progressMs: Long, source: MusicSource?): Long {
    val presentation = source?.let { SourcePackageController.current()?.running(it.id)?.entry?.descriptor?.presentation }
    return lyricWordDisplayPosition(progressMs, declaredLyricTiming(presentation))
}

internal fun lyricDisplayPosition(progressMs: Long, timing: SourceLyricTiming): Long =
    (progressMs + timing.lineLeadMs).coerceAtLeast(0L)

/** 逐字时钟独立消费逐字提前量，不叠加整行提前量或用户整行偏移。 */
internal fun lyricWordDisplayPosition(progressMs: Long, timing: SourceLyricTiming): Long =
    (progressMs + timing.wordLeadMs).coerceAtLeast(0L)

internal fun lyricSeekFraction(timeMs: Long, durationMs: Long): Float =
    ((timeMs - LYRIC_SEEK_PREROLL_MS).coerceAtLeast(0L).toFloat() / durationMs.coerceAtLeast(1L))
        .coerceIn(0f, 1f)
