package com.musicone.demo

import android.util.Log

internal fun lyricDiagnostic(track: MusicTrack, event: String, lyrics: List<TimedLyric> = emptyList(), detail: String = "") {
    runCatching {
        Log.i("MusicLyrics", "事件=$event，曲目=${track.id}，MID=${track.songMid}，行数=${lyrics.size}，" +
            "逐字行=${lyrics.count { it.words.isNotEmpty() }}，末句=${lyrics.lastOrNull()?.timeMs ?: -1L}，" +
            "版本=${lyrics.firstOrNull()?.parserVersion ?: 0}，逐字开关=${ExperiencePreferences.options.verbatimLyricAnimation}，$detail")
    }
}
