package com.musicone

import android.content.Context
import com.musicone.sourceintegration.*
import androidx.media3.common.Player
import kotlinx.coroutines.*

/**
 * 后台预加载：当前歌起播后按 0s／2s／5s 探测后续五首，
 * 默认在 +280ms／+2s 整首下载前两首；三次带宽不足卡顿后改为稳定播放 +3s／+10s。
 * 暂停保留已有任务，立即并行准备后续三首。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackPreloader(
    private val context: Context,
    private val scope: CoroutineScope,
    // 判断地址过期必须用包含息屏休眠时间的单调时钟，否则锁屏放久了会误以为旧地址还能用。
    private val ticketClockMs: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private data class Key(
        val source: MusicSource,
        val id: String,
        val namespace: String,
        val credential: String,
        val vip: Boolean?,
    )

    private class Task(
        val track: MusicTrack,
        val key: Key,
        val roleIndex: Int,
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

    private val qualities = PlaybackQualityPreferences(context)
    private val resolver = PlatformPlaybackResolver(context, requestOrigin = PlaybackRequestOrigin.PRELOAD)
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
    private var pausedPreloading = false
    private val gate = PlaybackPreloadGate(mayStart = { mayProbe })
    private val lyrics = PlaybackLyricsPreloader(context, scope, gate)

    fun observe(state: () -> MusicOneUiState, switching: () -> Boolean, player: () -> Player?,
        qualityQueryWaiting: () -> Boolean = { false }) {
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
                    val paused = transport != null && playbackPreloadIsPaused(matches, playing,
                        transport.playWhenReady, transport.playbackState, transport.playerError != null)
                    // 用户正在切音质或等音质列表时，暂停预加载把带宽和请求让出来。
                    val active = current != null && !snapshot.qualityChanging && !qualityQueryWaiting() &&
                        !switching() && context.hasMusicNetwork()
                    val canProbe = active && playbackPreloadCanProbe(playing && snapshot.isPlaying, paused)
                    val buffered = transport?.let { (it.bufferedPosition - it.currentPosition).coerceAtLeast(0L) } ?: 0L
                    val local = current?.source == MusicSource.LOCAL
                    val canDownload = canProbe && playbackPreloadCanDownload(playing, buffered, local, paused)
                    pausedPreloading = canProbe && paused
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
                        val elapsed = timing.elapsedMs(playbackNowMs())
                        reconcile(snapshot, elapsed, canDownload, paused)
                        lyrics.update(nextPlaybackPreloadTracks(snapshot, PRELOAD_LYRIC_SOURCES, PLAYBACK_PRELOAD_PROBE_COUNT))
                    }
                    // 让 280ms 阈值直接唤醒，不能被固定 250ms 轮询推迟到 500ms。
                    val elapsed = timing.elapsedMs(playbackNowMs())
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
        val entering = monitor?.foregroundChanged(visible) == true
        if (entering && !pausedPreloading) cancelDownloads()
        if (visible) {
            val now = ticketNowMs()
            val invalidated = entries.onForegroundResume(now)
            if (entering || invalidated.isNotEmpty()) probeGateUntilMs = 0L
            invalidated.forEach { key ->
                tasks[key]?.let { task ->
                    task.resolved = null
                    task.downloaded = false
                    task.cancelDownload()
                }
            }
        }
    }

    private fun reconcile(snapshot: MusicOneUiState, elapsed: Long, canDownload: Boolean, paused: Boolean) {
        val targets = nextPlaybackPreloadTracks(snapshot, PRELOAD_AUDIO_SOURCES, PLAYBACK_PRELOAD_PROBE_COUNT)
        val keys = targets.mapTo(hashSetOf(), ::key)
        tasks.keys.filter { it !in keys }.forEach { tasks.remove(it)?.cancel() }
        entries.retain(keys)
        val now = ticketNowMs()
        val probeBusy = tasks.values.any { it.probeJob?.isActive == true }
        var probeStarted = false
        targets.forEachIndexed { index, track ->
            val key = key(track)
            // 队列插歌或调整顺序后，歌曲的顺位变了，按新顺位重新安排预加载。
            tasks[key]?.takeIf { it.roleIndex != index }?.let { stale ->
                stale.cancel()
                tasks.remove(key)
            }
            val task = tasks.getOrPut(key) {
                Task(
                    track = track,
                    key = key,
                    roleIndex = index,
                    prefetch = if (index < PLAYBACK_PRELOAD_PAUSED_DOWNLOAD_COUNT) PlaybackAudioPrefetch(context) else null,
                )
            }
            if (task.resolved == null) task.resolved = entries.peek(key, now)
            // 音质比对完且拿到地址后才开始下载；还在比对就先等等，免得白下低音质。
            if (task.resolved != null && key in compared) task.probeFinished = true
            val work = entries.work(key, now)
            // 地址快过期了就必须重新获取，不能拿旧地址继续下，也不能复用旧的下载完成状态。
            if (task.resolved != null && work == PlaybackPreloadEntries.Work.RESOLVE) {
                task.resolved = null
                task.downloaded = false
                task.cancelDownload()
            }
            if ((paused || (!probeBusy && !probeStarted && now >= probeGateUntilMs)) &&
                playbackPreloadProbeDue(index, elapsed, paused) && task.probeJob?.isActive != true) {
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
            val downloadDelay = playbackDownloadDelayMs(index, timing.conservative, paused)
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
                probeGateUntilMs = ticketNowMs() + PLAYBACK_PRELOAD_PROBE_GAP_MS
            }
        }
    }

    private suspend fun probe(task: Task, compare: Boolean) {
        val track = task.track
        val key = task.key
        val now = ticketNowMs()
        entries.work(key, now)
        val preferred = PlaybackOptions.effective(context, qualities.read(track.source), track.source)
            .playbackEquivalent()
        val cached = qualityCache.read(track)
        val ticket = entries.peek(key, now)
        try {
            val resolved = resolveTarget(track, preferred, cached, ticket, compare)
            if (resolved == null) {
                entries.failed(key, ticketNowMs(), retryable = true)
                PlaybackPreloadDiagnostics.stage("后台无可用档位", track)
                return
            }
            // 切换账号后，旧账号迟到的预加载结果直接丢弃。
            if (key(track) != key) {
                entries.failed(key, ticketNowMs(), retryable = false)
                return
            }
            entries.resolved(key, resolved, ticketNowMs(), resolved.source.expiresAtEpochMs?.let { it - System.currentTimeMillis() })
            val previous = task.resolved
            // 拿到更高音质的地址后，停掉正在下载的低音质任务。
            if (previous != null && previous !== resolved) {
                task.downloaded = false
                task.cancelDownload()
            }
            task.resolved = resolved
            if (task.prefetch != null) resolver.rememberPreloaded(resolved, preferred)
            PlaybackPreloadDiagnostics.stage("取源就绪", track)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            entries.failed(key, ticketNowMs(), retryable =
                error !is TrackUnavailableException && !error.stopsSourcePlaybackFallback())
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
        // 如果探测只拿到低音质，不要覆盖手里已经有的高音质地址。
        return if (usableTicket.source.actualQuality.isHigherThan(probed.source.actualQuality)) {
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
                // 下载中途如果地址换了，别把新地址误标成已下载。
                if (task.resolved !== resolved) {
                    prefetch.cancel()
                    return@launch
                }
                if (cached) {
                    entries.downloaded(key)
                    task.downloaded = true
                    PlaybackPreloadDiagnostics.stage("片段就绪", task.track)
                } else {
                    entries.blocked(key, ticketNowMs())
                    PlaybackPreloadDiagnostics.stage("缓存受限，仅地址就绪", task.track)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                entries.failed(key, ticketNowMs(), retryable = true)
                PlaybackPreloadDiagnostics.stage("后台失败", task.track, error.javaClass.simpleName)
            }
        }
    }

    /** 切歌、断网或播不了时停掉后台预加载；正常暂停时不走这里，改由暂停预加载逻辑接管。 */
    fun stop() {
        pausedPreloading = false
        mayProbe = false
        mayDownload = false
        clearTasks()
        lyrics.stop()
    }

    fun stopForSource(sourceId: String) {
        tasks.filterKeys { it.source.id == sourceId }.values.forEach(Task::cancel)
        tasks.keys.removeAll { it.source.id == sourceId }
        entries.retain(tasks.keys)
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
        val saved = entries.take(key, ticketNowMs())
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
            durationMs = track.durationMs.takeIf { it > 0L } ?: prepared.durationMs)
            .copy(providerData = prepared.providerData + track.providerData)
        PlaybackPreloadDiagnostics.stage("切歌命中", track)
        return saved.copy(track = merged)
    }

    /** 给自然过渡用：查看下一首已经整首下好的播放信息，先不移除缓存（等真正切歌时再取走）。 */
    fun peekDownloaded(track: MusicTrack, preferred: AudioQuality): ResolvedPlayback? {
        val key = key(track)
        if (tasks[key]?.downloaded != true) return null
        val saved = entries.peek(key, ticketNowMs()) ?: return null
        val actual = saved.source.actualQuality
        if (!playbackQualityAllowed(track.source, actual, preferred) || !DolbyPlaybackPolicy.permits(actual)) return null
        val merged = track.copy(previewUrl = saved.source.url,
            durationMs = track.durationMs.takeIf { it > 0L } ?: saved.track.durationMs)
            .copy(providerData = saved.track.providerData + track.providerData)
        return saved.copy(track = merged)
    }

    private fun key(track: MusicTrack): Key {
        val account = sourceAccountState(context, track.source)
        return Key(track.source, track.id, sourceCacheNamespace(context, track.source),
            account?.revision?.toString().orEmpty(), sourceMembershipState(context, track.source))
    }

    private fun playbackNowMs(): Long = System.nanoTime() / 1_000_000L
    private fun ticketNowMs(): Long = ticketClockMs()
}
