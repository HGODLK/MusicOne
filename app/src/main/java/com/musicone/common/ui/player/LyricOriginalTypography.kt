package com.musicone

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em

// 文字类别必须写 \p{script=Xxx}：Android 9 的 ICU 不认 \p{IsXxx}，类加载即闪退。
private val westernLyricRuns = Regex("[\\p{script=Latin}\\p{script=Greek}\\p{script=Cyrillic}\\p{M}\\p{N}\\p{P}\\p{Zs}]+")
private val westernLyricCharacters = Regex("[\\p{script=Latin}\\p{script=Greek}\\p{script=Cyrillic}0-9]")

/** 只缩小原文中的西文字形，保留文字偏移、字体分段和逐字高亮索引。 */
internal fun lyricOriginalAnnotatedString(text: String,
    mode: AppFontMode = ExperiencePreferences.options.fontMode): AnnotatedString = buildAnnotatedString {
    append(musicOneDisplayAnnotatedString(text, mode = mode))
    westernLyricRuns.findAll(text).forEach { run ->
        if (westernLyricCharacters.containsMatchIn(run.value)) {
            addStyle(SpanStyle(fontSize = .82.em), run.range.first, run.range.last + 1)
        }
    }
}
