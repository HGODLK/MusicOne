package com.musicone.source.kugou

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

private val kugouKrcKey = byteArrayOf(
    0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
    0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
)

internal const val CURRENT_KUGOU_LYRIC_PARSER_VERSION = 3

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
    val translations = language?.let { kugouLanguageRows(it, 1) }.orEmpty().map { it.joinToString("") }
    val romaji = language?.let { kugouLanguageRows(it, 0) }.orEmpty()
    return raw.lineSequence().map { it.trimEnd('\r') }.mapNotNull(linePattern::matchEntire)
        .toList().mapIndexedNotNull { index, match ->
        val start = match.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
        val body = match.groupValues[3]
        val timings = wordPattern.findAll(body).toList()
        val words = ArrayList<LyricWord>(timings.size)
        val wordRomaji = ArrayList<List<RomajiMora>>(timings.size)
        for ((wordIndex, timing) in timings.withIndex()) {
            val offset = timing.groupValues[1].toLongOrNull() ?: return@mapIndexedNotNull null
            val duration = timing.groupValues[2].toLongOrNull() ?: return@mapIndexedNotNull null
            if (offset > Long.MAX_VALUE - start || duration > Long.MAX_VALUE - start - offset) return@mapIndexedNotNull null
            val wordStart = start + offset
            if (words.lastOrNull()?.let { wordStart < it.startMs } == true) return@mapIndexedNotNull null
            val end = timings.getOrNull(wordIndex + 1)?.range?.first ?: body.length
            val text = (if (wordIndex == 0) body.substring(0, timing.range.first) else "") +
                body.substring(timing.range.last + 1, end)
            if (text.isNotEmpty()) {
                words += LyricWord(wordStart, wordStart + duration, text)
                wordRomaji += romajiMoras(romaji.getOrNull(index)?.getOrNull(wordIndex).orEmpty())
            }
        }
        val text = if (timings.isEmpty()) body else words.joinToString("") { it.text }
        if (text.isBlank()) return@mapIndexedNotNull null
        val translation = translations.getOrNull(index)?.takeIf { it.isNotBlank() && it != text }
        TimedLyric(start, text, translation, words, CURRENT_KUGOU_LYRIC_PARSER_VERSION,
            lyricRomajiTransliteration(words.map { it.text }, words.map { it.startMs }, wordRomaji))
    }.sortedBy(TimedLyric::timeMs).let { lines ->
        val texts = lines.map { it.text }
        when {
            lyricHangulLanguageAllowed(texts) ->
                lines.map { line -> line.copy(transliteration = lyricHangulTransliteration(line.words.map { it.text })) }
            lyricRomajiLanguageAllowed(texts) -> lines
            else -> lines.map { it.copy(transliteration = null) }
        }
    }
}

/** language 内容按行、按词排列；type=0 是音译（日语是逐词罗马音，韩语是汉字空耳），type=1 是翻译（整句放在单个元素里）。 */
private fun kugouLanguageRows(encoded: String, type: Int): List<List<String>> = runCatching {
    val json = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
    val contents = json.optJSONArray("content") ?: return@runCatching emptyList()
    val rows = (0 until contents.length()).firstNotNullOfOrNull { index ->
        contents.optJSONObject(index)?.takeIf { it.optInt("type", -1) == type }?.optJSONArray("lyricContent")
    } ?: return@runCatching emptyList()
    (0 until rows.length()).map { lineIndex ->
        val words = rows.optJSONArray(lineIndex)
        (0 until (words?.length() ?: 0)).map { wordIndex -> words?.optString(wordIndex).orEmpty() }
    }
}.getOrDefault(emptyList())

