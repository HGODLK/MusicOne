package com.musicone

import kotlin.io.encoding.Base64
import org.json.JSONObject

private const val millisHeader = "\\[(\\d+),(\\d+)]"
private val millisLine = Regex("^$millisHeader(.*)$")
private val lysLine = Regex("^\\[(\\d{1,2})](.*)$")
private val qrcWord = Regex("\\((\\d+),(\\d+)\\)")
private val yrcWord = Regex("\\((\\d+),(\\d+),-?\\d+\\)")
private val krcWord = Regex("<(\\d+),(\\d+),-?\\d+>")
internal val qrcSignature = Regex("(?m)^$millisHeader.+${qrcWord.pattern}")
internal val yrcSignature = Regex("(?m)^$millisHeader.*${yrcWord.pattern}")
internal val krcSignature = Regex("(?m)^$millisHeader.*${krcWord.pattern}")
internal val lysSignature = Regex("(?m)^${lysLine.pattern.removeSuffix("(.*)$")}.+${qrcWord.pattern}")

internal fun parseLocalQrc(raw: String): List<TimedLyric> {
    val text = if (raw.startsWith('<')) {
        // QQ 的 LyricContent 属性也可包含真实换行，不能让 XML 解析器把它归一为空格。
        Regex("\\bLyricContent\\s*=\\s*([\"'])([\\s\\S]*?)\\1").find(raw)?.groupValues?.get(2)
            ?.let(::decodeLyricEntities) ?: return emptyList()
    } else raw
    return parseLocalMillisLyrics(text, "qrc")
}

internal fun parseLocalMillisLyrics(raw: String, format: String): List<TimedLyric> {
    val pattern = when (format) { "krc" -> krcWord; "yrc" -> yrcWord; else -> qrcWord }
    val suffix = format == "qrc" || format == "lys"
    val language = if (format == "krc") readKrcLanguage(raw) else emptyMap()
    return raw.lineSequence().map { it.trim().removePrefix("\uFEFF") }.mapNotNull {
        if (format == "lys") lysLine.matchEntire(it) else millisLine.matchEntire(it)
    }.mapIndexedNotNull { index, match ->
        val lys = format == "lys"
        val body = match.groupValues[if (lys) 2 else 3]
        val start = if (lys) null else match.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
        val markers = if (format == "lyl") emptyList() else pattern.findAll(body).toList()
        if (markers.isEmpty() && lys) return@mapIndexedNotNull null
        val words = ArrayList<LyricWord>(markers.size)
        for ((i, marker) in markers.withIndex()) {
            val offset = marker.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
            val wordStart = if (format == "krc") lyricEnd(start ?: 0L, offset) ?: return@mapIndexedNotNull null else offset
            val duration = marker.groupValues[2].toLongOrNull() ?: return@mapIndexedNotNull null
            val end = lyricEnd(wordStart, duration) ?: return@mapIndexedNotNull null
            if (words.lastOrNull()?.let { it.startMs > wordStart } == true) return@mapIndexedNotNull null
            val from = markers.getOrNull(i - 1)?.range?.last?.plus(1) ?: 0
            val to = markers.getOrNull(i + 1)?.range?.first ?: body.length
            val piece = if (suffix) body.substring(from, marker.range.first) +
                (if (i == markers.lastIndex) body.substring(marker.range.last + 1) else "")
            else (if (i == 0) body.substring(0, marker.range.first) else "") + body.substring(marker.range.last + 1, to)
            if (piece.isNotEmpty()) words += LyricWord(wordStart, end, piece)
        }
        val text = (if (markers.isEmpty()) body else words.joinToString("") { it.text }).trim()
        if (text.isBlank() || text.all { it == '/' }) return@mapIndexedNotNull null
        val rowStart = start ?: words.firstOrNull()?.startMs ?: return@mapIndexedNotNull null
        val translation = language[1]?.getOrNull(index)?.joinToString("")?.takeIf { it.isNotBlank() && it != text }
        val readings = language[0]?.getOrNull(index)
        val ruby = if (readings?.size == words.size && words.isNotEmpty()) {
            words.zip(readings).joinToString("") { (word, reading) -> localReading(word.text, reading) ?: word.text }
                .takeIf(::lyricRubyMarked)
        } else null
        TimedLyric(rowStart, text, translation, words, transliteration = ruby)
    }.sortedBy(TimedLyric::timeMs).toList()
}

private fun readKrcLanguage(raw: String): Map<Int, List<List<String>>> = runCatching {
    val encoded = Regex("(?m)^\\[language:([^]]+)]\\s*$").find(raw)?.groupValues?.get(1) ?: return emptyMap()
    val content = JSONObject(Base64.Mime.decode(encoded).decodeToString()).getJSONArray("content")
    (0 until content.length()).associate { i ->
        val block = content.getJSONObject(i)
        val rows = block.getJSONArray("lyricContent")
        block.getInt("type") to List(rows.length()) { row ->
            val words = rows.getJSONArray(row)
            List(words.length()) { word -> words.optString(word) }
        }
    }
}.getOrDefault(emptyMap())

private val lqeSection = Regex("^\\[(lyrics|translation|pronunciation):\\s*format@([^,\\]]+)[^]]*]$", RegexOption.IGNORE_CASE)

internal fun parseLocalLqe(raw: String): List<TimedLyric> {
    val sections = linkedMapOf<String, Pair<String, StringBuilder>>()
    var body: StringBuilder? = null
    for (line in raw.lineSequence()) {
        val section = lqeSection.matchEntire(line.trim())
        if (section != null) {
            val name = section.groupValues[1].lowercase()
            body = StringBuilder()
            sections[name] = section.groupValues[2].trim().lowercase() to body
        } else body?.appendLine(line)
    }
    fun read(name: String): List<TimedLyric> {
        val (format, content) = sections[name] ?: return emptyList()
        return when (format) {
            "lyricify syllable", "lys" -> parseLocalMillisLyrics(content.toString(), "lys")
            "lyricify lines", "lyl" -> parseLocalMillisLyrics(content.toString(), "lyl")
            "lrc", "enhanced lrc", "eslyric" -> parseLibraryLyrics(content.toString())
            "qrc" -> parseLocalQrc(content.toString())
            "yrc", "krc" -> parseLocalMillisLyrics(content.toString(), format)
            else -> emptyList()
        }
    }
    val translations = read("translation").associateBy { it.timeMs }
    val pronunciations = read("pronunciation").associateBy { it.timeMs }
    return read("lyrics").map { line ->
        line.copy(translation = translations[line.timeMs]?.text ?: line.translation,
            transliteration = localReading(line.text, pronunciations[line.timeMs]?.text) ?: line.transliteration)
    }
}
