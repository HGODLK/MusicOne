package com.musicone

import kotlinx.coroutines.CancellationException

/** 联网刷新失败仍交付旧歌词；取消必须继续传播，不能伪装成成功的降级结果。 */
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
