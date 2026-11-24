package com.musicone.source.webdav.internal

import com.musicone.source.sdk.SourceLyricLine
import com.musicone.source.sdk.SourceLyrics

/**
 * 解析标准 .lrc 歌词文件。
 * 支持一行多时间标签、[offset:] 全局偏移，以及双语歌词中同时间戳合并为翻译行。
 */
internal object WebDavLyricsParser {
    private val TIMESTAMP_REGEX = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
    private val OFFSET_REGEX = Regex("^\\[offset:([+-]?\\d+)]$", RegexOption.IGNORE_CASE)

    fun parse(raw: String): SourceLyrics? {
        if (raw.isBlank()) return null
        var offsetMs = 0L
        val entries = mutableListOf<Pair<Long, String>>()

        raw.lineSequence().forEach { line ->
            val trimmed = line.trim().removePrefix("\uFEFF")
            if (trimmed.isEmpty()) return@forEach

            OFFSET_REGEX.matchEntire(trimmed)?.let { m ->
                offsetMs = m.groupValues[1].toLongOrNull() ?: 0L
                return@forEach
            }

            val matches = TIMESTAMP_REGEX.findAll(trimmed).toList()
            if (matches.isEmpty()) return@forEach

            val text = TIMESTAMP_REGEX.replace(trimmed, "").trim()
            if (text.isEmpty()) return@forEach

            for (m in matches) {
                val minutes = m.groupValues[1].toLongOrNull() ?: continue
                val seconds = m.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = m.groupValues[3]
                val millis = when (fracRaw.length) {
                    0 -> 0L
                    1 -> fracRaw.toLong() * 100L
                    2 -> fracRaw.toLong() * 10L
                    else -> fracRaw.take(3).toLong()
                }
                val startMs = (minutes * 60_000L + seconds * 1_000L + millis - offsetMs).coerceAtLeast(0L)
                entries += startMs to text
            }
        }

        if (entries.isEmpty()) return null

        // 按时间升序稳定归组：同时间戳第二行视为翻译
        val grouped = LinkedHashMap<Long, MutableList<String>>()
        entries.sortedBy { it.first }.forEach { (time, text) ->
            grouped.getOrPut(time) { mutableListOf() }.add(text)
        }

        val lines = grouped.map { (time, texts) ->
            val primary = texts.first()
            val translation = texts.drop(1).filter { it != primary }.joinToString("\n").ifBlank { null }
            SourceLyricLine(
                startMs = time,
                text = primary,
                translation = translation,
            )
        }
        return SourceLyrics(lines = lines)
    }
}
