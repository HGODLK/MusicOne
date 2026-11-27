package com.musicone

import androidx.compose.ui.text.style.TextDirection

internal const val BIDI_FSI = '\u2068'
internal const val BIDI_PDI = '\u2069'

internal enum class StrongTextDirection { LTR, RTL, NEUTRAL }

internal data class MusicOneScriptRun(val start: Int, val end: Int, val script: MusicOneScript)

/** 是否属于阿拉伯语／波斯语使用的阿拉伯字母体系区段（含扩展与表现形式区）。 */
internal fun isArabicOrPersianChar(char: Char): Boolean {
    val code = char.code
    return code in 0x0600..0x06FF ||
        code in 0x0750..0x077F ||
        code in 0x0870..0x08FF ||
        code in 0xFB50..0xFDFF ||
        code in 0xFE70..0xFEFF
}

/** 阿拉伯／波斯文字中的核心字母（不含纯标点与数字，用于方向与整词保护）。 */
internal fun isArabicOrPersianLetter(char: Char): Boolean =
    isArabicOrPersianChar(char) && Character.isLetter(char)

/** 波斯语特有字母与数字，用于区分 `fa` 与 `ar` 地区字形变体。 */
internal fun isPersianSpecificChar(char: Char): Boolean = when (char.code) {
    0x067E, // پ
    0x0686, // چ
    0x0698, // ژ
    0x06A9, // ک
    0x06AF, // گ
    0x06C0, // ۀ
    0x06CC, // ی
    in 0x06F0..0x06F9, // ۰-۹
    -> true
    else -> false
}

/** 组合音标（如阿拉伯元音符号 Harakaat）、波斯半空格 ZWNJ、ZWJ 与伸展符。 */
internal fun isCombiningOrJoiner(char: Char): Boolean {
    val code = char.code
    if (code == 0x200C || code == 0x200D || code == 0x0640) return true
    return when (Character.getType(char)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }
}

/** 零宽双向控制符。 */
internal fun isBidiControlChar(char: Char): Boolean {
    val code = char.code
    return code == 0x061C || code == 0x200E || code == 0x200F ||
        code in 0x202A..0x202E || code in 0x2066..0x2069
}

internal fun containsArabicOrPersian(text: CharSequence): Boolean {
    for (index in 0 until text.length) {
        if (isArabicOrPersianChar(text[index])) return true
    }
    return false
}

internal fun containsPersianSpecific(text: CharSequence): Boolean {
    for (index in 0 until text.length) {
        if (isPersianSpecificChar(text[index])) return true
    }
    return false
}

/** 为阿拉伯／波斯文本选择地区代码：含波斯特有字符时返回 `fa`，否则 `ar`。 */
internal fun arabicOrPersianLocaleTag(text: CharSequence): String =
    if (containsPersianSpecific(text)) "fa" else "ar"

/** 按首个强方向字符判定文本方向，跳过空白、数字、标点与控制符。 */
internal fun strongTextDirectionOf(text: CharSequence): StrongTextDirection {
    var index = 0
    while (index < text.length) {
        val codePoint = Character.codePointAt(text, index)
        index += Character.charCount(codePoint)
        val dir = Character.getDirectionality(codePoint)
        if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        ) return StrongTextDirection.RTL
        if (dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT) return StrongTextDirection.LTR
    }
    return StrongTextDirection.NEUTRAL
}

internal fun isRtlStrongText(text: CharSequence): Boolean =
    strongTextDirectionOf(text) == StrongTextDirection.RTL

/** 为独立标题、歌手或歌词原文提供明确的 Compose 段落方向。 */
internal fun contentTextDirection(text: CharSequence): TextDirection = when (strongTextDirectionOf(text)) {
    StrongTextDirection.RTL -> TextDirection.Rtl
    StrongTextDirection.LTR -> TextDirection.Ltr
    StrongTextDirection.NEUTRAL -> TextDirection.ContentOrLtr
}

/**
 * 判断整首歌主歌词是否以波斯／阿拉伯语为主。
 * 按全曲有效原文行统计，避免波斯语歌曲中偶发的一句英文副歌导致整屏左右来回跳动。
 */
internal fun isRtlPrimaryLyrics(lines: List<TimedLyric>): Boolean {
    var rtlLines = 0
    var ltrLines = 0
    var arabicLetters = 0
    var otherLetters = 0
    for (line in lines) {
        val text = line.text
        if (text.isBlank()) continue
        when (strongTextDirectionOf(text)) {
            StrongTextDirection.RTL -> rtlLines++
            StrongTextDirection.LTR -> ltrLines++
            StrongTextDirection.NEUTRAL -> Unit
        }
        for (index in 0 until text.length) {
            val ch = text[index]
            if (isArabicOrPersianLetter(ch)) arabicLetters++
            else if (Character.isLetter(ch)) otherLetters++
        }
    }
    if (arabicLetters == 0) return false
    return rtlLines > ltrLines || (rtlLines == ltrLines && rtlLines > 0 && arabicLetters >= otherLetters)
}

/**
 * 双向混排拼接保护：任一段含强 RTL 或阿拉伯／波斯字符时，用 FSI/PDI 隔离各独立字段，
 * 避免“波斯歌名 - English Artist”之类拼接被双向算法打乱顺序。纯 LTR 文本保持原样。
 */
internal fun joinBidiIsolated(parts: List<String>, separator: String): String {
    val nonBlank = parts.filter { it.isNotBlank() }
    if (nonBlank.isEmpty()) return ""
    if (nonBlank.size == 1) return nonBlank[0]
    val needsIsolation = nonBlank.any { containsArabicOrPersian(it) || isRtlStrongText(it) }
    if (!needsIsolation) return nonBlank.joinToString(separator)
    return nonBlank.joinToString(separator) { "$BIDI_FSI$it$BIDI_PDI" }
}

/** 阿拉伯／波斯文脚本分段：组合音标、ZWNJ、ZWJ 与伸展符始终并入前一个脚本，不断开连笔。 */
internal fun musicOneScriptRuns(text: String, cjkScript: MusicOneScript): List<MusicOneScriptRun> {
    if (text.isEmpty()) return emptyList()
    val runs = ArrayList<MusicOneScriptRun>(4)
    var start = 0
    var current = scriptForChar(text[0], cjkScript, null)
    for (index in 1 until text.length) {
        val next = scriptForChar(text[index], cjkScript, current)
        if (next != current) {
            runs.add(MusicOneScriptRun(start, index, current))
            start = index
            current = next
        }
    }
    runs.add(MusicOneScriptRun(start, text.length, current))
    return runs
}

private fun scriptForChar(char: Char, cjkScript: MusicOneScript, previous: MusicOneScript?): MusicOneScript {
    if (previous != null && isCombiningOrJoiner(char)) return previous
    return scriptFor(char, cjkScript)
}
