package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 歌词独立于两首音频窗口；QQ保留静默验证通道，B站和本地不发起在线歌词请求。 */
internal class PlaybackLyricsPreloader(
    context: Context,
    private val scope: CoroutineScope,
    private val gate: PlaybackPreloadGate,
) {
    private val preferences = PlatformPreferences(context)
    private val cache = CachedLyricsStore(context)
    private val qq = QqNextLyricsPreloader(context)
    private val resolver = PlatformPlaybackResolver(context, qqRequestOrigin = QqRequestOrigin.PRELOAD)
    private data class Key(val source: MusicSource, val id: String, val namespace: String, val credential: String)
    private var keys = emptyList<Key>()
    private var job: Job? = null

    fun update(tracks: List<MusicTrack>) {
        val next = tracks.map { track ->
            val session = preferences.readSession(track.source)
            Key(track.source, track.id, session.cacheNamespace(), session.credential)
        }
        if (next == keys) return
        stop()
        keys = next
        job = scope.launch(Dispatchers.IO) {
            for ((index, track) in tracks.withIndex()) {
                val namespace = next[index].namespace
                try {
                    ensureActive()
                    if (!current(next[index])) continue
                    val cached = cache.read(track, namespace)
                    if (hasReusableLyrics(track, cached) && !shouldUpgradeLyricCacheToVerbatim(track, cached)) continue
                    if (hasReusableLyrics(track, track.lyrics) && !shouldUpgradeLyricCacheToVerbatim(track, track.lyrics)) {
                        cache.save(track, track.lyrics, namespace)
                        continue
                    }
                    gate.request {
                        ensureActive()
                        if (current(next[index])) {
                            if (track.source == MusicSource.QQ) qq.preload(track, namespace)
                            else resolver.lyrics(track)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 单首失败不影响后续歌曲或当前播放。 */ }
            }
        }
    }

    private fun current(key: Key): Boolean {
        val session = preferences.readSession(key.source)
        return session.cacheNamespace() == key.namespace && session.credential == key.credential
    }

    fun stop() { job?.cancel(); job = null; keys = emptyList() }
}
