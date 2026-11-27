package com.musicone

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em

// 文字类别必须写 \p{script=Xxx}：Android 9 的 ICU 不认 \p{IsXxx}，类加载即闪退。
// 只把 ASCII 数字归入西文范围，不把波斯／阿拉伯数字或标点吞进西文缩字。
private val westernLyricRuns = Regex("[\\p{script=Latin}\\p{script=Greek}\\p{script=Cyrillic}\\p{M}0-9\\p{P}\\p{Zs}]+")
private val westernLyricCharacters = Regex("[\\p{script=Latin}\\p{script=Greek}\\p{script=Cyrillic}0-9]")

/** 只缩小原文中的西文字形，保留文字偏移、字体分段和逐字高亮索引。 */
internal fun lyricOriginalAnnotatedString(text: String,
    mode: AppFontMode = ExperiencePreferences.options.fontMode): AnnotatedString =
    lyricOriginalAnnotatedString(text, mode, hint = null)

/** 支持显式指定语系提示的重载，供多语种字体预览按段独立着色而不改变热路径签名。 */
internal fun lyricOriginalAnnotatedString(text: String, mode: AppFontMode, hint: MusicOneScript?): AnnotatedString = buildAnnotatedString {
    append(musicOneDisplayAnnotatedString(text, hint = hint, mode = mode))
    westernLyricRuns.findAll(text).forEach { run ->
        // 遇到阿拉伯／波斯区段字符（如标点、元音符号）切分，避免跨脚本吞入西文缩字范围。
        var cursor = run.range.first
        val limit = run.range.last + 1
        while (cursor < limit) {
            while (cursor < limit && (isArabicOrPersianChar(text[cursor]) || isCombiningOrJoiner(text[cursor]))) cursor++
            val start = cursor
            while (cursor < limit && !isArabicOrPersianChar(text[cursor])) cursor++
            var end = cursor
            while (end > start && text[end - 1].isWhitespace() && end < limit && isArabicOrPersianChar(text[end])) end--
            if (start < end && westernLyricCharacters.containsMatchIn(text.substring(start, end))) {
                addStyle(SpanStyle(fontSize = .82.em), start, end)
            }
        }
    }
}
