package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** 歌词窗口独立于音频预取，队列或账号改变后取消旧窗口，单首失败不阻塞后续歌曲。 */
internal class QqLyricsPreloadWindow(
    private val scope: CoroutineScope,
    private val preload: suspend (MusicTrack, String) -> Unit,
    private val settleDelayMs: Long = 3_000L,
) {
    private var request: Pair<String, List<String>>? = null
    private var job: Job? = null

    fun update(tracks: List<MusicTrack>, namespace: String) {
        val next = namespace to tracks.map(MusicTrack::id)
        if (request == next) return
        val previous = job
        stop()
        if (tracks.isEmpty()) return
        request = next
        job = scope.launch(Dispatchers.IO) {
            previous?.join()
            delay(settleDelayMs)
            for (track in tracks) {
                ensureActive()
                try { preload(track, namespace) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 单首失败时仍准备窗口内其他歌曲。 */ }
            }
        }
    }

    fun stop() { job?.cancel(); job = null; request = null }
}
