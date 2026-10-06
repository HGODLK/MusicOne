package com.musicone.demo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 拖动预览只影响歌词和时间轴，松手后再提交音频跳转。 */
internal class PlayerSeekPreview {
    private var lyricSeekRevision = 0L
    private var progressLyricSeekRevision = 0L
    private val mutableLyricSeek = MutableStateFlow<LyricProgressSeek?>(null)
    val lyricSeek = mutableLyricSeek.asStateFlow()
    private val mutableProgressLyricSeek = MutableStateFlow<ProgressLyricSeek?>(null)
    val progressLyricSeek = mutableProgressLyricSeek.asStateFlow()
    private val mutablePosition = MutableStateFlow<Long?>(null)
    val position = mutablePosition.asStateFlow()

    fun update(fraction: Float, durationMs: Long) {
        mutablePosition.value = (fraction.coerceIn(0f, 1f) * durationMs).toLong()
    }

    fun clear() { mutablePosition.value = null }

    fun requestLyricSeek(trackId: String, fraction: Float) {
        mutableLyricSeek.value = LyricProgressSeek(
            trackId,
            fraction.coerceIn(0f, 1f),
            ++lyricSeekRevision,
        )
    }

    fun requestProgressLyricSeek(
        trackId: String,
        fromPositionMs: Long,
        fraction: Float,
    ): ProgressLyricSeek = ProgressLyricSeek(
            trackId,
            fromPositionMs.coerceAtLeast(0L),
            fraction.coerceIn(0f, 1f),
            ++progressLyricSeekRevision,
        ).also { mutableProgressLyricSeek.value = it }
}

internal data class LyricProgressSeek(val trackId: String, val fraction: Float, val revision: Long)

/** 进度条单击携带跳转前位置，歌词动画不会被同步到达的新播放进度覆盖起点。 */
internal data class ProgressLyricSeek(
    val trackId: String,
    val fromPositionMs: Long,
    val fraction: Float,
    val revision: Long,
    val animationPrepared: CompletableDeferred<Unit> = CompletableDeferred(),
)
