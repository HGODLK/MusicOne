package com.musicone.demo

internal data class LyricWordRange(val word: LyricWord, val start: Int, val end: Int)

/**
 * 歌词正文显示范围与明暗时间来源映射。
 * [word] 为 null 时表示该范围保持固定基础亮度（1.0f），不虚构播放时间。
 */
internal data class LyricHighlightRange(
    val start: Int,
    val end: Int,
    val word: LyricWord?,
)

internal fun lyricWordRanges(line: TimedLyric): List<LyricWordRange> {
    var cursor = 0
    return line.words.mapNotNull { word ->
        val exact = line.text.indexOf(word.text, cursor)
        val text = if (exact >= 0) word.text else word.text.trim()
        val start = if (exact >= 0) exact else line.text.indexOf(text, cursor)
        if (text.isEmpty() || start < 0) return@mapNotNull null
        val end = (start + text.length).coerceAtMost(line.text.length)
        cursor = end
        LyricWordRange(word, start, end)
    }
}

private enum class PunctuationType {
    NONE,
    OPENING,
    CLOSING,
    WORD_INTERNAL,
}

private fun isOpeningPunctuationChar(c: Char): Boolean = when (c) {
    '“', '‘', '『', '「', '《', '〈', '(', '（', '[', '【', '{', '〔', '⟨', '«' -> true
    else -> false
}

private fun isClosingOrClausePunctuationChar(c: Char): Boolean = when (c) {
    '”', '’', '』', '」', '》', '〉', ')', '）', ']', '】', '}', '〕', '⟩', '»',
    ',', '，', '.', '。', '、', '?', '？', '!', '！', ':', '：', ';', '；',
    '…', '—', '–', '~', '～', '·' -> true
    else -> false
}

private fun classifyPunctuation(text: String, index: Int): PunctuationType {
    val c = text[index]
    if (isOpeningPunctuationChar(c)) return PunctuationType.OPENING
    if (isClosingOrClausePunctuationChar(c)) return PunctuationType.CLOSING
    if (c == '"') {
        var count = 0
        for (i in 0 until index) {
            if (text[i] == '"') count++
        }
        return if (count % 2 == 0) PunctuationType.OPENING else PunctuationType.CLOSING
    }
    if (c == '\'') {
        val beforeLetter = index > 0 && text[index - 1].isLetter()
        val afterLetter = index + 1 < text.length && text[index + 1].isLetter()
        if (beforeLetter && afterLetter) return PunctuationType.WORD_INTERNAL
        var count = 0
        for (i in 0 until index) {
            if (text[i] == '\'') count++
        }
        return if (count % 2 == 0) PunctuationType.OPENING else PunctuationType.CLOSING
    }
    return PunctuationType.NONE
}

private fun isPurePunctuation(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return true
    return trimmed.all {
        isOpeningPunctuationChar(it) || isClosingOrClausePunctuationChar(it) || it == '"' || it == '\''
    }
}

private fun safeBoundary(text: String, index: Int): Int {
    if (index <= 0) return 0
    if (index >= text.length) return text.length
    if (Character.isLowSurrogate(text[index])) return index - 1
    return index
}

/**
 * 为整句歌词计算“显示范围 → 明暗时间来源”的映射。
 * 遵循规则：
 * 1. 词本身包含标点时，整段沿用该词进度；
 * 2. 独立的后置标点（逗号、句号、闭括号等）优先跟随前一个有效词；
 * 3. 独立的前置标点（开括号、前引号等）优先跟随后一个有效词；
 * 4. 零时长的纯标点切片不单独瞬间点亮，按上述归属跟随相邻词；
 * 5. 无合适相邻词的独立标点保持固定亮度（1.0f），不虚构播放时间；
 * 6. 范围边界保护 UTF-16 代理对，互斥无重叠。
 */
