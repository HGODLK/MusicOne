package com.musicone.demo

internal fun parseQqQrc(raw: String): List<TimedLyric> = runCatching {
    val marker = "LyricContent=\""
    val start = raw.indexOf(marker)
    val xmlBody = if (start != -1) {
        val startIdx = start + marker.length
        val closeTag = raw.lastIndexOf("/>").takeIf { it > startIdx } ?: raw.lastIndexOf(">").takeIf { it > startIdx }
        val endIdx = if (closeTag != null) {
            raw.lastIndexOf('"', closeTag).takeIf { it > startIdx } ?: closeTag
        } else {
            raw.lastIndexOf('"').takeIf { it > startIdx } ?: raw.length
        }
        raw.substring(startIdx, endIdx)
    } else raw

    val content = xmlBody.replace("&#10;", "\n")
        .replace("&#13;", "\r")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    val linePattern = Regex("^\\[(\\d+),(\\d+)](.*)$")
    val wordPattern = Regex("\\((\\d+),(\\d+)\\)")
    content.lineSequence().mapNotNull { rawLine ->
        val line = rawLine.trim()
        val match = linePattern.matchEntire(line) ?: return@mapNotNull null
        val lineStart = match.groupValues[1].toLongOrNull() ?: 0L
        val lineBody = match.groupValues[3]

        var cursor = 0
        val words = wordPattern.findAll(lineBody).mapNotNull { timing ->
            // 时间标记之间的原文完整保留，括号和引号本身也可以是一个词段。
            val text = lineBody.substring(cursor, timing.range.first)
            cursor = timing.range.last + 1
            if (text.isEmpty() || text == "\r") return@mapNotNull null
            val startMs = timing.groupValues[1].toLong()
            val durationMs = timing.groupValues[2].toLong()
            LyricWord(startMs, startMs + durationMs, text)
        }.toList()
        val fullText = wordPattern.replace(lineBody, "").trim()

        if (fullText.isBlank() || fullText == "//") return@mapNotNull null
        TimedLyric(
            timeMs = lineStart,
            text = fullText,
            translation = null,
            words = words,
            parserVersion = CURRENT_LYRIC_PARSER_VERSION,
        )
    }.sortedBy(TimedLyric::timeMs).toList()
}.getOrDefault(emptyList())
