package com.musicone

private fun lyricProvider(track: MusicTrack) = com.musicone.sourceintegration.SourcePackageController.current()?.running(track.source.id)?.entry?.lyricProvider

internal fun hasReusableLyrics(track: MusicTrack, lyrics: List<TimedLyric>): Boolean {
    val version = lyricProvider(track)?.parserVersion ?: 0
    return hasReusableLyrics(lyrics, version)
}
/** 音源包没声明歌词解析器版本时直接复用旧缓存，声明了就按版本号校验是否过期。 */
internal fun hasReusableLyrics(lyrics: List<TimedLyric>, parserVersion: Int): Boolean =
    lyrics.isNotEmpty() && validLyricTimeline(lyrics) &&
        (parserVersion == 0 || lyrics.all { it.parserVersion == parserVersion })

internal fun needsVerbatimLyricsRefresh(lyrics: List<TimedLyric>, parserVersion: Int,
    refreshPlainLyricsForVerbatim: Boolean): Boolean =
    !hasReusableLyrics(lyrics, parserVersion) ||
        (refreshPlainLyricsForVerbatim && lyrics.none { it.words.isNotEmpty() })
internal fun validLyricTimeline(lyrics: List<TimedLyric>): Boolean = lyrics.all { line ->
    line.timeMs >= 0L && line.text.isNotBlank() && line.words.all { it.startMs >= 0L && it.endMs >= it.startMs }
} && lyrics.zipWithNext().all { (before, after) -> before.timeMs <= after.timeMs }

internal fun shouldUpgradeLyricCacheToVerbatim(track: MusicTrack, cached: List<TimedLyric>,
    parserVersion: Int = lyricProvider(track)?.parserVersion ?: 0,
    refreshPlainLyricsForVerbatim: Boolean = lyricProvider(track)?.refreshPlainLyricsForVerbatim == true,
): Boolean =
    ExperiencePreferences.options.verbatimLyricAnimation && cached.isNotEmpty() &&
        needsVerbatimLyricsRefresh(cached, parserVersion, refreshPlainLyricsForVerbatim)
