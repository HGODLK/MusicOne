package com.musicone.demo

import android.content.Context
import androidx.media3.common.Player
import kotlinx.coroutines.*

/**
 * 后台预加载：当前歌起播后按 0s／2s／5s 探测后续五首，
 * 默认在 +280ms／+2s 下载前两首；三次带宽不足卡顿后改为稳定播放 +3s／+10s。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackPreloader(private val context: Context, private val scope: CoroutineScope) {
    private data class Key(
        val source: MusicSource,
        val id: String,
        val namespace: String,
        val credential: String,
        val vip: Boolean,
    )

    private class Task(
        val track: MusicTrack,
        val key: Key,
        val roleIndex: Int,
        val probeDelayMs: Long,
        val prefetch: PlaybackAudioPrefetch?,
    ) {
        var probeJob: Job? = null
        var downloadJob: Job? = null
        @Volatile var probeFinished: Boolean = false
        @Volatile var resolved: ResolvedPlayback? = null
        @Volatile var downloaded: Boolean = false

        fun cancel() {
            probeJob?.cancel()
            downloadJob?.cancel()
            prefetch?.cancel()
        }

        fun cancelDownload() {
            downloadJob?.cancel()
            downloadJob = null
            prefetch?.cancel()
        }
    }

    private val preferences = PlatformPreferences(context)
    private val biliSession = BiliSessionStore(context)
    private val qualities = PlaybackQualityPreferences(context)
    private val resolver = PlatformPlaybackResolver(context, qqRequestOrigin = QqRequestOrigin.PRELOAD)
    private val qualityCache = PlaybackQualityCache(context)
    private val entries = PlaybackPreloadEntries<Key, ResolvedPlayback>(
        ttlMs = { key -> playbackTicketTtlMs(key.source) },
        refreshLeadMs = PLAYBACK_TICKET_REFRESH_MARGIN_MS,
    )
    private val tasks = linkedMapOf<Key, Task>()
    private val compared = mutableSetOf<Key>()
    private var currentKey: Pair<MusicSource, String>? = null
    private val timing = PlaybackPreloadTiming()
    private var monitor: PlaybackPreloadMonitor? = null
    private var observeJob: Job? = null
    @Volatile private var probeGateUntilMs = 0L
    @Volatile private var mayProbe = false
    @Volatile private var mayDownload = false
    private val gate = PlaybackPreloadGate(mayStart = { mayProbe })
    private val lyrics = PlaybackLyricsPreloader(context, scope, gate)

    fun observe(state: () -> MusicOneUiState, switching: () -> Boolean, player: () -> Player?) {
        if (observeJob != null) return
        val feedback = PlaybackPreloadMonitor(timing, state, switching, context::hasMusicNetwork)
        monitor = feedback
        observeJob = scope.launch {
            try {
                while (isActive) {
                    feedback.refresh()
                    val snapshot = state()
                    val current = snapshot.currentTrack
                    val transport = player()
                    val matches = current != null && transport?.currentMediaItem?.mediaId == current.id
                    val playing = transport != null && matches && transport.isPlaying
                    val active = current != null && snapshot.isPlaying && !snapshot.qualityChanging &&
                        !switching() && context.hasMusicNetwork()
                    val canProbe = active && playing && playbackPreloadCanProbe(playing)
                    val buffered = transport?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0L) } ?: 0L
                    val local = current?.source == MusicSource.LOCAL
                    val canDownload = canProbe && playbackPreloadCanDownload(playing, buffered, local)
                    mayProbe = canProbe
                    mayDownload = canDownload
                    if (!canProbe) {
                        stop()
                    } else {
                        val identity = current.source to current.id
                        if (identity != currentKey) {
                            currentKey = identity
                            compared.clear()
                            probeGateUntilMs = 0L
                            clearTasks()
                        }
                        if (!canDownload) cancelDownloads()
                        val elapsed = timing.elapsedMs(nowMs())
                        reconcile(snapshot, elapsed, canDownload)
                        lyrics.update(nextPlaybackPreloadTracks(snapshot, PRELOAD_LYRIC_SOURCES, PLAYBACK_PRELOAD_PROBE_COUNT))
                    }
                    // 让 280ms 阈值直接唤醒，不能被固定 250ms 轮询推迟到 500ms。
                    val elapsed = timing.elapsedMs(nowMs())
                    val remaining = (0 until PLAYBACK_PRELOAD_DOWNLOAD_COUNT).mapNotNull(timing::downloadDelayMs)
                        .map { it - elapsed }.filter { it > 0L }.minOrNull() ?: 250L
                    delay(minOf(250L, remaining))
                }
            } finally { feedback.close() }
        }
    }

    fun attachPlayer(player: Player, bandwidth: PlaybackPreloadBandwidth) { monitor?.attach(player, bandwidth) }
    fun detachPlayer() { monitor?.close() }
    fun foregroundChanged(visible: Boolean) {
        if (monitor?.foregroundChanged(visible) == true) cancelDownloads()
    }

    private fun reconcile(snapshot: MusicOneUiState, elapsed: Long, canDownload: Boolean) {
        val targets = nextPlaybackPreloadTracks(snapshot, PRELOAD_AUDIO_SOURCES, PLAYBACK_PRELOAD_PROBE_COUNT)
        val keys = targets.mapTo(hashSetOf(), ::key)
        tasks.keys.filter { it !in keys }.forEach { tasks.remove(it)?.cancel() }
        entries.retain(keys)
        val now = nowMs()
        val probeBusy = tasks.values.any { it.probeJob?.isActive == true }
        var probeStarted = false
        targets.forEachIndexed { index, track ->
            val key = key(track)
            // 队列插入或重排后角色会变化，必须按新角色重排探测与下载时机。
            tasks[key]?.takeIf { it.roleIndex != index }?.let { stale ->
                stale.cancel()
                tasks.remove(key)
            }
            val task = tasks.getOrPut(key) {
                val downloadDelay = timing.downloadDelayMs(index)
                Task(
                    track = track,
                    key = key,
                    roleIndex = index,
                    probeDelayMs = playbackProbeDelayMs(index),
                    prefetch = if (downloadDelay != null) PlaybackAudioPrefetch(context) else null,
                )
            }
            if (task.resolved == null) task.resolved = entries.peek(key, now)
            // 已对比过且手里有票时才允许直接下载；还在对比就等结果，避免先下低档。
            if (task.resolved != null && key in compared) task.probeFinished = true
            val work = entries.work(key, now)
            // 票据进入刷新窗口后必须重新探测，不能继续下载旧地址。
            if (task.resolved != null && work == PlaybackPreloadEntries.Work.RESOLVE) task.resolved = null
            if (!probeBusy && !probeStarted && now >= probeGateUntilMs &&
                elapsed >= task.probeDelayMs && task.probeJob?.isActive != true) {
                val needsComparison = key !in compared
                if (needsComparison) {
                    compared += key
                    startProbe(task, compare = true)
                    probeStarted = true
                } else if (task.resolved == null && work != PlaybackPreloadEntries.Work.NONE) {
                    startProbe(task, compare = false)
                    probeStarted = true
                }
            }
            val downloadDelay = timing.downloadDelayMs(index)
            if (canDownload && task.probeFinished && downloadDelay != null &&
                elapsed >= downloadDelay && task.resolved != null && !task.downloaded &&
                task.downloadJob?.isActive != true &&
                entries.work(key, now) == PlaybackPreloadEntries.Work.DOWNLOAD) {
                startDownload(task)
            }
        }
    }

    private fun startProbe(task: Task, compare: Boolean) {
        task.probeFinished = false
        task.probeJob = scope.launch {
            try {
                probe(task, compare)
            } finally {
                // 成功或失败都留出间隔，避免连续探测压到当前播放。
                task.probeFinished = true
                probeGateUntilMs = nowMs() + PLAYBACK_PRELOAD_PROBE_GAP_MS
            }
        }
    }

    private suspend fun probe(task: Task, compare: Boolean) {
        val track = task.track
        val key = task.key
        val now = nowMs()
        entries.work(key, now)
        val preferred = PlaybackOptions.effective(context, qualities.read(track.source), track.source)
            .playbackEquivalent()
        val cached = qualityCache.read(track)
        val ticket = entries.peek(key, now)
        try {
            val resolved = resolveTarget(track, preferred, cached, ticket, compare)
            if (resolved == null) {
                entries.failed(key, nowMs(), retryable = true)
                PlaybackPreloadDiagnostics.stage("后台无可用档位", track)
                return
            }
            // 迟到的结果不能写进后来切换的账号窗口。
            if (key(track) != key) {
                entries.failed(key, nowMs(), retryable = false)
                return
            }
            entries.resolved(key, resolved, nowMs())
            val previous = task.resolved
            // 票据被换成更高档时，停止可能已经开始写入的旧档片段。
            if (previous != null && previous !== resolved) task.cancelDownload()
            task.resolved = resolved
            if (task.prefetch != null) resolver.rememberPreloaded(resolved, preferred)
            PlaybackPreloadDiagnostics.stage("取源就绪", track)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            entries.failed(key, nowMs(), retryable =
                error !is TrackUnavailableException && !error.stopsPlaybackFallback())
            PlaybackPreloadDiagnostics.stage("后台失败", track, error.javaClass.simpleName)
        }
    }

    private suspend fun resolveTarget(
        track: MusicTrack,
        preferred: AudioQuality,
        cached: AudioQuality?,
        ticket: ResolvedPlayback?,
        compare: Boolean,
    ): ResolvedPlayback? {
        val usableTicket = ticket?.takeIf {
            playbackQualityAllowed(track.source, it.source.actualQuality, preferred) &&
                DolbyPlaybackPolicy.permits(it.source.actualQuality)
        }
        if (usableTicket != null && !compare) return usableTicket
        val probe = when {
            compare && cached != null -> resolver.probeComparison(track, preferred, cached)
            compare -> resolver.probeBest(track, preferred)
            cached != null -> resolver.probeAt(track, preferred, cached)
            else -> resolver.probeBest(track, preferred)
        }
        val probed = probe.playback ?: return usableTicket
        if (usableTicket == null) return probed
        // 探测暂用低档时，手里已有的更高票据不能被降级覆盖。
        return if (usableTicket.source.actualQuality.ordinal > probed.source.actualQuality.ordinal) {
            usableTicket
        } else {
            probed
        }
    }

    private fun startDownload(task: Task) {
        task.downloadJob = scope.launch {
            val key = task.key
            val resolved = task.resolved ?: return@launch
            val prefetch = task.prefetch ?: return@launch
            try {
                val cached = withContext(Dispatchers.IO) {
                    prefetch.cache(resolved, mayContinue = { mayDownload })
                }
                // 下载期间票据被替换时不能把新票据误标成已下载。
                if (task.resolved !== resolved) {
                    prefetch.cancel()
                    return@launch
                }
                if (cached) {
                    entries.downloaded(key)
                    task.downloaded = true
                    PlaybackPreloadDiagnostics.stage("片段就绪", task.track)
                } else {
                    entries.blocked(key, nowMs())
                    PlaybackPreloadDiagnostics.stage("缓存受限，仅地址就绪", task.track)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                entries.failed(key, nowMs(), retryable = true)
                PlaybackPreloadDiagnostics.stage("后台失败", task.track, error.javaClass.simpleName)
            }
        }
    }

    /** 切歌、暂停或缓冲不足时撤销后台工作；已完成票据仍保留给下一次切歌。 */
    fun stop() {
        mayProbe = false
        mayDownload = false
        clearTasks()
        lyrics.stop()
    }

    private fun clearTasks() {
        tasks.values.forEach(Task::cancel)
        tasks.clear()
    }

    private fun cancelDownloads() {
        mayDownload = false
        tasks.values.forEach(Task::cancelDownload)
    }

    fun take(track: MusicTrack, preferred: AudioQuality): ResolvedPlayback? {
        val key = key(track)
        val saved = entries.take(key, nowMs())
        if (saved == null) {
            PlaybackPreloadDiagnostics.stage("未命中新鲜地址", track)
            return null
        }
        val actual = saved.source.actualQuality
        if (!playbackQualityAllowed(track.source, actual, preferred) || !DolbyPlaybackPolicy.permits(actual)) return null
        if (!context.hasMusicNetwork() && offlinePlaybackKey(saved.source.url) == null) return null
        val prepared = saved.track
        // 当前队列的歌词和显示状态优先，只补充B站取源需要的真实分P和时长。
        val merged = track.copy(previewUrl = saved.source.url,
            bilibiliBvid = track.bilibiliBvid.ifBlank { prepared.bilibiliBvid },
            bilibiliCid = track.bilibiliCid.takeIf { it > 0L } ?: prepared.bilibiliCid,
            durationMs = track.durationMs.takeIf { it > 0L } ?: prepared.durationMs)
        PlaybackPreloadDiagnostics.stage("切歌命中", track)
        return saved.copy(track = merged)
    }

    private fun key(track: MusicTrack): Key {
        if (track.source == MusicSource.BILIBILI) {
            val session = biliSession.read()
            return Key(track.source, track.id, "BILIBILI:${session?.mid ?: "guest"}", session?.cookie.orEmpty(), false)
        }
        val session = preferences.readSession(track.source)
        return Key(
            track.source, track.id, session.cacheNamespace(), session.credential,
            session.account?.hasVipAccess == true,
        )
    }

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L
}
