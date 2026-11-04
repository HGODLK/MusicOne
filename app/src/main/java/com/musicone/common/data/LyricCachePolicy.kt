package com.musicone

private fun lyricProvider(track: MusicTrack) = com.musicone.sourceintegration.SourcePackageController.current()?.running(track.source.id)?.entry?.lyricProvider

internal fun hasReusableLyrics(track: MusicTrack, lyrics: List<TimedLyric>): Boolean {
    val version = lyricProvider(track)?.parserVersion ?: 0
    return hasReusableLyrics(lyrics, version)
}
/** 未声明解析版本时允许读取旧缓存；已加载的包按自身版本验证。 */
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
