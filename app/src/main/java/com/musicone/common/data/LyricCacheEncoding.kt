package com.musicone

import org.json.JSONArray
import org.json.JSONObject

// 宿主缓存容器版本独立于各包歌词解析器版本，继续兼容既有第二版格式。
private const val LYRIC_CACHE_FORMAT_VERSION = 2

internal fun encodeCachedLyrics(lyrics: List<TimedLyric>): String = JSONObject()
    .put("version", LYRIC_CACHE_FORMAT_VERSION)
    .put("format", if (lyrics.any { it.words.isNotEmpty() }) "WORD" else "LRC")
    .put("lines", JSONArray().apply {
        lyrics.forEach { line ->
            put(JSONObject().put("time", line.timeMs).put("text", line.text)
                .put("parserVersion", line.parserVersion).apply {
                    line.translation?.takeIf { it.isNotBlank() && !it.trim().matches(Regex("^/+$")) }
                        ?.let { put("translation", it) }
                    line.transliteration?.takeIf { it.isNotBlank() }?.let { put("transliteration", it) }
                    if (line.words.isNotEmpty()) put("words", JSONArray().apply {
                        line.words.forEach { word ->
                            put(JSONObject().put("s", word.startMs).put("e", word.endMs).put("t", word.text))
                        }
                    })
                })
        }
    }).toString()

internal fun decodeCachedLyrics(raw: String): List<TimedLyric> = runCatching {
    val legacy = raw.trimStart().startsWith('[')
    val document = if (legacy) null else JSONObject(raw)
    val current = document?.optInt("version") == LYRIC_CACHE_FORMAT_VERSION
    val lines = if (legacy) JSONArray(raw) else document!!.getJSONArray("lines")
    lines.jsonObjects().map { value ->
        val translation = value.optString("translation")
            .takeIf { it.isNotBlank() && !it.trim().matches(Regex("^/+$")) }
        val words = value.optJSONArray("words")?.let { array ->
            List(array.length()) { index ->
                val word = array.getJSONObject(index)
                LyricWord(word.getLong("s"), word.getLong("e"), word.getString("t"))
            }
        }.orEmpty()
        TimedLyric(value.getLong("time"), value.getString("text"), translation, words,
            if (current) value.optInt("parserVersion", 0) else 0,
            transliteration = value.optString("transliteration").takeIf { it.isNotBlank() })
    }
}.getOrDefault(emptyList())
