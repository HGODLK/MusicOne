package com.musicone.demo

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

private val kugouKrcKey = byteArrayOf(
    0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
    0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
)

internal const val CURRENT_KUGOU_LYRIC_PARSER_VERSION = 1

internal fun parseKugouKrcBase64(content: String): List<TimedLyric> = runCatching {
    val encrypted = Base64.decode(content, Base64.DEFAULT)
    if (encrypted.size <= 4) return@runCatching emptyList()
    val compressed = ByteArray(encrypted.size - 4) { index ->
        (encrypted[index + 4].toInt() xor kugouKrcKey[index % kugouKrcKey.size].toInt()).toByte()
    }
    val raw = InflaterInputStream(ByteArrayInputStream(compressed)).use { it.readBytes().toString(Charsets.UTF_8) }
    parseKugouKrc(raw)
}.getOrDefault(emptyList())

internal fun parseKugouKrc(raw: String): List<TimedLyric> {
    val linePattern = Regex("^\\[(\\d+),(\\d+)](.*)$")
    val wordPattern = Regex("<(\\d+),(\\d+),\\d+>")
    val language = Regex("(?m)^\\[language:([^]]+)]$").find(raw)?.groupValues?.getOrNull(1)
    val translations = language?.let(::kugouTranslations).orEmpty()
    return raw.lineSequence().map { it.trimEnd('\r') }.mapNotNull(linePattern::matchEntire)
        .toList().mapIndexedNotNull { index, match ->
        val start = match.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
        val body = match.groupValues[3]
        val timings = wordPattern.findAll(body).toList()
        val words = ArrayList<LyricWord>(timings.size)
        for ((wordIndex, timing) in timings.withIndex()) {
            val offset = timing.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
            val duration = timing.groupValues[2].toLongOrNull() ?: return@mapIndexedNotNull null
            if (offset > Long.MAX_VALUE - start || duration > Long.MAX_VALUE - start - offset) return@mapIndexedNotNull null
            val wordStart = start + offset
            if (words.lastOrNull()?.let { wordStart < it.startMs } == true) return@mapIndexedNotNull null
            val end = timings.getOrNull(wordIndex + 1)?.range?.first ?: body.length
            val text = (if (wordIndex == 0) body.substring(0, timing.range.first) else "") +
                body.substring(timing.range.last + 1, end)
            if (text.isNotEmpty()) words += LyricWord(wordStart, wordStart + duration, text)
        }
        val text = if (timings.isEmpty()) body else words.joinToString("") { it.text }
        if (text.isBlank()) return@mapIndexedNotNull null
        val translation = translations.getOrNull(index)?.takeIf { it.isNotBlank() && it != text }
        TimedLyric(start, text, translation, words, CURRENT_KUGOU_LYRIC_PARSER_VERSION)
    }.sortedBy(TimedLyric::timeMs)
}

private fun kugouTranslations(encoded: String): List<String> = runCatching {
    val json = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
    val contents = json.optJSONArray("content") ?: return@runCatching emptyList()
    val translated = (0 until contents.length()).firstNotNullOfOrNull { index ->
        contents.optJSONObject(index)?.takeIf { it.optInt("type", -1) == 1 }?.optJSONArray("lyricContent")
    } ?: return@runCatching emptyList()
    (0 until translated.length()).map { lineIndex ->
        val words = translated.optJSONArray(lineIndex)
        (0 until (words?.length() ?: 0)).joinToString("") { wordIndex -> words?.optString(wordIndex).orEmpty() }
    }
}.getOrDefault(emptyList())
