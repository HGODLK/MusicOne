package com.musicone

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

/** 统一管理音质列表查询、手动切换音质和后台升级音质，切歌时一并取消。 */
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
    // 记录当前档位查询的进度，打开菜单时据此决定沿用还是补发请求。
    private var optionsKey: PlaybackRequestKey? = null
    private var optionsDeferred = false
    private var optionsReady = false
    private var optionsInteractive = false
    private val upgrade = CachedPlaybackUpgrade(scope, resolve = resolvePreferred)

    /** 用户正打开音质菜单等结果，后台预加载看到这个标记会先暂停让路。 */
    val waitingForUser: Boolean get() = optionsInteractive && !optionsReady && optionsJob?.isActive == true

    fun cancel() {
        ++optionsSequence
        ++changeSequence
        optionsJob?.cancel()
        changeJob?.cancel()
        changing = false
        optionsKey = null
        optionsDeferred = false
        optionsReady = false
        optionsInteractive = false
        upgrade.cancel()
    }

    /** 打开音质菜单时调用：如果已经查完或正在查就直接复用；如果还在起播延迟等待中，就立刻开查。 */
    fun ensureOptions(track: MusicTrack, key: PlaybackRequestKey) {
        if (key == optionsKey && (optionsReady || (optionsJob?.isActive == true && !optionsDeferred))) {
            if (!optionsReady) optionsInteractive = true
            return
        }
        loadOptions(track, key, interactive = true)
    }

    fun loadOptions(track: MusicTrack, key: PlaybackRequestKey, delayMs: Long = 0L, interactive: Boolean = false) {
        // 后台刷新取消了用户正在等的同首歌查询时，继续保持“用户等待中”的标记。
        val userWaiting = interactive || (key == optionsKey && waitingForUser)
        optionsJob?.cancel()
        val request = ++optionsSequence
        optionsKey = key
        optionsDeferred = delayMs > 0
        optionsReady = false
        optionsInteractive = userWaiting
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
                if (request == optionsSequence) optionsDeferred = false
                val values = options(track) { partial ->
                    // 音源包可能在 IO 线程逐档回报，切回主线程按顺序合并，完整结果之后的迟到片段丢弃。
                    scope.launch { if (request == optionsSequence && !optionsReady) emit(PlaybackQualityEvent.Options(key, partial, false)) }
                }
                if (request == optionsSequence) optionsReady = true
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
        if (!DolbyPlaybackPolicy.permits(quality)) {
            publish(PlaybackQualityEvent.ChangeFailed(key, DolbyPlaybackPolicy.eligibility.unavailableReason.orEmpty()))
            return
        }
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
                DolbyPlaybackPolicy.requireAllowed(source.actualQuality)
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
