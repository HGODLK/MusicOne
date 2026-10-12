package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

internal fun encodeCachedLyrics(lyrics: List<TimedLyric>): String = JSONObject()
    .put("version", CURRENT_LYRIC_PARSER_VERSION)
    .put("format", if (lyrics.any { it.words.isNotEmpty() }) "QRC" else "LRC")
    .put("lines", JSONArray().apply {
        lyrics.forEach { line ->
            put(JSONObject().put("time", line.timeMs).put("text", line.text)
                .put("parserVersion", line.parserVersion).apply {
                    line.translation?.takeIf { it.isNotBlank() && !it.trim().matches(Regex("^/+$")) }
                        ?.let { put("translation", it) }
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
    val current = document?.optInt("version") == CURRENT_LYRIC_PARSER_VERSION
    val lines = if (legacy) JSONArray(raw) else document!!.getJSONArray("lines")
    lines.searchObjects().map { value ->
        val translation = value.optString("translation")
            .takeIf { it.isNotBlank() && !it.trim().matches(Regex("^/+$")) }
        val words = value.optJSONArray("words")?.let { array ->
            List(array.length()) { index ->
                val word = array.getJSONObject(index)
                LyricWord(word.getLong("s"), word.getLong("e"), word.getString("t"))
            }
        }.orEmpty()
        TimedLyric(value.getLong("time"), value.getString("text"), translation, words,
            if (current) value.optInt("parserVersion", 0) else 0)
    }
}.getOrDefault(emptyList())
