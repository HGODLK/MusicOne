package com.musicone

import kotlinx.coroutines.CancellationException

/** 在线拉歌词失败时退回旧歌词兜底；协程取消直接抛出，不当成失败降级。 */
internal suspend fun fetchLyricsPreservingFallback(track: MusicTrack, fallback: List<TimedLyric>,
    preserveVerbatimFallback: Boolean = track.source.lyricBehavior?.preserveVerbatimFallback == true,
    fetch: suspend () -> List<TimedLyric>): List<TimedLyric> = try {
    val fetched = fetch()
    if (preserveVerbatimFallback && fallback.any { it.words.isNotEmpty() } &&
        fetched.none { it.words.isNotEmpty() }) fallback else fetched
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    if (fallback.isEmpty()) throw error
    lyricDiagnostic(track, "网络失败，保留已有歌词", fallback, error.javaClass.simpleName)
    fallback
}
