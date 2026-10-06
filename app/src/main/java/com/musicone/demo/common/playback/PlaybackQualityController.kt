package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal sealed interface PlaybackQualityEvent {
    val key: PlaybackRequestKey
    data class Loading(override val key: PlaybackRequestKey) : PlaybackQualityEvent
    data class Options(override val key: PlaybackRequestKey, val values: List<AudioQuality>, val complete: Boolean) : PlaybackQualityEvent
    data class OptionsFailed(override val key: PlaybackRequestKey, val message: String) : PlaybackQualityEvent
    data class Changing(override val key: PlaybackRequestKey, val quality: AudioQuality) : PlaybackQualityEvent
    data class Selected(override val key: PlaybackRequestKey, val quality: AudioQuality, val url: String?) : PlaybackQualityEvent
    data class ChangeFailed(override val key: PlaybackRequestKey, val message: String) : PlaybackQualityEvent
    data class Upgraded(override val key: PlaybackRequestKey, val quality: AudioQuality, val source: PlaybackSource) : PlaybackQualityEvent
}

/** 档位查询、手动切换与缓存升级共享取消边界，换源由统一播放入口提交。 */
internal class PlaybackQualityController(
    private val scope: CoroutineScope,
    private val options: suspend (MusicTrack, (List<AudioQuality>) -> Unit) -> List<AudioQuality>,
    private val resolve: suspend (MusicTrack, AudioQuality) -> PlaybackSource,
    resolvePreferred: suspend (MusicTrack, AudioQuality) -> ResolvedPlayback,
    private val savePreference: (MusicSource, AudioQuality) -> Unit,
    private val effectivePreference: (MusicSource) -> AudioQuality,
    private val isCurrent: (PlaybackRequestKey) -> Boolean,
    private val waitingForPlayback: () -> Boolean,
    private val publish: (PlaybackQualityEvent) -> Unit,
) {
    private var optionsJob: Job? = null
    private var changeJob: Job? = null
    private var optionsSequence = 0L
    private var changeSequence = 0L
    private var changing = false
    private val upgrade = CachedPlaybackUpgrade(scope, resolve = resolvePreferred)

    fun cancel() {
        ++optionsSequence
        ++changeSequence
        optionsJob?.cancel()
        changeJob?.cancel()
        changing = false
        upgrade.cancel()
    }

    fun loadOptions(track: MusicTrack, key: PlaybackRequestKey, delayMs: Long = 0L) {
        optionsJob?.cancel()
        val request = ++optionsSequence
        fun emit(event: PlaybackQualityEvent) {
            if (request == optionsSequence && isCurrent(key)) publish(event)
        }
        optionsJob = scope.launch {
            emit(PlaybackQualityEvent.Loading(key))
            try {
                if (delayMs > 0) {
                    delay(delayMs)
                    while (isCurrent(key) && waitingForPlayback()) delay(100)
                }
                val values = options(track) { emit(PlaybackQualityEvent.Options(key, it, false)) }
                emit(PlaybackQualityEvent.Options(key, values, true))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                emit(PlaybackQualityEvent.OptionsFailed(key, error.asUserMessage()))
            }
        }
    }

    fun select(track: MusicTrack, key: PlaybackRequestKey, quality: AudioQuality,
               active: AudioQuality?, available: List<AudioQuality>) {
        if (!isCurrent(key) || !quality.isAvailableInPlayer(available) || changing) return
        upgrade.cancel()
        if (active?.playbackEquivalent() == quality.playbackEquivalent()) {
            savePreference(track.source, quality)
            publish(PlaybackQualityEvent.Selected(key, quality, null))
            return
        }
        val request = ++changeSequence
        changing = true
        publish(PlaybackQualityEvent.Changing(key, quality))
        changeJob = scope.launch {
            try {
                val source = resolve(track, quality.playbackEquivalent())
                if (request != changeSequence || !isCurrent(key)) return@launch
                savePreference(track.source, quality)
                changing = false
                publish(PlaybackQualityEvent.Selected(key, quality, source.url))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == changeSequence && isCurrent(key)) {
                    changing = false
                    publish(PlaybackQualityEvent.ChangeFailed(key, error.asUserMessage()))
                }
            } finally {
                if (request == changeSequence) changing = false
            }
        }
    }

    fun followCachedPlayback(resolved: ResolvedPlayback, key: PlaybackRequestKey,
                             effective: AudioQuality, preferred: AudioQuality) {
        upgrade.start(resolved, effective, isCurrent = {
            isCurrent(key) && !changing && effectivePreference(resolved.track.source) == effective
        }) { next ->
            publish(PlaybackQualityEvent.Upgraded(key,
                playerDisplayedQuality(preferred, next.source.actualQuality), next.source))
        }
    }
}
