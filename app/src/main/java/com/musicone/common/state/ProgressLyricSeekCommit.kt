package com.musicone

/** 松手清空拖动预览前先记下目标进度，确保歌词只响应这次确认的跳转。 */
internal data class ProgressLyricSeekCommit(
    val trackId: String,
    val generation: Long,
    val revision: Long,
    val positionMs: Long,
    val confirmed: PlaybackProgressSnapshot? = null,
)
