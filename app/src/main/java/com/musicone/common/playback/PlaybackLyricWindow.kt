package com.musicone

import kotlinx.coroutines.*

internal data class PlaybackLyricTarget(val track: MusicTrack, val namespace: String, val revision: String = "")

/** 维护待预加载的歌词队列（按歌曲和账号区分），某一首拉取失败不影响继续拉后面的。 */
internal class PlaybackLyricWindow(private val scope: CoroutineScope,
    private val prepare: suspend (MusicTrack, String) -> Unit, private val initialDelayMs: Long = 0L) {
    private var keys: List<List<String>> = emptyList()
    private var job: Job? = null
    fun update(tracks: List<MusicTrack>, namespace: String) = update(tracks.map { PlaybackLyricTarget(it, namespace) })
    fun update(targets: List<PlaybackLyricTarget>) {
        val next = targets.map { listOf(it.track.source.id, it.track.id, it.namespace, it.revision) }
        if (next == keys) return
        stop()
        keys = next
        job = scope.launch(Dispatchers.IO) {
            delay(initialDelayMs)
            targets.forEach { target ->
                ensureActive()
                try { prepare(target.track, target.namespace) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 单首失败忽略，继续拉后面的 */ }
            }
        }
    }
    fun stop() { job?.cancel(); job = null; keys = emptyList() }
}
