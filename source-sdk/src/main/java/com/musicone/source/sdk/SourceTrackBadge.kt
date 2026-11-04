package com.musicone.source.sdk

/** 歌曲名称旁的展示角标。颜色为可空的 0xAARRGGBB；空值沿用宿主默认配色。 */
data class SourceTrackBadge(
    val text: String,
    val lightTextArgb: Long? = null,
    val lightBackgroundArgb: Long? = null,
    val darkTextArgb: Long? = null,
    val darkBackgroundArgb: Long? = null,
)
