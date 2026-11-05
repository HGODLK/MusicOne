package com.musicone

/**
 * 按原文位置标注的注音：在整行原文里用 {原文片段|读音} 标出要注音的片段，不依赖逐字数据。
 * 例：原文“今日は晴れ”，注音写作“{今日|きょう}は{晴|は}れ”。读音里的空格分隔各个音。
 */
internal data class LyricRubyAnchor(val start: Int, val end: Int, val reading: String)

private val lyricRubyAnchorMark = Regex("\\{([^{}|\\n]+)\\|([^{}|\\n]+)\\}")

/** 是否含有位置标注；不含时仍按逐词制表符格式解析。 */
internal fun lyricRubyMarked(value: String?): Boolean = value != null && lyricRubyAnchorMark.containsMatchIn(value)

/** 去掉标注只保留原文片段。 */
internal fun lyricRubyPlainText(marked: String): String = lyricRubyAnchorMark.replace(marked) { it.groupValues[1] }

/** 去掉标注后必须与原文逐字一致，否则整句不显示，避免错位注音上屏。 */
internal fun lyricRubyAnchors(text: String, marked: String?): List<LyricRubyAnchor>? {
    if (marked.isNullOrBlank() || '{' !in marked) return null
    val anchors = ArrayList<LyricRubyAnchor>()
    val plain = StringBuilder(text.length)
    var position = 0
    for (match in lyricRubyAnchorMark.findAll(marked)) {
        plain.append(marked, position, match.range.first)
        val base = match.groupValues[1]
        val reading = match.groupValues[2].trim()
        if (base.isBlank() || reading.isEmpty()) return null
        val start = plain.length
        plain.append(base)
        anchors += LyricRubyAnchor(start, plain.length, reading)
        position = match.range.last + 1
    }
    plain.append(marked, position, marked.length)
    return anchors.takeIf { it.isNotEmpty() && plain.toString() == text }
}

/** 按位置把注音写回原文，供改写原文（例如插入禁断字符）后重新生成标注。 */
internal fun lyricRubyMark(text: String, anchors: List<LyricRubyAnchor>): String = buildString(text.length + anchors.size * 8) {
    var position = 0
    for (anchor in anchors.sortedBy { it.start }) {
        append(text, position, anchor.start)
        append('{').append(text, anchor.start, anchor.end).append('|').append(anchor.reading).append('}')
        position = anchor.end
    }
    append(text, position, text.length)
}

/** 逐词制表符格式转换为位置标注，写入本地 LRC 时两种来源统一成一种文本。 */
internal fun lyricRubyAnchoredText(line: TimedLyric): String? {
    if (lyricRubyAnchors(line.text, line.transliteration) != null) return line.transliteration
    val groups = lyricRubyGroups(line, emptyList()) ?: return null
    return lyricRubyMark(line.text, groups.map { LyricRubyAnchor(it.textStart, it.textEnd, it.moras.joinToString(" ")) })
}
