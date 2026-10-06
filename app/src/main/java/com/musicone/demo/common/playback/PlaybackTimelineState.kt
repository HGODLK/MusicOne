package com.musicone.demo

/** 播放位置必须携带歌曲身份，避免切歌时把上一首的位置交给下一首。 */
internal data class PlaybackProgressSnapshot(
    val trackId: String? = null,
    val positionMs: Long = 0L,
)

/** 只描述播放器实际是否正在推进，不复用界面上的乐观播放状态。 */
internal data class PlaybackActivity(
    val trackId: String? = null,
    val advancing: Boolean = false,
)