internal fun lyricWordHighlightRanges(line: TimedLyric): List<LyricHighlightRange> {
    if (line.text.isEmpty()) return emptyList()

    // 过滤出具有有效时间推进的切片词（排除零时长的纯标点词切片，防止其瞬间单独跳亮）
    var cursor = 0
    val matchedWords = mutableListOf<LyricWordRange>()
    for (word in line.words) {
        val isZeroDurationPunct = word.endMs <= word.startMs && isPurePunctuation(word.text)
        val exact = line.text.indexOf(word.text, cursor)
        val text = if (exact >= 0) word.text else word.text.trim()
        val start = if (exact >= 0) exact else if (text.isNotEmpty()) line.text.indexOf(text, cursor) else -1
        if (text.isEmpty() || start < 0) continue
        val end = (start + text.length).coerceAtMost(line.text.length)
        cursor = end
        if (!isZeroDurationPunct) {
            matchedWords.add(LyricWordRange(word, start, end))
        }
    }

    if (matchedWords.isEmpty()) {
        return listOf(LyricHighlightRange(0, line.text.length, null))
    }

    // 预备各词扩展后的起止索引
    val n = matchedWords.size
    val starts = IntArray(n) { matchedWords[it].start }
    val ends = IntArray(n) { matchedWords[it].end }

    // 1. 首词前的前导符号归属（开引号、前括号跟随后续首词）
    var prefixUnattached = 0
    for (i in 0 until starts[0]) {
        val type = classifyPunctuation(line.text, i)
        if (type == PunctuationType.OPENING || line.text[i].isWhitespace()) {
            starts[0] = i
            break
        } else if (type == PunctuationType.CLOSING) {
            prefixUnattached = i + 1
        }
    }

    // 2. 词间符号归属（后置标点向前归属，前置标点向后归属）
    for (i in 0 until n - 1) {
        val gapStart = ends[i]
        val gapEnd = starts[i + 1]
        if (gapStart >= gapEnd) continue

        // 从左向右寻找属于前词的后置标点边界
        var forward = gapStart
        while (forward < gapEnd) {
            val type = classifyPunctuation(line.text, forward)
            if (type == PunctuationType.CLOSING || type == PunctuationType.WORD_INTERNAL) {
                forward++
            } else if (type == PunctuationType.NONE && line.text[forward].isWhitespace()) {
                val hasMoreClosing = (forward until gapEnd).any {
                    classifyPunctuation(line.text, it) == PunctuationType.CLOSING
                }
                if (hasMoreClosing) forward++ else break
            } else {
                break
            }
        }

        // 从右向左寻找属于后词的前置标点边界
        var backward = gapEnd
        while (backward > forward) {
            val type = classifyPunctuation(line.text, backward - 1)
            if (type == PunctuationType.OPENING) {
                backward--
            } else if (type == PunctuationType.NONE && line.text[backward - 1].isWhitespace()) {
                val hasMoreOpening = (gapStart until backward).any {
                    classifyPunctuation(line.text, it) == PunctuationType.OPENING
                }
                if (hasMoreOpening) backward-- else break
            } else {
                break
            }
        }

        // 若前置标点与后置标点之间仅有空白符，将其并入前词，避免产生多余的独立空白片段
        if (forward < backward && (forward until backward).all { line.text[it].isWhitespace() }) {
            forward = backward
        }

        ends[i] = forward
        starts[i + 1] = backward
    }

    // 3. 尾词后的结尾标点归属（句号、感叹号、闭引号等向前归属尾词）
    var suffixEnd = ends[n - 1]
    while (suffixEnd < line.text.length) {
        val type = classifyPunctuation(line.text, suffixEnd)
        if (type == PunctuationType.CLOSING || line.text[suffixEnd].isWhitespace()) {
            suffixEnd++
        } else {
            break
        }
    }
    ends[n - 1] = suffixEnd

    // 4. 构建互斥无重叠且覆盖全句的范围列表
    val result = mutableListOf<LyricHighlightRange>()
    var currentPos = 0

    if (prefixUnattached > 0) {
        val safePrefix = safeBoundary(line.text, prefixUnattached)
        if (safePrefix > currentPos) {
            result.add(LyricHighlightRange(currentPos, safePrefix, null))
            currentPos = safePrefix
        }
    }

    for (i in 0 until n) {
        val wordStart = safeBoundary(line.text, maxOf(currentPos, starts[i]))
        if (wordStart > currentPos) {
            result.add(LyricHighlightRange(currentPos, wordStart, null))
            currentPos = wordStart
        }
        val wordEnd = safeBoundary(line.text, maxOf(wordStart, ends[i]))
        if (wordEnd > wordStart) {
            result.add(LyricHighlightRange(wordStart, wordEnd, matchedWords[i].word))
            currentPos = wordEnd
        }
    }

    if (currentPos < line.text.length) {
        result.add(LyricHighlightRange(currentPos, line.text.length, null))
    }

    return result
}

/** 当前句已由行级样式强调，词级只在保留对比度的范围内柔和变化。 */
internal fun lyricWordAlpha(word: LyricWord, positionMs: Long): Float = when {
    positionMs >= word.endMs -> 1f
    positionMs < word.startMs -> .7f
    else -> .7f + .3f * ((positionMs - word.startMs).toFloat() /
        (word.endMs - word.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
}

internal fun lyricHighlightAlpha(word: LyricWord?, positionMs: Long): Float {
    if (word == null) return 1f
    return lyricWordAlpha(word, positionMs)
}
