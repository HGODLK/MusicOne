package com.musicone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface PlaybackTrackEvent {
    val key: PlaybackRequestKey
    data class Ready(override val key: PlaybackRequestKey, val playback: ResolvedPlayback,
        val preferred: AudioQuality, val effective: AudioQuality, val positionMs: Long,
        val playWhenReady: Boolean) : PlaybackTrackEvent
    data class Metadata(override val key: PlaybackRequestKey, val original: MusicTrack,
        val enriched: MusicTrack) : PlaybackTrackEvent
    data class Failed(override val key: PlaybackRequestKey, val message: String) : PlaybackTrackEvent
}

/** 音源和后补元数据共用请求身份，切歌后旧任务不能继续起播或覆盖详情。 */
internal class PlaybackTrackLoader(
    private val scope: CoroutineScope,
    private val takePreloaded: (MusicTrack, AudioQuality) -> ResolvedPlayback?,
    private val resolve: suspend (MusicTrack, AudioQuality, Long) -> ResolvedPlayback,
    private val enrich: suspend (MusicTrack) -> MusicTrack,
    private val isCurrent: (PlaybackRequestKey) -> Boolean,
    private val isPlaying: () -> Boolean,
    private val publish: (PlaybackTrackEvent) -> Unit,
    private val wakeGuard: PlaybackResolutionWakeGuard? = null,
    private val recover: (suspend (MusicTrack, AudioQuality, PlaybackSource) -> ResolvedPlayback)? = null,
    private val sourceStatus: (MusicTrack) -> QueueSourceStatus = { QueueSourceStatus.AVAILABLE },
    private val awaitSource: suspend () -> Unit = {
        com.musicone.sourceintegration.SourcePackageController.current()?.awaitStartup()
        Unit
    },
    private val selectQuality: ((MusicTrack) -> Pair<AudioQuality, AudioQuality>)? = null,
    private val retryPolicy: PlaybackRetryPolicy = PlaybackRetryPolicy(),
) {
    private var resolution: Job? = null
    private var metadata: Job? = null
    private var sequence = 0L
    val isResolving get() = resolution?.isActive == true

    fun cancel() { ++sequence; resolution?.cancel(); metadata?.cancel() }

    fun start(track: MusicTrack, key: PlaybackRequestKey, preferred: AudioQuality, effective: AudioQuality,
              positionMs: Long, playWhenReady: Boolean, lyricsHandedOff: Boolean,
              previousSource: PlaybackSource? = null) {
        cancel()
        val request = sequence
        fun current() = request == sequence && isCurrent(key)
        resolution = scope.launch {
            val started = System.nanoTime()
            PlaybackPipelineDiagnostics.stage("解析开始", key.generation)
            val releaseWake = wakeGuard?.acquire()
            try {
                // 等待包激活后再判断缺包，初始化等待不计入实际解析超时。
                if (track.source != MusicSource.LOCAL) {
                    awaitSource()
                }
                if (!current()) return@launch
                // 包激活后才能应用跟随主音源等音质规则，不能沿用等待前的能力快照。
                val (requestedPreferred, requestedEffective) = selectQuality?.invoke(track) ?: (preferred to effective)
                val status = sourceStatus(track)
                val settleDelay = if (track.source.lyricBehavior?.waitForSingleSwitchSettlement == true && !lyricsHandedOff)
                    ONLINE_PLAYBACK_SETTLE_DELAY_MS else 0L
                // 取源错误按策略重试；音源未启用或音源明确答复没有地址时不再重试，直接交给跳过逻辑。
                val session = PlaybackResolveSession(retryPolicy.tierTimeoutMs)
                val playback = withContext(session) { retryPlaybackResolve(retryPolicy,
                    extraTimeoutMs = { attempt -> if (attempt == 0) settleDelay else 0L },
                    retryable = { status == QueueSourceStatus.AVAILABLE && !session.definitive },
                    shouldContinue = ::current,
                    beforeAttempt = session::beginAttempt,
                ) { attempt ->
                    resolveQueuePlayback(track, status,
                        cached = { if (attempt == 0 && previousSource == null) takePreloaded(track, requestedEffective) else null },
                        resolve = {
                            // 首轮优先用原资源换票；失败后的重试改走完整解析，不再依赖可能已失效的旧资源。
                            if (attempt == 0 && previousSource != null && recover != null) recover.invoke(track, requestedEffective, previousSource)
                            else resolve(track, requestedEffective, if (attempt == 0) settleDelay else 0L)
                        })
                } }
                if (!current()) return@launch
                PlaybackPipelineDiagnostics.stage("解析完成", key.generation, (System.nanoTime() - started) / 1_000_000)
                publish(PlaybackTrackEvent.Ready(key, playback, requestedPreferred, requestedEffective, positionMs, playWhenReady))
                if (!current()) return@launch
                metadata = scope.launch {
                    while (playWhenReady && !isPlaying()) delay(100)
                    delay(PLAYBACK_METADATA_DELAY_MS)
                    val enriched = try { enrich(playback.track) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { return@launch }
                    if (current()) publish(PlaybackTrackEvent.Metadata(key, playback.track, enriched))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val timeout = error is PlaybackResolveTimeoutException
                PlaybackPipelineDiagnostics.stage(if (timeout) "解析超时" else "解析失败", key.generation,
                    (System.nanoTime() - started) / 1_000_000)
                // 重试用尽后一律允许跳过，是否真正跳过由运行时按播放意图和连续失败数决定。
                if (current()) publish(PlaybackTrackEvent.Failed(key,
                    if (timeout) "音源加载超时" else error.asUserMessage()))
            } finally { releaseWake?.invoke() }
        }
    }
}
