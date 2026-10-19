package com.musicone.demo

internal const val LYRIC_DISPLAY_LEAD_MS = 0L
internal const val NETEASE_LYRIC_DISPLAY_LEAD_MS = 380L
internal const val LYRIC_SEEK_PREROLL_MS = 350L

internal fun lyricDisplayPosition(progressMs: Long, source: MusicSource? = null): Long =
    (progressMs + when (source) {
        MusicSource.NETEASE -> NETEASE_LYRIC_DISPLAY_LEAD_MS
        MusicSource.QQ -> 320L
        MusicSource.KUGOU -> 185L
        else -> LYRIC_DISPLAY_LEAD_MS
    }).coerceAtLeast(0L)

/** 逐字提前量独立于整行寻焦，不叠加整行提前量。 */
internal fun lyricWordDisplayPosition(progressMs: Long, source: MusicSource?): Long =
    (progressMs + if (source == MusicSource.QQ || source == MusicSource.KUGOU) 125L else 0L).coerceAtLeast(0L)

internal fun lyricSeekFraction(timeMs: Long, durationMs: Long): Float =
    ((timeMs - LYRIC_SEEK_PREROLL_MS).coerceAtLeast(0L).toFloat() / durationMs.coerceAtLeast(1L))
        .coerceIn(0f, 1f)
