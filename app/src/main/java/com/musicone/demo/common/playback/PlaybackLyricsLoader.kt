package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

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
    private val timeoutMs: Long = PLAYBACK_LYRICS_TIMEOUT_MS,
    private val retryTimeoutMs: Long = PLAYBACK_LYRICS_RETRY_TIMEOUT_MS,
) {
    private var job: Job? = null
    private var sequence = 0L
    private val playbackStarted = MutableStateFlow<PlaybackRequestKey?>(null)

    fun cancel() { ++sequence; job?.cancel(); job = null; playbackStarted.value = null }

    fun onPlaybackStarted(key: PlaybackRequestKey) { playbackStarted.value = key }

    fun load(track: MusicTrack, key: PlaybackRequestKey, startDelayMs: Long = 0L,
        retryAfterPlayback: Boolean = true) {
        val alreadyStarted = playbackStarted.value == key
        cancel()
        if (alreadyStarted) playbackStarted.value = key
        val request = sequence
        fun emit(state: LyricLoadState, lyrics: List<TimedLyric>? = null) {
            if (request == sequence && isCurrent(key)) publish(PlaybackLyricsUpdate(key, state, lyrics))
        }
        val needsUpgrade = shouldUpgradeLyricCacheToVerbatim(track, track.lyrics)
        if (hasReusableLyrics(track, track.lyrics) && !needsUpgrade) {
            emit(LyricLoadState.READY)
            return
        }
        if (track.lyrics.isNotEmpty()) {
            emit(LyricLoadState.READY, track.lyrics)
        }
        job = scope.launch {
            lyricDiagnostic(track, "开始加载", track.lyrics, "代次=${key.generation}，序号=$request")
            if (track.lyrics.isEmpty()) emit(LyricLoadState.LOADING)
            // 起步避让不占网络超时预算；失败后最多等实际起播补试一次。
            if (startDelayMs > 0L) delay(startDelayMs)
            repeat(if (retryAfterPlayback) 2 else 1) { attempt ->
                if (attempt > 0) {
                    playbackStarted.first { it == key }
                    if (request != sequence || !isCurrent(key)) return@launch
                    emit(LyricLoadState.LOADING)
                }
                val lyrics = try {
                    withTimeoutOrNull(if (attempt > 0 || !retryAfterPlayback) retryTimeoutMs else timeoutMs) { load(track) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    lyricDiagnostic(track, "加载失败", detail = "异常=${error::class.java.simpleName}")
                    null
                }
                if (lyrics != null) {
                    lyricDiagnostic(track, "加载结束", lyrics, "代次=${key.generation}，序号=$request")
                    emit(if (lyrics.isEmpty() && track.lyrics.isEmpty()) LyricLoadState.UNAVAILABLE else LyricLoadState.READY,
                        lyrics.ifEmpty { track.lyrics })
                    return@launch
                }
                if (track.lyrics.isNotEmpty()) {
                    emit(LyricLoadState.READY, track.lyrics)
                    return@launch
                }
                emit(LyricLoadState.RETRYABLE)
            }
        }
    }
}
