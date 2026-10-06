package com.musicone.demo

import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.zip.InflaterInputStream

private val kugouKrcKey = byteArrayOf(
    0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
    0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
)

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
    val wordPattern = Regex("<\\d+,\\d+,\\d+>")
    val language = Regex("(?m)^\\[language:([^]]+)]$").find(raw)?.groupValues?.getOrNull(1)
    val translations = language?.let(::kugouTranslations).orEmpty()
    return raw.lineSequence().mapNotNull(linePattern::matchEntire).toList().mapIndexedNotNull { index, match ->
        val text = match.groupValues[3].replace(wordPattern, "").trim()
        if (text.isBlank()) return@mapIndexedNotNull null
        val translation = translations.getOrNull(index)?.takeIf { it.isNotBlank() && it != text }
        TimedLyric(match.groupValues[1].toLong(), text, translation)
    }
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
