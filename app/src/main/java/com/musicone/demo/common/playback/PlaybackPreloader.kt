package com.musicone.demo

import android.content.Context
import androidx.media3.common.Player
import kotlinx.coroutines.*

/** 当前音源不限制预取目标，两首音频各自下载，歌词使用独立五首窗口。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackPreloader(private val context: Context, private val scope: CoroutineScope) {
    private data class Key(val source: MusicSource, val id: String, val namespace: String, val credential: String, val vip: Boolean, val quality: AudioQuality)
    private class Task(val prefetch: PlaybackAudioPrefetch, var job: Job? = null, var downloading: Boolean = false) {
        fun cancel() { job?.cancel(); prefetch.cancel() }
    }
    private val preferences = PlatformPreferences(context)
    private val biliSession = BiliSessionStore(context)
    private val qualities = PlaybackQualityPreferences(context)
    private val resolver = PlatformPlaybackResolver(context, qqRequestOrigin = QqRequestOrigin.PRELOAD)
    private val entries = PlaybackPreloadEntries<Key, ResolvedPlayback>(::preloadedPlaybackFresh, ::preloadedPlaybackRefreshDue)
    private val tasks = linkedMapOf<Key, Task>()
    private val draining = mutableListOf<Job>()
    private var anchor: Pair<MusicSource, String>? = null
    @Volatile private var mayRequest = false
    private val gate = PlaybackPreloadGate(mayStart = { mayRequest })
    private val lyrics = PlaybackLyricsPreloader(context, scope, gate)

    fun observe(state: () -> MusicOneUiState, switching: () -> Boolean, player: () -> Player?) {
        scope.launch {
            while (isActive) {
                val snapshot = state()
                val current = snapshot.currentTrack
                val transport = player()
                val matches = current != null && transport?.currentMediaItem?.mediaId == current.id
                val active = current != null && snapshot.isPlaying && !snapshot.qualityChanging &&
                    !switching() && context.hasMusicNetwork()
                val duration = transport?.duration?.takeIf { it > 0L } ?: current?.durationMs?.takeIf { it > 0L }
                val remaining = duration?.let { (it - (transport?.currentPosition ?: 0L)).coerceAtLeast(0L) }
                val buffered = transport?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0L) } ?: 0L
                val allowed = active && matches && playbackPreloadCanRun(
                    transport.isPlaying, buffered, remaining, mayRequest, local = current.source == MusicSource.LOCAL)
                if (!allowed) stop() else {
                    mayRequest = true
                    val nextAnchor = current.source to current.id
                    val allowRefresh = anchor != nextAnchor || playbackPreloadNearEnd(remaining)
                    anchor = nextAnchor
                    reconcile(nextPlaybackPreloadTracks(snapshot, PRELOAD_AUDIO_SOURCES, 2), allowRefresh)
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

    private fun reconcile(tracks: List<MusicTrack>, allowRefresh: Boolean) {
        val targets = tracks.associateBy(::key)
        tasks.keys.filter { it !in targets }.forEach { key ->
            tasks.remove(key)?.let { task -> task.cancel(); task.job?.let(draining::add) }
        }
        tasks.entries.removeAll { it.value.job?.isCompleted == true }
        draining.removeAll { it.isCompleted }
        entries.retain(targets.keys)
        for ((key, track) in targets) {
            val existing = tasks[key]
            if (existing != null && (!existing.downloading || !entries.needsRefresh(key, nowMs(), allowRefresh))) continue
            val work = entries.work(key, nowMs(), allowRefresh)
            if (work == PlaybackPreloadEntries.Work.NONE) continue
            if (existing != null) {
                existing.cancel()
                existing.job?.let(draining::add)
                tasks.remove(key)
            }
            val task = Task(PlaybackAudioPrefetch(context))
            tasks[key] = task
            val previous = draining.toList()
            task.job = scope.launch {
                try {
                    previous.joinAll()
                    val resolved = if (work == PlaybackPreloadEntries.Work.RESOLVE) {
                        val prepared = withContext(Dispatchers.IO) {
                            gate.request {
                                ensureActive()
                                if (key(track) != key) throw CancellationException("预加载目标配置已变化")
                                withTimeout(15_000L) { resolver.resolvePreferred(track, key.quality) }
                            }
                        }
                        ensureActive()
                        if (prepared.source.trial || key(track) != key) {
                            entries.failed(key, nowMs(), retryable = false)
                            return@launch
                        }
                        // 地址先交付，下载中断后仍可复用；所有交接状态只在运行时作用域更新。
                        entries.resolved(key, prepared, nowMs())
                        PlaybackPreloadDiagnostics.stage("取源就绪", track)
                        prepared
                    } else entries.peek(key, nowMs()) ?: return@launch
                    task.downloading = true
                    val cached = withContext(Dispatchers.IO) {
                        task.prefetch.cache(resolved, mayContinue = { mayRequest })
                    }
                    ensureActive()
                    if (cached) entries.downloaded(key) else entries.blocked(key, nowMs())
                    PlaybackPreloadDiagnostics.stage(if (cached) "片段就绪" else "缓存受限，仅地址就绪", track)
                } catch (timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    entries.failed(key, nowMs(), retryable = true)
                    PlaybackPreloadDiagnostics.stage("取源超时", track)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    entries.failed(key, nowMs(), retryable =
                        error !is TrackUnavailableException && !error.stopsPlaybackFallback())
                    PlaybackPreloadDiagnostics.stage("后台失败", track, error.javaClass.simpleName)
                }
            }
        }
    }

    /** 停止后台请求及写入，保留窗口中的片段进度、有限重试预算和切歌结果。 */
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
        val saved = entries.take(key, nowMs()) ?: run {
            PlaybackPreloadDiagnostics.stage("未命中新鲜地址", track)
            return null
        }
        if (!DolbyPlaybackPolicy.permits(saved.source.actualQuality) ||
            !context.hasMusicNetwork() && offlinePlaybackKey(saved.source.url) == null) return null
        val prepared = saved.track
        // 当前队列的歌词和显示状态优先，只补充B站取源需要的真实分P和时长。
        val merged = track.copy(previewUrl = saved.source.url,
            bilibiliBvid = track.bilibiliBvid.ifBlank { prepared.bilibiliBvid },
            bilibiliCid = track.bilibiliCid.takeIf { it > 0L } ?: prepared.bilibiliCid,
            durationMs = track.durationMs.takeIf { it > 0L } ?: prepared.durationMs)
        PlaybackPreloadDiagnostics.stage("切歌命中", track)
        return saved.copy(track = merged)
    }

    private fun nowMs() = System.nanoTime() / 1_000_000L
}
