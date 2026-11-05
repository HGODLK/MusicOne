package com.musicone

/** 不占宽度，只禁止两侧断行。 */
private const val LYRIC_WORD_JOINER = '⁠'

private fun Char.isHangul(): Boolean = this in '가'..'힣' || this in '㄰'..'㆏'

private fun lyricKeepTogether(left: Char, right: Char): Boolean =
    left.isLetterOrDigit() && right.isLetterOrDigit() && (left.isHangul() || right.isHangul())

/**
 * 韩文和英文一样靠空格分词，系统却按方块字处理，会从词中间断行，整个词的注音只能挤在第一行那半截上。
 * 这里生成只供绘制的副本，正文和词同步加字符，高亮和注音的位置都从副本计算，不需要换算。
 * 比一整行还宽的词仍由系统强制断开。
 */
internal fun lyricKeepKoreanWords(line: TimedLyric): TimedLyric {
    val text = line.text
    if (text.none { it.isHangul() }) return line
    val offsets = IntArray(text.length + 1)
    val joined = StringBuilder(text.length * 2)
    text.forEachIndexed { index, char ->
        offsets[index] = joined.length
        joined.append(char)
        val next = text.getOrNull(index + 1)
        if (next != null && lyricKeepTogether(char, next)) joined.append(LYRIC_WORD_JOINER)
    }
    offsets[text.length] = joined.length
    val ranges = lyricWordRanges(line)
    // 词尾的字符归前一个词，词和词之间不留缝。
    val words = line.words.map { word ->
        val range = ranges.firstOrNull { it.word === word } ?: return@map word
        word.copy(text = joined.substring(offsets[range.start], offsets[range.end]))
    }
    // 位置标注的注音按原文下标记录，副本插入了字符，需要换算后重新标注。
    val transliteration = lyricRubyAnchors(text, line.transliteration)?.let { anchors ->
        lyricRubyMark(joined.toString(), anchors.map { it.copy(start = offsets[it.start], end = offsets[it.end]) })
    } ?: line.transliteration
    return line.copy(text = joined.toString(), words = words, transliteration = transliteration)
}
