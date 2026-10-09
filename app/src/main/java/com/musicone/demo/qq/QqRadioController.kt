package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class QqRadioQueueSnapshot(val queue: List<MusicTrack>, val current: MusicTrack?)
internal sealed interface QqRadioEvent {
    data class Mode(val active: Boolean) : QqRadioEvent
    data class Loading(val initial: Boolean) : QqRadioEvent
    data class Started(val queue: List<MusicTrack>) : QqRadioEvent
    data class Appended(val queue: List<MusicTrack>, val advance: Boolean) : QqRadioEvent
    data class Failed(val message: String, val initial: Boolean, val pause: Boolean) : QqRadioEvent
}

/** QQ 电台独立拥有请求代次、队列窗口和补歌后的前进意图。 */
internal class QqRadioController(
    private val scope: CoroutineScope,
    private val load: suspend (Set<String>, Int) -> List<MusicTrack>,
    private val snapshot: () -> QqRadioQueueSnapshot,
    private val publish: (QqRadioEvent) -> Unit,
) {
    private val window = QqRadioQueueWindow()
    private var job: Job? = null
    private var generation = 0L
    private var advancePending = false
    private var active = false

    fun stop() {
        ++generation
        job?.cancel()
        job = null
        advancePending = false
        active = false
        window.reset()
        publish(QqRadioEvent.Mode(false))
    }

    fun restore(queue: List<MusicTrack>, currentId: String?): List<MusicTrack> {
        active = true
        return window.restore(queue, currentId)
    }

    fun insert(queue: List<MusicTrack>, current: MusicTrack?, track: MusicTrack): List<MusicTrack> {
        active = true
        val result = window.insert(queue, current, track)
        publish(QqRadioEvent.Mode(true))
        return result
    }

    fun center(queue: List<MusicTrack>, trackId: String) = window.center(queue, trackId)

    fun start() {
        stop()
        request(initial = true)
    }

    fun startWithSeed(seed: MusicTrack) {
        stop()
        active = true
        val queue = window.start(emptyList(), seed)
        publish(QqRadioEvent.Mode(true))
        publish(QqRadioEvent.Started(queue))
        prefetch()
    }

    fun prefetch() {
        if (!active || job?.isActive == true) return
        val current = snapshot()
        if (window.requestSize(current.queue, current.current?.id, false) > 0) request(initial = false)
    }

    fun advance() {
        if (!active) return
        advancePending = true
        if (job?.isActive != true) request(initial = false)
    }

    private fun request(initial: Boolean) {
        val current = snapshot()
        val size = if (initial) window.initialRequestSize
            else window.requestSize(current.queue, current.current?.id, advancePending)
        if (size <= 0) return
        val request = generation
        publish(QqRadioEvent.Loading(initial))
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val tracks = load(if (initial) emptySet() else window.seenIds(), size)
                if (request != generation || (!initial && !active)) return@launch
                val latest = snapshot()
                if (tracks.isEmpty()) throw PlatformApiException(
                    if (initial) "猜你喜欢暂时没有可播放歌曲" else "猜你喜欢暂时没有更多歌曲")
                val queue = if (initial) window.start(tracks)
                    else window.append(latest.queue, tracks, latest.current?.id)
                if (queue.isEmpty()) throw PlatformApiException("猜你喜欢暂时没有可播放歌曲")
                job = null
                active = true
                val advance = advancePending
                advancePending = false
                publish(if (initial) QqRadioEvent.Started(queue) else QqRadioEvent.Appended(queue, advance))
                prefetch()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (request == generation) {
                    job = null
                    val pause = advancePending
                    advancePending = false
                    if (initial) active = false
                    publish(QqRadioEvent.Failed(error.asUserMessage(), initial, pause))
                }
            }
        }
        job?.start()
    }
}
