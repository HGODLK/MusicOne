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
        val wantsVerbatim = track.source == MusicSource.QQ && ExperiencePreferences.options.verbatimLyricAnimation
        val needsUpgrade = wantsVerbatim && track.lyrics.isNotEmpty() && track.lyrics.none { it.words.isNotEmpty() }
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
            val lyrics = try {
                withTimeoutOrNull(PLAYBACK_LYRICS_TIMEOUT_MS) {
                    if (startDelayMs > 0L) delay(startDelayMs)
                    load(track)
                }.orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                lyricDiagnostic(track, "加载失败", detail = "异常=${error::class.java.simpleName}")
                emptyList()
            }
            lyricDiagnostic(track, "加载结束", lyrics, "代次=${key.generation}，序号=$request")
            emit(if (lyrics.isEmpty() && track.lyrics.isEmpty()) LyricLoadState.UNAVAILABLE else LyricLoadState.READY, lyrics.ifEmpty { track.lyrics })
        }
    }
}
