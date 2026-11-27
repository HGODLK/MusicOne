package com.musicone

private val localLineStamp = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,6}))?]")
private val localWordStamp = Regex("[<\\[]([0-9]{1,3}):([0-9]{1,2})(?:[.:]([0-9]{1,6}))?[>\\]]")
private val localOffset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
private val xlrcTranslation = Regex("^\\[>[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*](.*)$")
private val xlrcVoice = Regex("^\\[v:[^]\\r\\n]+]")
private val xlrcReading = Regex("([\\u3400-\\u9FFF\\uF900-\\uFAFF]+)\\[([\\u3040-\\u30FF\\u31F0-\\u31FF]+)]")

private fun MatchResult.localMillis(): Long = groupValues[1].toLong() * 60_000 +
    groupValues[2].toLong() * 1_000 + groupValues[3].padEnd(3, '0').take(3).toLong()

private data class LocalLyricRow(
    val start: Long,
    val text: String,
    val words: List<Pair<Long, String>>,
    var end: Long? = null,
    val translations: MutableList<String> = mutableListOf(),
    // 行内 {原文|读音} 注音，text 已去掉标注。
    val ruby: String? = null,
)

/** 解析 LRC/XLRC/SPL 歌词：同一时间戳的多行按文件顺序配对原文和翻译，扩展语法只在对应格式里生效。 */
internal fun parseLocalLrc(raw: String, extension: String): List<TimedLyric> {
    val format = extension.lowercase()
    val spl = format == "spl"
    val xlrc = format == "xlrc"
    val offset = localOffset.find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val rows = mutableListOf<LocalLyricRow>()
    var parents = emptyList<LocalLyricRow>()
    fun mark(text: String): String = if (xlrc) xlrcReading.replace(text) { "{${it.groupValues[1]}|${it.groupValues[2]}}" } else text
    for (source in raw.lineSequence()) {
        val line = source.trim().removePrefix("\uFEFF")
        if (line.isEmpty()) continue
        if (xlrc) {
            val translated = xlrcTranslation.matchEntire(line)
            if (translated != null) {
                val text = lyricRubyPlainText(mark(translated.groupValues[1].replace(xlrcVoice, ""))).trim()
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
        val marked = buildString {
            var position = 0
            for (marker in markers) {
                append(mark(body.substring(position, marker.range.first)))
                position = marker.range.last + 1
            }
            append(mark(body.substring(position)))
        }.trim()
        // 注音标注只影响显示文本，逐字片段里的标注同样去掉。
        val ruby = marked.takeIf(::lyricRubyMarked)
        val text = ruby?.let(::lyricRubyPlainText) ?: marked
        fun plain(piece: String) = if (ruby == null) piece else lyricRubyPlainText(piece)
        if (text.isEmpty()) {
            parents = emptyList()
            continue
        }
        parents = starts.map { stamp ->
            val start = stamp.localMillis()
            var wordStart = start
            var position = 0
            // 末尾无文字的时间戳是结束边界，LRC / XLRC 的 <> 也必须保留。
            val end = markers.lastOrNull()?.takeIf {
                it.range.last == body.lastIndex && it.localMillis() >= start
            }?.localMillis()
            val words = mutableListOf<Pair<Long, String>>()
            val content = StringBuilder()
            for (marker in markers) {
                content.append(mark(body.substring(position, marker.range.first)))
                val time = marker.localMillis()
                if (time >= wordStart && (end == null || time <= end)) {
                    if (content.isNotEmpty()) {
                        words += wordStart to plain(content.toString())
                        content.setLength(0)
                    }
                    wordStart = time
                }
                position = marker.range.last + 1
            }
            content.append(mark(body.substring(position)))
            if (content.isNotEmpty()) words += wordStart to plain(content.toString())
            LocalLyricRow(start, text, if (markers.isEmpty()) emptyList() else words, end, ruby = ruby).also(rows::add)
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
        // 同一时刻去掉标注后与原文相同的行是注音行，不当作翻译（已由上面的过滤排除）。
        val ruby = (listOf(original) + group.drop(1)).firstNotNullOfOrNull { row ->
            row.ruby?.takeIf { row.text == original.text }
        }
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
            transliteration = ruby,
        )
    }
}

/** 判断 [] 是不是行内逐字时间戳（正文后面跟着时间标签）；连续写在行首的 [] 只是普通 LRC 复唱。 */
internal fun hasSquareLyricWords(raw: String): Boolean = raw.lineSequence().any { line ->
    val text = line.trim().removePrefix("\uFEFF")
    val first = localLineStamp.matchAt(text, 0) ?: return@any false
    var cursor = first.range.last + 1
    while (true) {
        val repeated = localLineStamp.matchAt(text, cursor) ?: break
        cursor = repeated.range.last + 1
    }
    val next = localLineStamp.find(text, cursor) ?: return@any false
    text.substring(cursor, next.range.first).isNotBlank() && next.localMillis() > first.localMillis()
}
