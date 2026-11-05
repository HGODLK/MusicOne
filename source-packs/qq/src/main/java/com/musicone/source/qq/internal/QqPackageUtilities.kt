package com.musicone.source.qq.internal

import android.util.Log
internal const val CURRENT_LYRIC_PARSER_VERSION = 4

internal fun isDolbyAudioMime(mime: String?): Boolean = mime?.lowercase() in setOf("audio/eac3-joc", "audio/eac3", "audio/ac4")
internal inline fun <T> playbackTrace(name: String, block: () -> T): T = block()
internal fun lyricDiagnostic(track: MusicTrack, event: String, lyrics: List<TimedLyric> = emptyList(), detail: String = "") {
    Log.d("QqLyrics", "事件=$event，曲目=${track.id}，行数=${lyrics.size}")
}
internal fun List<TimedLyric>.confirmedByCurrentQqParser(): List<TimedLyric> = map { it.copy(parserVersion = CURRENT_LYRIC_PARSER_VERSION) }
