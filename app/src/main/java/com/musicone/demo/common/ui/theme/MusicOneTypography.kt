package com.musicone.demo

import androidx.compose.material3.Typography
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import java.util.Locale

internal enum class MusicOneScript { SC, TC, JA, KO, LATIN }

internal object MusicOneUiFontFamilies {
    val sc = FontFamily(
        Font(R.font.sarasa_ui_sc_regular, FontWeight.Normal),
        Font(R.font.sarasa_ui_sc_semibold, FontWeight.Medium),
        Font(R.font.sarasa_ui_sc_semibold, FontWeight.SemiBold),
        Font(R.font.sarasa_ui_sc_bold, FontWeight.Bold),
    )
    val tc = FontFamily(Font(R.font.sarasa_ui_tc_regular, FontWeight.Normal))
    val ja = FontFamily(Font(R.font.sarasa_ui_j_regular, FontWeight.Normal))
    val ko = FontFamily(Font(R.font.sarasa_ui_k_regular, FontWeight.Normal))
}

internal object MusicOneDisplayFontFamilies {
    val sc = FontFamily(
        Font(R.font.source_han_serif_sc_regular, FontWeight.Normal),
        Font(R.font.source_han_serif_sc_medium, FontWeight.Medium),
        Font(R.font.source_han_serif_sc_semibold, FontWeight.SemiBold),
    )
    val tc = FontFamily(Font(R.font.source_han_serif_tc_regular, FontWeight.Normal))
    val ja = FontFamily(Font(R.font.source_han_serif_j_regular, FontWeight.Normal))
    val ko = FontFamily(Font(R.font.source_han_serif_k_regular, FontWeight.Normal))
    val latin = FontFamily(
        Font(R.font.source_serif_4_regular, FontWeight.Normal),
        Font(R.font.source_serif_4_semibold, FontWeight.SemiBold),
    )
    val displayLatin = FontFamily(Font(R.font.source_serif_4_display_regular, FontWeight.Normal))
}

internal object MusicOneTextStyles {
    val lyricActive = TextStyle(fontSize = 32.sp, lineHeight = 42.sp, fontWeight = FontWeight.Medium)
    val lyricTranslation = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal)
    val lyricUpcoming = TextStyle(fontSize = 28.sp, lineHeight = 38.sp, fontWeight = FontWeight.Medium)
    val lyricUnavailable = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.Normal)
    val playerTitle = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
    val playerArtist = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
    val miniPlayerTitle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val miniPlayerArtist = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal)
    val editorialTitle = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium)
}

private fun uiStyle(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontFamily = MusicOneUiFontFamilies.sc,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
)

internal val MusicOneTypography = Typography(
    displayLarge = uiStyle(36, 44, FontWeight.SemiBold),
    displayMedium = uiStyle(32, 40, FontWeight.SemiBold),
    displaySmall = uiStyle(28, 36, FontWeight.SemiBold),
    headlineLarge = uiStyle(28, 36, FontWeight.SemiBold),
    headlineMedium = uiStyle(24, 32, FontWeight.SemiBold),
    headlineSmall = uiStyle(20, 28, FontWeight.SemiBold),
    titleLarge = uiStyle(22, 28, FontWeight.SemiBold),
    titleMedium = uiStyle(16, 24, FontWeight.Medium),
    titleSmall = uiStyle(14, 20, FontWeight.Medium),
    bodyLarge = uiStyle(16, 24, FontWeight.Normal),
    bodyMedium = uiStyle(14, 20, FontWeight.Normal),
    bodySmall = uiStyle(12, 18, FontWeight.Normal),
    labelLarge = uiStyle(14, 20, FontWeight.SemiBold),
    labelMedium = uiStyle(12, 16, FontWeight.Medium),
    labelSmall = uiStyle(11, 16, FontWeight.Medium),
)

internal fun musicOneUiFontFamily(script: MusicOneScript): FontFamily = when (script) {
    MusicOneScript.SC, MusicOneScript.LATIN -> MusicOneUiFontFamilies.sc
    MusicOneScript.TC -> MusicOneUiFontFamilies.tc
    MusicOneScript.JA -> MusicOneUiFontFamilies.ja
    MusicOneScript.KO -> MusicOneUiFontFamilies.ko
}

