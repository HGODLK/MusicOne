package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
) {
    private var resolution: Job? = null
    private var metadata: Job? = null
    private var sequence = 0L
    val isResolving get() = resolution?.isActive == true

    fun cancel() { ++sequence; resolution?.cancel(); metadata?.cancel() }

    fun start(track: MusicTrack, key: PlaybackRequestKey, preferred: AudioQuality, effective: AudioQuality,
              positionMs: Long, playWhenReady: Boolean, lyricsHandedOff: Boolean) {
        cancel()
        val request = sequence
        fun current() = request == sequence && isCurrent(key)
        resolution = scope.launch {
            try {
                val playback = takePreloaded(track, effective) ?: resolve(track, effective,
                    if (track.source == MusicSource.QQ && !lyricsHandedOff) QQ_ONLINE_PLAYBACK_SETTLE_DELAY_MS else 0L)
                if (!current()) return@launch
                publish(PlaybackTrackEvent.Ready(key, playback, preferred, effective, positionMs, playWhenReady))
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
                if (current()) publish(PlaybackTrackEvent.Failed(key, error.asUserMessage()))
            }
        }
    }
}
