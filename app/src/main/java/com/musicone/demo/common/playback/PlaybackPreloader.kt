package com.musicone.demo

import android.content.Context
import androidx.media3.common.Player
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** 当前音源不限制预取目标，两首音频各自下载，歌词使用独立五首窗口。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackPreloader(private val context: Context, private val scope: CoroutineScope) {
    private data class Key(val source: MusicSource, val id: String, val namespace: String, val credential: String, val vip: Boolean, val quality: AudioQuality)
    private data class Ready(val playback: ResolvedPlayback, val createdMs: Long)
    private class Task(val prefetch: PlaybackAudioPrefetch, var job: Job? = null) {
        fun cancel() { job?.cancel(); prefetch.cancel() }
    }
    private val preferences = PlatformPreferences(context)
    private val biliSession = BiliSessionStore(context)
    private val qualities = PlaybackQualityPreferences(context)
    private val resolver = PlatformPlaybackResolver(context, qqRequestOrigin = QqRequestOrigin.PRELOAD)
    private val ready = ConcurrentHashMap<Key, Ready>()
    private val tasks = linkedMapOf<Key, Task>()
    private val draining = mutableListOf<Job>()
    private var playedId: String? = null
    @Volatile private var mayRequest = false
    private val gate = PlaybackPreloadGate(mayStart = { mayRequest })
    private val lyrics = PlaybackLyricsPreloader(context, scope, gate)

    fun observe(state: () -> MusicOneUiState, switching: () -> Boolean, player: () -> Player?) {
        scope.launch {
            while (isActive) {
                val snapshot = state()
                val currentId = snapshot.currentTrack?.id
                val active = currentId != null && snapshot.isPlaying && !snapshot.qualityChanging &&
                    !switching() && context.hasMusicNetwork()
                val transport = player()
                if (transport?.currentMediaItem?.mediaId == currentId && transport?.isPlaying == true) {
                    playedId = currentId
                }
                // 初次起播允许错峰并行取源；当前歌曲真正缓冲不足时先暂停后续请求。
                mayRequest = active && !(playedId == currentId &&
                    transport?.currentMediaItem?.mediaId == currentId &&
                    transport?.playbackState == Player.STATE_BUFFERING)
                if (!active) stop() else {
                    val targets = nextPlaybackPreloadTracks(snapshot, PRELOAD_AUDIO_SOURCES, 2)
                    reconcile(targets)
                    lyrics.update(nextPlaybackPreloadTracks(snapshot, PRELOAD_LYRIC_SOURCES, 5))
                }
                delay(500L)
            }
        }
    }

    private fun key(track: MusicTrack): Key {
        val quality = PlaybackOptions.effective(context, qualities.read(track.source), track.source).playbackEquivalent()
        if (track.source == MusicSource.BILIBILI) {
            val session = biliSession.read()
            return Key(track.source, track.id, "BILIBILI:${session?.mid ?: "guest"}", session?.cookie.orEmpty(), false, quality)
        }
        val session = preferences.readSession(track.source)
        return Key(track.source, track.id, session.cacheNamespace(), session.credential, session.account?.hasVipAccess == true, quality)
    }

    private fun reconcile(tracks: List<MusicTrack>) {
        val targets = tracks.associateBy(::key)
        tasks.keys.filter { it !in targets }.forEach { key ->
            tasks.remove(key)?.let { task -> task.cancel(); task.job?.let(draining::add) }
        }
        draining.removeAll { it.isCompleted }
        ready.keys.removeAll { it !in targets }
        for ((key, track) in targets) {
            if (key in tasks) continue
            if (ready[key]?.let { preloadedPlaybackFresh(it.createdMs, nowMs()) } == true) continue
            val task = Task(PlaybackAudioPrefetch(context))
            tasks[key] = task
            val previous = draining.toList()
            task.job = scope.launch(Dispatchers.IO) {
                try {
                    previous.joinAll()
                    val resolved = gate.request {
                        ensureActive()
                        if (key(track) != key) throw CancellationException("预加载目标配置已变化")
                        resolver.resolvePreferred(track, key.quality)
                    }
                    ensureActive()
                    if (resolved.source.trial || key(track) != key) return@launch
                    // 先交付可用地址，下载可继续；切歌取消写入不会丢失已取得的音源。
                    ready[key] = Ready(resolved, nowMs())
                    task.prefetch.cache(resolved)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 失败保留已写缓存，切歌时正常解析兜底，不反复重试。 */ }
            }
        }
    }

    /** 停止后台请求及写入，保留最多两份结果供本次切歌交接。 */
    fun stop() {
        mayRequest = false
        tasks.values.forEach { task -> task.cancel(); task.job?.let(draining::add) }
        draining.removeAll { it.isCompleted }
        tasks.clear()
        lyrics.stop()
    }

    fun take(track: MusicTrack, quality: AudioQuality): ResolvedPlayback? {
        val key = key(track)
        if (key.quality != quality) return null
        val saved = ready.remove(key) ?: return null
        if (!preloadedPlaybackFresh(saved.createdMs, nowMs()) ||
            !DolbyPlaybackPolicy.permits(saved.playback.source.actualQuality) ||
            !context.hasMusicNetwork() && offlinePlaybackKey(saved.playback.source.url) == null) return null
        val prepared = saved.playback.track
        // 当前队列的歌词和显示状态优先，只补充B站取源需要的真实分P和时长。
        val merged = track.copy(previewUrl = saved.playback.source.url,
            bilibiliBvid = track.bilibiliBvid.ifBlank { prepared.bilibiliBvid },
            bilibiliCid = track.bilibiliCid.takeIf { it > 0L } ?: prepared.bilibiliCid,
            durationMs = track.durationMs.takeIf { it > 0L } ?: prepared.durationMs)
        return saved.playback.copy(track = merged)
    }

    private fun nowMs() = System.nanoTime() / 1_000_000L
}
