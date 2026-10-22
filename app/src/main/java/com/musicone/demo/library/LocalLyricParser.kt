package com.musicone.demo

internal val localLyricExtensions = setOf("lrc", "xlrc", "spl")

internal fun localLyricExtension(name: String): String =
    name.substringAfterLast('.', "").lowercase().takeIf { it in localLyricExtensions } ?: "lrc"

private val localLineStamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,6}))?]")
private val localWordStamp = Regex("[<\\[]([0-9]{1,3}):([0-9]{1,2})(?:[.:]([0-9]{1,6}))?[>\\]]")
private val localOffset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
private val xlrcTranslation = Regex("^\\[>[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*](.*)$")
private val xlrcVoice = Regex("^\\[v:[^]\\r\\n]+]")
private val xlrcReading = Regex("(?<=[\\u3400-\\u9FFF\\uF900-\\uFAFF])\\[[\\u3040-\\u30FF\\u31F0-\\u31FF]+]")

private fun MatchResult.localMillis(): Long = groupValues[1].toLong() * 60_000 +
    groupValues[2].toLong() * 1_000 + groupValues[3].padEnd(3, '0').take(3).toLong()

private data class LocalLyricRow(
    val start: Long,
    val text: String,
    val words: List<Pair<Long, String>>,
    var end: Long? = null,
    val translations: MutableList<String> = mutableListOf(),
)

/** LRC 同时刻按文件顺序配对；XLRC 和 SPL 的扩展标记只在各自格式内解释。 */
internal fun parseLibraryLyrics(raw: String, extension: String = "lrc"): List<TimedLyric> {
    val format = extension.lowercase()
    val spl = format == "spl"
    val xlrc = format == "xlrc"
    val offset = localOffset.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val rows = mutableListOf<LocalLyricRow>()
    var parents = emptyList<LocalLyricRow>()
    fun clean(text: String): String = if (xlrc) text.replace(xlrcReading, "") else text
    for (source in raw.lineSequence()) {
        val line = source.trim().removePrefix("\uFEFF")
        if (line.isEmpty()) continue
        if (xlrc) {
            val translated = xlrcTranslation.matchEntire(line)
            if (translated != null) {
                val text = clean(translated.groupValues[1].replace(xlrcVoice, "")).trim()
                if (text.isNotEmpty()) parents.forEach { it.translations += text }
                continue
            }
        }
        val starts = mutableListOf<MatchResult>()
        var cursor = 0
        while (true) {
            val stamp = localLineStamp.find(line, cursor)?.takeIf { it.range.first == cursor } ?: break
            starts += stamp
            cursor = stamp.range.last + 1
        }
        if (starts.isEmpty()) {
            // SPL 无时间戳译文只属于紧邻的歌词块，元数据会终止这个块。
            if (spl && !line.startsWith('[') && !line.startsWith('<')) {
                parents.forEach { it.translations += line }
            } else parents = emptyList()
            continue
        }
        var body = line.substring(cursor).trim()
        if (xlrc) body = body.replace(xlrcVoice, "")
        if (body.isBlank()) {
            val end = starts.first().localMillis()
            val startsToEnd = parents.map { it.start }.toSet()
            rows.filter { it.start in startsToEnd }.forEach { if (end >= it.start) it.end = end }
            parents = emptyList()
            continue
        }
        // SPL 的 [] 和 <> 都可作为行内逐字标记；LRC / XLRC 保持 <> 语法。
        val markers = localWordStamp.findAll(body).filter {
            it.value.startsWith('<') && it.value.endsWith('>') ||
                spl && it.value.startsWith('[') && it.value.endsWith(']')
        }.toList()
        val text = buildString {
            var position = 0
            for (marker in markers) {
                append(clean(body.substring(position, marker.range.first)))
                position = marker.range.last + 1
            }
            append(clean(body.substring(position)))
        }.trim()
        if (text.isEmpty()) {
            parents = emptyList()
            continue
        }
        parents = starts.map { stamp ->
            val start = stamp.localMillis()
            var wordStart = start
            var position = 0
            val end = markers.lastOrNull()?.takeIf {
                spl && it.value.startsWith('[') && it.range.last == body.lastIndex && it.localMillis() >= start
            }?.localMillis()
            val words = mutableListOf<Pair<Long, String>>()
            val content = StringBuilder()
            for (marker in markers) {
                content.append(clean(body.substring(position, marker.range.first)))
                val time = marker.localMillis()
                if (time >= wordStart && (end == null || time <= end)) {
                    if (content.isNotEmpty()) {
                        words += wordStart to content.toString()
                        content.setLength(0)
                    }
                    wordStart = time
                }
                position = marker.range.last + 1
            }
            content.append(clean(body.substring(position)))
            if (content.isNotEmpty()) words += wordStart to content.toString()
            LocalLyricRow(start, text, if (markers.isEmpty()) emptyList() else words, end).also(rows::add)
        }
    }
    val sorted = rows.sortedBy { it.start }
    val groups = if (xlrc) sorted.map { listOf(it) } else sorted.groupBy { it.start }.values.toList()
    return groups.mapIndexed { index, group ->
        val original = group.first()
        val translations = buildList {
            addAll(original.translations)
            for (translated in group.drop(1)) {
                add(translated.text)
                addAll(translated.translations)
            }
        }.filter { it.isNotBlank() && it != original.text }.distinct()
        val end = group.mapNotNull { it.end }.minOrNull() ?: groups.asSequence().drop(index + 1).map { it.first().start }
            .firstOrNull { it > original.start }
        TimedLyric(
            timeMs = original.start + offset,
            text = original.text,
            translation = translations.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            words = original.words.mapIndexed { wordIndex, (start, text) ->
                val next = original.words.getOrNull(wordIndex + 1)?.first ?: end ?: (start + 500)
                LyricWord(start + offset, maxOf(start, next) + offset, text)
            },
        )
    }
}
