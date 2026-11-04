package com.musicone

import android.content.Context
import com.musicone.sourceintegration.*
import kotlinx.coroutines.CoroutineScope

/** 歌词窗口与音频窗口独立，实际缓存及换号边界复用通用歌词请求。 */
internal class PlaybackLyricsPreloader(private val context: Context, scope: CoroutineScope,
    private val gate: PlaybackPreloadGate) {
    private val resolver = PlatformPlaybackResolver(context, requestOrigin = PlaybackRequestOrigin.PRELOAD)
    private val cache = CachedLyricsStore(context)
    private val window = PlaybackLyricWindow(scope, prepare@{ track, namespace ->
        val revision = sourceAccountState(context, track.source)?.revision
        fun current() = sourceCacheNamespace(context, track.source) == namespace &&
            sourceAccountState(context, track.source)?.revision == revision
        if (!current()) return@prepare
        val cached = cache.read(track, namespace)
        if (!current()) return@prepare
        if (hasReusableLyrics(track, cached) && !shouldUpgradeLyricCacheToVerbatim(track, cached)) return@prepare
        if (hasReusableLyrics(track, track.lyrics) && !shouldUpgradeLyricCacheToVerbatim(track, track.lyrics)) {
            cache.save(track, track.lyrics, namespace)
            return@prepare
        }
        gate.request<Unit> {
            if (sourceCacheNamespace(context, track.source) == namespace &&
                sourceAccountState(context, track.source)?.revision == revision) resolver.lyrics(track)
        }
    })
    fun update(tracks: List<MusicTrack>) = window.update(tracks.filter { track ->
        track.source == MusicSource.LOCAL || !SourcePackageController.get(context).startup.value.initializing
    }.map { track ->
        PlaybackLyricTarget(track, sourceCacheNamespace(context, track.source),
            sourceAccountState(context, track.source)?.revision?.toString().orEmpty())
    })
    fun stop() = window.stop()
}
