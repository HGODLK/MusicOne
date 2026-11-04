package com.musicone

import kotlinx.coroutines.*

internal data class PlaybackLyricTarget(val track: MusicTrack, val namespace: String, val revision: String = "")

/** 按稳定曲目和账号身份维持歌词窗口，单首失败不影响后续目标。 */
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
                catch (_: Exception) { /* 保留当前窗口，继续准备其他歌曲。 */ }
            }
        }
    }
    fun stop() { job?.cancel(); job = null; keys = emptyList() }
}
