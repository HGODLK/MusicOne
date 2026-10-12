package com.musicone.demo

/** 在预览清空之前保存进度条的最终目标；确认只属于同一次操作。 */
internal data class ProgressLyricSeekCommit(
    val trackId: String,
    val generation: Long,
    val revision: Long,
    val positionMs: Long,
    val confirmed: PlaybackProgressSnapshot? = null,
)