internal fun musicOneDisplayFontFamily(script: MusicOneScript): FontFamily = when (script) {
    MusicOneScript.SC -> MusicOneDisplayFontFamilies.sc
    MusicOneScript.TC -> MusicOneDisplayFontFamilies.tc
    MusicOneScript.JA -> MusicOneDisplayFontFamilies.ja
    MusicOneScript.KO -> MusicOneDisplayFontFamilies.ko
    MusicOneScript.LATIN -> MusicOneDisplayFontFamilies.latin
}

internal fun musicOneScriptOf(text: String, hint: MusicOneScript? = null): MusicOneScript {
    if (text.any(::isKorean)) return MusicOneScript.KO
    if (text.any(::isJapanese)) return MusicOneScript.JA
    if (text.any(::isCjk)) return hint?.takeIf { it != MusicOneScript.LATIN } ?: defaultCjkScript()
    return MusicOneScript.LATIN
}

internal fun musicOneUiAnnotatedString(text: String, hint: MusicOneScript? = null): AnnotatedString =
    musicOneAnnotatedString(text, display = false, editorial = false, hint = hint)

internal fun musicOneDisplayAnnotatedString(text: String, hint: MusicOneScript? = null): AnnotatedString =
    musicOneAnnotatedString(text, display = true, editorial = false, hint = hint)

internal fun musicOneEditorialAnnotatedString(text: String, hint: MusicOneScript? = null): AnnotatedString =
    musicOneAnnotatedString(text, display = true, editorial = true, hint = hint)

private fun musicOneAnnotatedString(
    text: String,
    display: Boolean,
    editorial: Boolean,
    hint: MusicOneScript?,
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    val cjkScript = musicOneScriptOf(text, hint)
    return buildAnnotatedString {
        var start = 0
        var current = scriptFor(text[0], cjkScript)
        for (index in 1 until text.length) {
            val next = scriptFor(text[index], cjkScript)
            if (next != current) {
                appendFontSpan(text.substring(start, index), current, display, editorial)
                start = index
                current = next
            }
        }
        appendFontSpan(text.substring(start), current, display, editorial)
    }
}

private fun AnnotatedString.Builder.appendFontSpan(
    text: String,
    script: MusicOneScript,
    display: Boolean,
    editorial: Boolean,
) {
    val family = if (!display) {
        musicOneUiFontFamily(script)
    } else if (editorial && script == MusicOneScript.LATIN) {
        MusicOneDisplayFontFamilies.displayLatin
    } else {
        musicOneDisplayFontFamily(script)
    }
    withStyle(SpanStyle(fontFamily = family)) {
        append(text)
    }
}

private fun scriptFor(char: Char, cjkScript: MusicOneScript): MusicOneScript = when {
    isKorean(char) -> MusicOneScript.KO
    isJapanese(char) -> MusicOneScript.JA
    isCjk(char) -> cjkScript
    isLatin(char) -> MusicOneScript.LATIN
    else -> cjkScript
}

private fun defaultCjkScript(): MusicOneScript {
    val locale = Locale.getDefault()
    return when (locale.language) {
        "ja" -> MusicOneScript.JA
        "ko" -> MusicOneScript.KO
        "zh" -> if (locale.country.uppercase(Locale.ROOT) in setOf("TW", "HK", "MO")) {
            MusicOneScript.TC
        } else {
            MusicOneScript.SC
        }
        else -> MusicOneScript.SC
    }
}

private fun isCjk(char: Char): Boolean = char in '\u3400'..'\u4DBF' ||
    char in '\u4E00'..'\u9FFF' || char in '\uF900'..'\uFAFF'

private fun isJapanese(char: Char): Boolean = char in '\u3040'..'\u30FF' ||
    char in '\u31F0'..'\u31FF' || char in '\uFF66'..'\uFF9D'

private fun isKorean(char: Char): Boolean = char in '\u1100'..'\u11FF' ||
    char in '\u3130'..'\u318F' || char in '\uA960'..'\uA97F' || char in '\uAC00'..'\uD7AF'

private fun isLatin(char: Char): Boolean = char.isLetterOrDigit() && char.code <= 0x024F
