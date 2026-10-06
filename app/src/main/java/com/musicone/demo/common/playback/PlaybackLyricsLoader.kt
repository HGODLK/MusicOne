package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal data class PlaybackRequestKey(val trackId: String, val generation: Long)
internal data class PlaybackLyricsUpdate(
    val key: PlaybackRequestKey,
    val state: LyricLoadState,
    val lyrics: List<TimedLyric>? = null,
)

/** 歌词任务独立取消，迟到结果同时校验播放身份与本次加载序号。 */
internal class PlaybackLyricsLoader(
    private val scope: CoroutineScope,
    private val load: suspend (MusicTrack) -> List<TimedLyric>,
    private val isCurrent: (PlaybackRequestKey) -> Boolean,
    private val publish: (PlaybackLyricsUpdate) -> Unit,
) {
    private var job: Job? = null
    private var sequence = 0L

    fun cancel() { ++sequence; job?.cancel(); job = null }

    fun load(track: MusicTrack, key: PlaybackRequestKey, startDelayMs: Long = 0L) {
        cancel()
        val request = sequence
        fun emit(state: LyricLoadState, lyrics: List<TimedLyric>? = null) {
            if (request == sequence && isCurrent(key)) publish(PlaybackLyricsUpdate(key, state, lyrics))
        }
        if (track.lyrics.isNotEmpty()) {
            emit(LyricLoadState.READY)
            return
        }
        job = scope.launch {
            emit(LyricLoadState.LOADING)
            val lyrics = try {
                withTimeoutOrNull(PLAYBACK_LYRICS_TIMEOUT_MS) {
                    if (startDelayMs > 0L) delay(startDelayMs)
                    load(track)
                }.orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { emptyList() }
            emit(if (lyrics.isEmpty()) LyricLoadState.UNAVAILABLE else LyricLoadState.READY, lyrics)
        }
    }
}
