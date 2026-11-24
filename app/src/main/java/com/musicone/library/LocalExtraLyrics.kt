package com.musicone

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

private const val kscString = "'((?:[^'\\r\\n]|'')*)'"
internal val kscEntry = Regex("(?im)^\\s*karaoke\\.add\\(\\s*$kscString\\s*,\\s*$kscString\\s*,\\s*$kscString\\s*,\\s*$kscString\\s*\\)")
private val kscOffset = Regex("(?im)^\\s*karaoke\\.timeoffset\\s*:=\\s*([+-]?\\d+)")
private val kscWord = Regex("\\[([^]]+)]|\\X")
private val kscPrefixes = setOf("中", "英", "粤", "日", "韩", "法", "西", "德", "俄", "泰", "越", "印", "意", "葡", "男", "女", "合", "男声", "女声", "合唱")

internal fun parseLocalKsc(raw: String): List<TimedLyric> {
    val offset = kscOffset.find(raw)?.groupValues?.get(1)?.toIntOrNull()?.toLong() ?: 0L
    return kscEntry.findAll(raw).mapNotNull { match ->
        val (from, to, body, durations) = match.destructured
        val start = lyricClockMillis(from)?.plus(offset) ?: return@mapNotNull null
        val end = lyricClockMillis(to)?.plus(offset)?.takeIf { it > start } ?: return@mapNotNull null
        var pieces = kscWord.findAll(body.replace("''", "'")).map { it.groupValues[1].ifEmpty { it.value } }.toList()
        var lengths = durations.split(',').map { it.trim().toIntOrNull()?.takeIf { n -> n >= 0 } }
        val first = pieces.firstOrNull().orEmpty()
        if (first in kscPrefixes && (lengths.firstOrNull() == 0 || lengths.size + 1 == pieces.size) || first.endsWith(':') || first.endsWith('：')) {
            if (lengths.size == pieces.size) lengths = lengths.drop(1)
            pieces = pieces.drop(1)
        }
        val text = pieces.joinToString("").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        val durationsMs = lengths.filterNotNull()
        var cursor = start
        val words = if (pieces.size == durationsMs.size && lengths.size == durationsMs.size) {
            pieces.zip(durationsMs).mapNotNull { (piece, duration) ->
                val next = (cursor + duration).coerceAtMost(end)
                LyricWord(cursor, next, piece).also { cursor = next }
            }
        } else emptyList()
        TimedLyric(start, text, words = words)
    }.sortedBy(TimedLyric::timeMs).toList()
}

internal val lyricsfileHeader = Regex("(?m)^version:[ \\t]*['\"]?1\\.0['\"]?[ \\t]*(?:#.*)?$")
internal val lyricsfileTimeline = Regex("(?m)^lines:[ \\t]*")

internal fun parseLocalLyricsfile(raw: String): List<TimedLyric> = runCatching {
    val options = LoaderOptions().apply { maxAliasesForCollections = 0; isAllowDuplicateKeys = false }
    val document = Yaml(SafeConstructor(options)).load<Any>(raw) as? Map<*, *> ?: return emptyList()
    if (document["version"]?.toString() != "1.0") return emptyList()
    val metadata = document["metadata"] as? Map<*, *>
    val offset = metadata?.get("offset_ms")?.toString()?.toIntOrNull()?.toLong() ?: 0L
    val rows = (document["lines"] as? List<*>)?.filterIsInstance<Map<*, *>>() ?: return emptyList()
    fun Map<*, *>.time(key: String): Long? = this[key]?.toString()?.toLongOrNull()?.takeIf { it >= 0 && it <= Long.MAX_VALUE - Int.MAX_VALUE }
    val sorted = rows.mapNotNull { row -> row.time("start_ms")?.let { it to row } }.sortedBy { it.first }
    sorted.mapIndexedNotNull { index, (start, row) ->
        val end = row.time("end_ms") ?: sorted.getOrNull(index + 1)?.first
        val pieces = (row["words"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
        val words = pieces.mapIndexedNotNull { i, word ->
            val from = word.time("start_ms") ?: return@mapIndexedNotNull null
            val to = word.time("end_ms") ?: pieces.getOrNull(i + 1)?.time("start_ms") ?: end
            val text = word["text"] as? String ?: return@mapIndexedNotNull null
            LyricWord(from + offset, maxOf(from, to ?: from) + offset, text)
        }.takeIf { it.size == pieces.size && it.zipWithNext().all { (a, b) -> a.startMs <= b.startMs } }.orEmpty()
        val text = words.joinToString("") { it.text }.ifBlank { row["text"] as? String ?: "" }
        if (text.isBlank()) null else TimedLyric(start + offset, text, words = words)
    }
}.getOrDefault(emptyList())
