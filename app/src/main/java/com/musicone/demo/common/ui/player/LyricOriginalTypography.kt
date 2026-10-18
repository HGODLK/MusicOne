package com.musicone.demo

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.em

private val westernLyricRuns = Regex("[\\p{IsLatin}\\p{IsGreek}\\p{IsCyrillic}\\p{M}\\p{N}\\p{P}\\p{Zs}]+")
private val westernLyricCharacters = Regex("[\\p{IsLatin}\\p{IsGreek}\\p{IsCyrillic}0-9]")

/** 只缩小原文中的西文字形，保留文字偏移、字体分段和逐字高亮索引。 */
internal fun lyricOriginalAnnotatedString(text: String): AnnotatedString = buildAnnotatedString {
    append(musicOneDisplayAnnotatedString(text))
    westernLyricRuns.findAll(text).forEach { run ->
        if (westernLyricCharacters.containsMatchIn(run.value)) {
            addStyle(SpanStyle(fontSize = .82.em), run.range.first, run.range.last + 1)
        }
    }
}
