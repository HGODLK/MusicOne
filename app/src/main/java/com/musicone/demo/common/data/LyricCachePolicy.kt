package com.musicone.demo

internal const val CURRENT_LYRIC_PARSER_VERSION = 2

internal fun hasReusableLyrics(track: MusicTrack, lyrics: List<TimedLyric>): Boolean =
    lyrics.isNotEmpty() && validLyricTimeline(lyrics) &&
        (track.source != MusicSource.QQ || lyrics.all { it.parserVersion == CURRENT_LYRIC_PARSER_VERSION })

internal fun validLyricTimeline(lyrics: List<TimedLyric>): Boolean =
    lyrics.all { line ->
        line.timeMs >= 0L && line.text.isNotBlank() && line.words.all {
            it.startMs >= 0L && it.endMs >= it.startMs
        }
    } && lyrics.zipWithNext().all { (before, after) -> before.timeMs <= after.timeMs }

internal fun shouldUpgradeLyricCacheToVerbatim(track: MusicTrack, cached: List<TimedLyric>): Boolean =
    track.source == MusicSource.QQ && ExperiencePreferences.options.verbatimLyricAnimation &&
        cached.isNotEmpty() && cached.none { it.words.isNotEmpty() }

internal fun List<TimedLyric>.confirmedByCurrentQqParser(): List<TimedLyric> =
    map { it.copy(parserVersion = CURRENT_LYRIC_PARSER_VERSION) }
