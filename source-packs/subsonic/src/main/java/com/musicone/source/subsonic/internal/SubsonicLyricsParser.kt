package com.musicone.source.subsonic.internal

import org.json.JSONObject

internal data class ParsedLyricWord(val startMs: Long, val durationMs: Long, val text: String)
internal data class ParsedLyricLine(val startMs: Long, val text: String, val words: List<ParsedLyricWord> = emptyList(),
    val translation: String? = null)

/** 歌词解析器版本；改变解析结果时递增，宿主据此丢弃旧缓存。 */
internal const val SUBSONIC_LYRIC_PARSER_VERSION = 1

private val LRC_TIME = Regex("""^\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)]""")
private val LRC_WORD_TIME = Regex("""<(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)>""")
private val LRC_OFFSET = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)

/**
 * OpenSubsonic getLyricsBySongId 的结构化歌词。取第一份同步歌词为正文；
 * 另一种语言的同步歌词按相同时间配成翻译。没有时间轴的结果不伪造同步，
 * 但文本本身若是 LRC 会按 LRC 解析。
 */
internal fun parseStructuredLyrics(response: JSONObject): List<ParsedLyricLine>? {
    val entries = response.optJSONObject("lyricsList")?.objects("structuredLyrics").orEmpty()
    val synced = entries.filter { it.optBoolean("synced") }
    val main = synced.firstOrNull()
    if (main != null) {
        val lines = structuredLines(main)
        if (lines.isEmpty()) return null
        val mainLanguage = main.optString("lang")
        val other = synced.drop(1).firstOrNull { it.optString("lang").isNotBlank() && it.optString("lang") != mainLanguage }
        val translations = other?.let(::structuredLines)?.associate { it.startMs to it.text }.orEmpty()
        return mergeSameTimeTranslations(lines).map { line ->
            val translation = line.translation ?: translations[line.startMs]?.takeIf { it != line.text }
            line.copy(translation = translation)
        }
    }
    // 部分服务器把带时间标签的 LRC 原文放在未同步结果里。
    val text = entries.firstOrNull()?.objects("line")?.joinToString("\n") { it.optString("value") }.orEmpty()
    return parseLrc(text)
}

private fun structuredLines(entry: JSONObject): List<ParsedLyricLine> {
    // OpenSubsonic 约定 offset 为正表示歌词提前出现。
    val offset = entry.optLong("offset", 0L)
    return entry.objects("line").mapNotNull { line ->
        if (!line.has("start")) return@mapNotNull null
        val text = line.optString("value").trim()
        if (text.isBlank()) null else ParsedLyricLine((line.optLong("start") - offset).coerceAtLeast(0), text)
    }.sortedBy { it.startMs }
}

/** 解析 LRC；支持多时间标签、[offset:]、增强格式的逐字时间。没有任何时间标签时返回 null。 */
internal fun parseLrc(text: String): List<ParsedLyricLine>? {
    if (text.isBlank()) return null
    var offset = 0L
    val parsed = mutableListOf<ParsedLyricLine>()
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        LRC_OFFSET.find(line)?.let { offset = it.groupValues[1].toLongOrNull() ?: 0L; return@forEach }
        var rest = line
        val times = mutableListOf<Long>()
        while (true) {
            val match = LRC_TIME.find(rest) ?: break
            times += lrcTime(match.groupValues[1], match.groupValues[2]) ?: break
            rest = rest.substring(match.value.length)
        }
        if (times.isEmpty()) return@forEach
        val words = enhancedWords(rest)
        val content = if (words.isEmpty()) rest.trim() else words.joinToString("") { it.text }.trim()
        if (content.isBlank()) return@forEach
        times.forEach { time -> parsed += ParsedLyricLine(time, content, words.takeIf { times.size == 1 }.orEmpty()) }
    }
    if (parsed.isEmpty()) return null
    val shifted = parsed.map { line ->
        line.copy(startMs = (line.startMs - offset).coerceAtLeast(0),
            words = line.words.map { it.copy(startMs = (it.startMs - offset).coerceAtLeast(0)) })
    }.withIndex().sortedWith(compareBy({ it.value.startMs }, { it.index })).map { it.value }
    return mergeSameTimeTranslations(closeLastWords(shifted))
}

/** 同一时间的连续两行按“原文 + 翻译”合并，这是中文 LRC 常见的双语写法。 */
private fun mergeSameTimeTranslations(lines: List<ParsedLyricLine>): List<ParsedLyricLine> {
    val result = mutableListOf<ParsedLyricLine>()
    for (line in lines) {
        val previous = result.lastOrNull()
        if (previous != null && previous.startMs == line.startMs && previous.translation == null && line.words.isEmpty()) {
            result[result.lastIndex] = previous.copy(translation = line.text)
        } else result += line
    }
    return result
}

/** 增强 LRC：<mm:ss.xx>字<mm:ss.xx>字<mm:ss.xx>，末尾标签表示最后一个字的结束。 */
private fun enhancedWords(text: String): List<ParsedLyricWord> {
    val matches = LRC_WORD_TIME.findAll(text).toList()
    if (matches.isEmpty()) return emptyList()
    val words = mutableListOf<ParsedLyricWord>()
    matches.forEachIndexed { index, match ->
        val start = lrcTime(match.groupValues[1], match.groupValues[2]) ?: return emptyList()
        val end = matches.getOrNull(index + 1)
        val piece = text.substring(match.range.last + 1, end?.range?.first ?: text.length)
        if (piece.isEmpty()) return@forEachIndexed
        val next = end?.let { lrcTime(it.groupValues[1], it.groupValues[2]) }
        words += ParsedLyricWord(start, if (next != null && next > start) next - start else -1, piece)
    }
    // 整行文本会去掉首尾空白，逐字文本同步去掉，保证两者拼接一致。
    if (words.isNotEmpty()) {
        words[0] = words[0].copy(text = words[0].text.trimStart())
        words[words.lastIndex] = words.last().copy(text = words.last().text.trimEnd())
    }
    return words.filter { it.text.isNotEmpty() }
}

/** 没有结束标签的最后一个字，延续到下一行开始，最长 3 秒。 */
private fun closeLastWords(lines: List<ParsedLyricLine>): List<ParsedLyricLine> = lines.mapIndexed { index, line ->
    if (line.words.none { it.durationMs < 0 }) return@mapIndexed line
    val nextLine = lines.drop(index + 1).firstOrNull { it.startMs > line.startMs }?.startMs
    line.copy(words = line.words.map { word ->
        if (word.durationMs >= 0) word
        else word.copy(durationMs = ((nextLine ?: (word.startMs + 3_000)) - word.startMs).coerceIn(0, 3_000))
    })
}

private fun lrcTime(minutes: String, seconds: String): Long? {
    val minuteValue = minutes.toLongOrNull() ?: return null
    val parts = seconds.split('.', ':')
    val secondValue = parts[0].toLongOrNull() ?: return null
    val fraction = parts.getOrNull(1).orEmpty()
    val fractionMs = when (fraction.length) {
        0 -> 0L
        1 -> fraction.toLong() * 100
        2 -> fraction.toLong() * 10
        else -> fraction.take(3).toLong()
    }
    return minuteValue * 60_000 + secondValue * 1_000 + fractionMs
}
