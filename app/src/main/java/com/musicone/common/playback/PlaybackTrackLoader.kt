package com.musicone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal sealed interface PlaybackTrackEvent {
    val key: PlaybackRequestKey
    data class Ready(override val key: PlaybackRequestKey, val playback: ResolvedPlayback,
        val preferred: AudioQuality, val effective: AudioQuality, val positionMs: Long,
        val playWhenReady: Boolean) : PlaybackTrackEvent
    data class Metadata(override val key: PlaybackRequestKey, val original: MusicTrack,
        val enriched: MusicTrack) : PlaybackTrackEvent
    data class Failed(override val key: PlaybackRequestKey, val message: String,
        val maySkip: Boolean = true) : PlaybackTrackEvent
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
                val playback = withTimeout(30_000L) {
                    resolveQueuePlayback(track, sourceStatus(track),
                        cached = { if (previousSource == null) takePreloaded(track, requestedEffective) else null },
                        resolve = {
                            if (previousSource != null && recover != null) recover.invoke(track, requestedEffective, previousSource)
                            else resolve(track, requestedEffective,
                                if (track.source.lyricBehavior?.waitForSingleSwitchSettlement == true && !lyricsHandedOff) ONLINE_PLAYBACK_SETTLE_DELAY_MS else 0L)
                        })
                }
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
            } catch (timeout: TimeoutCancellationException) {
                if (current()) {
                    PlaybackPipelineDiagnostics.stage("解析超时", key.generation)
                    publish(PlaybackTrackEvent.Failed(key, "音源加载超时，请重试", false))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                PlaybackPipelineDiagnostics.stage("解析失败", key.generation, (System.nanoTime() - started) / 1_000_000)
                if (current()) publish(PlaybackTrackEvent.Failed(key,
                    error.asUserMessage(), maySkipSourceFailure(error)))
            } finally { releaseWake?.invoke() }
        }
    }
}
