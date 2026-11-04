package com.musicone

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class PlaybackLyricSession(val namespace: String, val revision: String)

/** 前台和预取共用同一缓存与账号边界，宿主不接触包的认证凭据。 */
internal class PlaybackLyricRequest(
    private val session: (MusicTrack) -> PlaybackLyricSession,
    private val readCached: suspend (MusicTrack, String) -> List<TimedLyric>,
    private val readFallback: suspend (MusicTrack, String) -> List<TimedLyric>,
    private val fetch: suspend (MusicTrack) -> List<TimedLyric>,
    private val save: suspend (MusicTrack, List<TimedLyric>, String) -> Unit,
    private val online: () -> Boolean,
    private val reusable: (MusicTrack, List<TimedLyric>) -> Boolean = ::hasReusableLyrics,
    private val upgrade: (MusicTrack, List<TimedLyric>) -> Boolean = ::shouldUpgradeLyricCacheToVerbatim,
) {
    suspend fun load(track: MusicTrack, expectedNamespace: String? = null): List<TimedLyric> {
        val captured = session(track)
        fun current() = session(track) == captured
        if (expectedNamespace != null && expectedNamespace != captured.namespace) return emptyList()
        currentCoroutineContext().ensureActive()
        val cached = readCached(track, captured.namespace)
        currentCoroutineContext().ensureActive()
        if (!current()) return emptyList()
        if (reusable(track, cached) && !upgrade(track, cached)) return cached
        if (reusable(track, track.lyrics) && !upgrade(track, track.lyrics)) {
            save(track, track.lyrics, captured.namespace)
            return track.lyrics
        }
        val fallback = cached.ifEmpty { readFallback(track, captured.namespace) }
            .ifEmpty { track.lyrics.takeIf(::validLyricTimeline).orEmpty() }
        currentCoroutineContext().ensureActive()
        if (!current()) return emptyList()
        if (!online()) {
            if (fallback.isEmpty()) throw java.io.IOException("网络不可用，歌词可稍后重试")
            return fallback
        }
        val fetched = fetchLyricsPreservingFallback(track, fallback) { fetch(track) }
        currentCoroutineContext().ensureActive()
        if (!current()) return emptyList()
        if (fetched.isNotEmpty() && validLyricTimeline(fetched)) {
            save(track, fetched, captured.namespace)
            return fetched
        }
        return fallback
    }
}
