package com.musicone.demo

/** 播放位置必须携带歌曲身份，避免切歌时把上一首的位置交给下一首。 */
internal data class PlaybackProgressSnapshot(
    val trackId: String? = null,
    val positionMs: Long = 0L,
    val generation: Long = 0L,
    val sampledAtMs: Long = 0L,
)

/** 只描述播放器实际是否正在推进，不复用界面上的乐观播放状态。 */
internal data class PlaybackActivity(
    val trackId: String? = null,
    val advancing: Boolean = false,
)

/** 页面和系统歌词共用单调时钟推算，暂停和缓冲期间不自行推进。 */
internal fun continuousLyricPosition(
    positionMs: Long, sampledAtMs: Long, nowMs: Long,
    advancing: Boolean, seeking: Boolean, durationMs: Long,
): Long {
    val elapsed = if (advancing && !seeking && sampledAtMs > 0L) (nowMs - sampledAtMs).coerceAtLeast(0L) else 0L
    val position = positionMs.coerceAtLeast(0L) + elapsed
    return if (durationMs > 0L) position.coerceAtMost(durationMs) else position
}
