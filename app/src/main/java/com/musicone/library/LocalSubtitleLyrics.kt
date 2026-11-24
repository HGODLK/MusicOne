package com.musicone

private val cueTiming = Regex("^(\\S+)\\s+-->\\s+(\\S+)(?:\\s.*)?$")
private val vttStamp = Regex("<((?:\\d+:)?\\d{2}:\\d{2}\\.\\d{3})>")
private val captionTag = Regex("</?(?:b|i|u|font|c|v|lang|ruby|rt)(?:[.\\s][^>]*)?>", RegexOption.IGNORE_CASE)
private val textEntity = Regex("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp|lrm|rlm);")

internal fun decodeLyricEntities(text: String): String = textEntity.replace(text) { match ->
    when (val code = match.groupValues[1]) {
        "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
        "nbsp" -> "\u00a0"; "lrm" -> "\u200e"; "rlm" -> "\u200f"
        else -> {
            val value = if (code.startsWith("#x")) code.substring(2).toIntOrNull(16) else code.drop(1).toIntOrNull()
            if (value != null && Character.isValidCodePoint(value)) String(Character.toChars(value)) else match.value
        }
    }
}

private val captionClock = Regex("(?:(\\d{1,5}):)?(\\d{1,3}):([0-5]\\d)[.,](\\d{1,3})")

internal fun lyricClockMillis(value: String): Long? {
    val match = captionClock.matchEntire(value) ?: return null
    val (h, m, s, fraction) = match.destructured
    return (h.toLongOrNull() ?: 0L) * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 +
        fraction.padEnd(3, '0').toLong()
}

internal fun parseLocalSubtitleCues(raw: String, webVtt: Boolean): List<TimedLyric> {
    val blocks = raw.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n[ \\t]*\n"))
    return blocks.mapNotNull { block ->
        val lines = block.trim().lines()
        if (webVtt && (lines[0] == "STYLE" || lines[0] == "REGION" || lines[0].startsWith("NOTE"))) return@mapNotNull null
        val index = lines.take(2).indexOfFirst { cueTiming.matches(it.trim()) }
        if (index < 0) return@mapNotNull null
        val timing = cueTiming.matchEntire(lines[index].trim()) ?: return@mapNotNull null
        val start = lyricClockMillis(timing.groupValues[1]) ?: return@mapNotNull null
        val end = lyricClockMillis(timing.groupValues[2])?.takeIf { it > start } ?: return@mapNotNull null
        val body = lines.drop(index + 1).joinToString("\n")
        fun plain(text: String) = decodeLyricEntities(captionTag.replace(text, ""))
        val stamps = if (webVtt) vttStamp.findAll(body).toList() else emptyList()
        val text = plain(if (webVtt) vttStamp.replace(body, "") else body).trim()
        if (text.isBlank()) return@mapNotNull null
        val words = mutableListOf<LyricWord>()
        var time = start
        var from = 0
        for (stamp in stamps) {
            val next = lyricClockMillis(stamp.groupValues[1])?.takeIf { it > time && it < end } ?: continue
            plain(body.substring(from, stamp.range.first)).takeIf(String::isNotEmpty)?.let { words += LyricWord(time, next, it) }
            from = stamp.range.last + 1
            time = next
        }
        if (words.isNotEmpty() || from > 0) plain(body.substring(from)).takeIf(String::isNotEmpty)?.let {
            words += LyricWord(time, end, it)
        }
        TimedLyric(start, text, words = words.takeIf { it.joinToString("") { w -> w.text }.trim() == text }.orEmpty())
    }.sortedBy(TimedLyric::timeMs)
}

private val assOverrides = Regex("\\{[^{}]*}")
private val assKaraoke = Regex("\\\\(?:[kK][fo]?)(\\d+)")

internal fun parseLocalAss(raw: String): List<TimedLyric> {
    var inEvents = false
    var columns = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
    return raw.lineSequence().mapNotNull { source ->
        val line = source.trim()
        if (line.startsWith('[')) inEvents = line.equals("[Events]", true)
        if (!inEvents) return@mapNotNull null
        if (line.startsWith("Format:", true)) columns = line.substringAfter(':').split(',').map { it.trim().lowercase() }
        if (!line.startsWith("Dialogue:", true) || columns.lastOrNull() != "text") return@mapNotNull null
        val values = line.substringAfter(':').split(',', limit = columns.size)
        if (values.size != columns.size) return@mapNotNull null
        val start = lyricClockMillis(values.getOrNull(columns.indexOf("start"))?.trim().orEmpty()) ?: return@mapNotNull null
        val end = lyricClockMillis(values.getOrNull(columns.indexOf("end"))?.trim().orEmpty()) ?: return@mapNotNull null
        if (end <= start) return@mapNotNull null
        val body = values.last()
        fun plain(text: String) = assOverrides.replace(text, "").replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")
        val text = plain(body).trim()
        if (text.isBlank()) return@mapNotNull null
        val markers = assOverrides.findAll(body).mapNotNull { tag -> assKaraoke.find(tag.value)?.let { tag to it.groupValues[1] } }.toList()
        var time = start
        val words = markers.mapIndexedNotNull { i, (tag, value) ->
            val duration = value.toLongOrNull()?.takeIf { it <= Long.MAX_VALUE / 10 } ?: return@mapNotNull null
            val next = lyricEnd(time, duration * 10) ?: return@mapNotNull null
            val until = markers.getOrNull(i + 1)?.first?.range?.first ?: body.length
            val prefix = if (i == 0) plain(body.substring(0, tag.range.first)) else ""
            LyricWord(time, next, prefix + plain(body.substring(tag.range.last + 1, until))).also { time = next }.takeIf { it.text.isNotEmpty() }
        }
        TimedLyric(start, text, words = words.takeIf { it.joinToString("") { w -> w.text }.trim() == text }.orEmpty())
    }.sortedBy(TimedLyric::timeMs).toList()
}
