package com.musicone.demo

/** 标识由播放业务提供；系统适配层不解析音源名称、数字编号或平台前缀。 */
internal data class SystemPlaybackKey(val trackId: String, val generation: Long)

internal data class SystemLyricLine(val timeMs: Long, val text: String, val translation: String? = null)

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
