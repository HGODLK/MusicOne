package com.musicone.demo

internal const val HYPER_OS_NO_LYRICS = "暂无歌词"

/** 摘要只携带原文歌词；展开区仅保留歌曲资料、时间与进度。 */
internal data class HyperOsIslandFrame(
    val key: SystemPlaybackKey,
    val summary: HyperOsIslandSummary,
    val title: String,
    val artist: String,
    val elapsedSeconds: Long,
    val durationSeconds: Long,
) {
    val progress: Int get() = if (durationSeconds > 0L)
        (elapsedSeconds.toDouble() / durationSeconds * 1000).toInt().coerceIn(0, 1000) else 0
    val elapsedText: String get() = "%02d:%02d".format(java.util.Locale.ROOT,
        elapsedSeconds / 60, elapsedSeconds % 60)
}

/** 无歌词仅在超级岛边界兜底，不改动其他系统展示的约定。 */
internal fun SystemPlaybackPresentation.hyperOsContentAt(nowMs: Long): SystemIslandContent {
    val content = contentAt(nowMs)
    return if (lyrics.none { it.text.isNotBlank() })
        content.copy(lyric = HYPER_OS_NO_LYRICS, translation = null) else content.copy(translation = null)
}
