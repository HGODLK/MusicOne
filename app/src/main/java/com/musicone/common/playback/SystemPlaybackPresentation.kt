package com.musicone

/** 标识由播放业务提供；系统适配层不解析音源名称、数字编号或平台前缀。 */
internal data class SystemPlaybackKey(val trackId: String, val generation: Long)

/** translation 已按歌词页显示内容裁剪；source 保留逐字、注音和完整翻译，供自带开关的桌面歌词读取。 */
internal data class SystemLyricLine(val timeMs: Long, val text: String, val translation: String? = null,
    val source: TimedLyric? = null)

/** 歌词时间已在业务侧校准，展示层只使用实际播放位置和通用资料。 */
internal data class SystemPlaybackPresentation(
    val key: SystemPlaybackKey,
    val title: String,
    val artist: String,
    val artworkUri: String?,
    val lyrics: List<SystemLyricLine>,
    val positionMs: Long,
    val sampledAtMs: Long,
    val durationMs: Long,
    val advancing: Boolean,
    val readyForPresentation: Boolean = true,
    // 逐字时钟只叠加逐字提前量，不叠加整行提前量和手动偏移。
    val wordLeadMs: Long = 0L,
    val lyricState: LyricLoadState = LyricLoadState.READY,
) {
    fun positionAt(nowMs: Long): Long = continuousLyricPosition(
        positionMs, sampledAtMs, nowMs, advancing, seeking = false, durationMs,
    )

    fun contentAt(nowMs: Long): SystemIslandContent {
        val position = positionAt(nowMs)
        var low = 0
        var high = lyrics.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (lyrics[middle].timeMs <= position) low = middle + 1 else high = middle
        }
        val line = lyrics.getOrNull(low - 1)
        val text = line?.text?.trim().orEmpty()
        return SystemIslandContent(
            key, title, artist, text.ifBlank { title },
            line?.translation?.trim()?.takeIf { text.isNotBlank() && it.isNotBlank() && it != text },
            if (durationMs > 0L) (position.toDouble() / durationMs * 100).toInt().coerceIn(0, 100) else null,
        )
    }

    fun nextUpdateDelayMs(nowMs: Long): Long {
        if (!advancing) return 1_000L
        val position = positionAt(nowMs)
        return lyrics.firstOrNull { it.timeMs > position }?.let { (it.timeMs - position).coerceIn(16L, 1_000L) }
            ?: 1_000L
    }
}

internal data class SystemIslandContent(
    val key: SystemPlaybackKey,
    val title: String,
    val artist: String,
    val lyric: String,
    val translation: String?,
    val progress: Int?,
)

internal data class SystemArtworkKey(val playback: SystemPlaybackKey, val uri: String?)
