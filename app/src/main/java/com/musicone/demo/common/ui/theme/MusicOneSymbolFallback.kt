package com.musicone.demo

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

private val musicOneMathSymbols = FontFamily(Font(R.font.musicone_math_symbols))

/** 仅覆盖数学花体所在区段，保留原有中日韩、西文的字体、字重及排版分配。 */
internal fun AnnotatedString.withMusicOneSymbolFallback(): AnnotatedString {
    var builder: AnnotatedString.Builder? = null
    var index = 0
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        val length = Character.charCount(codePoint)
        if (codePoint in 0x1D400..0x1D7FF) {
            val target = builder ?: AnnotatedString.Builder(this).also { builder = it }
            // 这些字符本身已包含粗体/斜体字形，避免再次合成字重与斜体。
            target.addStyle(SpanStyle(fontFamily = musicOneMathSymbols,
                fontWeight = FontWeight.Normal, fontStyle = FontStyle.Normal), index, index + length)
        }
        index += length
    }
    return builder?.toAnnotatedString() ?: this
}
