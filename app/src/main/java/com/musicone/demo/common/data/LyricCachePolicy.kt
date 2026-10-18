package com.musicone.demo

internal const val CURRENT_LYRIC_PARSER_VERSION = 2

internal fun hasReusableLyrics(track: MusicTrack, lyrics: List<TimedLyric>): Boolean =
    lyrics.isNotEmpty() && validLyricTimeline(lyrics) &&
        when (track.source) {
            MusicSource.QQ -> lyrics.all { it.parserVersion == CURRENT_LYRIC_PARSER_VERSION }
            MusicSource.NETEASE -> lyrics.all { it.parserVersion == CURRENT_NETEASE_LYRIC_PARSER_VERSION }
            MusicSource.KUGOU -> lyrics.all { it.parserVersion == CURRENT_KUGOU_LYRIC_PARSER_VERSION }
            else -> true
        }

internal fun validLyricTimeline(lyrics: List<TimedLyric>): Boolean =
    lyrics.all { line ->
        line.timeMs >= 0L && line.text.isNotBlank() && line.words.all {
            it.startMs >= 0L && it.endMs >= it.startMs
        }
    } && lyrics.zipWithNext().all { (before, after) -> before.timeMs <= after.timeMs }

internal fun shouldUpgradeLyricCacheToVerbatim(track: MusicTrack, cached: List<TimedLyric>): Boolean =
    ExperiencePreferences.options.verbatimLyricAnimation && cached.isNotEmpty() && when (track.source) {
        MusicSource.QQ -> cached.none { it.words.isNotEmpty() }
        MusicSource.NETEASE -> !hasReusableLyrics(track, cached)
        MusicSource.KUGOU -> !hasReusableLyrics(track, cached)
        else -> false
    }

internal fun List<TimedLyric>.confirmedByCurrentQqParser(): List<TimedLyric> =
    map { it.copy(parserVersion = CURRENT_LYRIC_PARSER_VERSION) }
