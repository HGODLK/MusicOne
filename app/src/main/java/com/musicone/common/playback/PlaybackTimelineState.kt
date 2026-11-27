package com.musicone

/** 播放进度快照，带上 trackId 防止切歌时把上一首的进度误算给下一首。 */
internal data class PlaybackProgressSnapshot(
    val trackId: String? = null,
    val positionMs: Long = 0L,
    val generation: Long = 0L,
    val sampledAtMs: Long = 0L,
)

/** 记录播放器底层是否真正在出声播放，不直接复用界面上的按钮状态。 */
internal data class PlaybackActivity(
    val trackId: String? = null,
    val advancing: Boolean = false,
)

/** 用单调时钟推算平滑的歌词进度，暂停或缓冲时停在原地不动。 */
internal fun continuousLyricPosition(
    positionMs: Long, sampledAtMs: Long, nowMs: Long,
    advancing: Boolean, seeking: Boolean, durationMs: Long,
): Long {
    val elapsed = if (advancing && !seeking && sampledAtMs > 0L) (nowMs - sampledAtMs).coerceAtLeast(0L) else 0L
    val position = positionMs.coerceAtLeast(0L) + elapsed
    return if (durationMs > 0L) position.coerceAtMost(durationMs) else position
}
