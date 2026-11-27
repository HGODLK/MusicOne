package com.musicone

import androidx.compose.material3.Typography
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import java.util.Locale

internal enum class MusicOneScript { SC, TC, JA, KO, LATIN, ARABIC }

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
    val arabic = FontFamily(
        Font(R.font.vazirmatn_regular, FontWeight.Normal),
        Font(R.font.vazirmatn_semibold, FontWeight.Medium),
        Font(R.font.vazirmatn_semibold, FontWeight.SemiBold),
        Font(R.font.vazirmatn_bold, FontWeight.Bold),
    )
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
    val arabic = FontFamily(
        Font(R.font.noto_naskh_arabic_regular, FontWeight.Normal),
        Font(R.font.noto_naskh_arabic_medium, FontWeight.Medium),
        Font(R.font.noto_naskh_arabic_semibold, FontWeight.SemiBold),
    )
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
    MusicOneScript.ARABIC -> MusicOneUiFontFamilies.arabic
}

internal fun musicOneDisplayFontFamily(script: MusicOneScript): FontFamily = when (script) {
    MusicOneScript.SC -> MusicOneDisplayFontFamilies.sc
    MusicOneScript.TC -> MusicOneDisplayFontFamilies.tc
    MusicOneScript.JA -> MusicOneDisplayFontFamilies.ja
    MusicOneScript.KO -> MusicOneDisplayFontFamilies.ko
    MusicOneScript.LATIN -> MusicOneDisplayFontFamilies.latin
    MusicOneScript.ARABIC -> MusicOneDisplayFontFamilies.arabic
}

internal fun musicOneScriptOf(text: String, hint: MusicOneScript? = null): MusicOneScript {
    if (text.any(::isKorean)) return MusicOneScript.KO
    if (text.any(::isJapanese)) return MusicOneScript.JA
    if (text.any(::isCjk)) return hint?.takeIf { it != MusicOneScript.LATIN && it != MusicOneScript.ARABIC } ?: defaultCjkScript()
    if (containsArabicOrPersian(text)) return MusicOneScript.ARABIC
    return MusicOneScript.LATIN
}

private val persianLocaleList = LocaleList("fa")
private val arabicLocaleList = LocaleList("ar")

internal fun musicOneUiAnnotatedString(text: String, hint: MusicOneScript? = null,
    mode: AppFontMode = ExperiencePreferences.options.fontMode): AnnotatedString =
    musicOneAnnotatedString(text, display = false, editorial = false, hint = hint, mode = mode)

internal fun musicOneDisplayAnnotatedString(text: String, hint: MusicOneScript? = null,
    mode: AppFontMode = ExperiencePreferences.options.fontMode): AnnotatedString =
    musicOneAnnotatedString(text, display = mode != AppFontMode.SANS_SERIF, editorial = false, hint = hint, mode = mode)

internal fun musicOneEditorialAnnotatedString(text: String, hint: MusicOneScript? = null,
    mode: AppFontMode = ExperiencePreferences.options.fontMode): AnnotatedString =
    musicOneAnnotatedString(text, display = true, editorial = true, hint = hint, mode = mode)

private fun musicOneAnnotatedString(
    text: String,
    display: Boolean,
    editorial: Boolean,
    hint: MusicOneScript?,
    mode: AppFontMode,
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    val arabicLocale = if (containsArabicOrPersian(text)) {
        if (containsPersianSpecific(text)) persianLocaleList else arabicLocaleList
    } else null
    if (mode == AppFontMode.CUSTOM) {
        val customConfig = CustomFontStore.config
        CustomFontStore.revision
        val fallbackMode = customConfig.fallback.asBaseFontMode()
        val fallbackDisplay = display && fallbackMode != AppFontMode.SANS_SERIF
        val cjkScript = musicOneScriptOf(text, hint)
        return buildAnnotatedString {
            for (run in musicOneScriptRuns(text, cjkScript)) {
                val slice = text.substring(run.start, run.end)
                val customId = if (display) customConfig.resolveLyricFontId(run.script)
                else customConfig.resolveUiFontId(run.script)
                val customFamily = customId?.let(CustomFontStore::fontFamily)
                val family = customFamily ?: if (fallbackMode == AppFontMode.SYSTEM) null else {
                    if (!fallbackDisplay) musicOneUiFontFamily(run.script)
                    else if (editorial && run.script == MusicOneScript.LATIN) MusicOneDisplayFontFamilies.displayLatin
                    else musicOneDisplayFontFamily(run.script)
                }
                val locale = if (run.script == MusicOneScript.ARABIC) arabicLocale else null
                if (family != null || locale != null) {
                    withStyle(SpanStyle(fontFamily = family, localeList = locale)) { append(slice) }
                } else {
                    append(slice)
                }
            }
        }.withMusicOneSymbolFallback(fallbackMode)
    }
    if (mode == AppFontMode.SYSTEM) {
        if (arabicLocale == null) return AnnotatedString(text)
        // 系统字体模式不指定内置字体，仅对阿拉伯／波斯段声明地区以修正波斯字形变体。
        val cjkScript = musicOneScriptOf(text, hint)
        return buildAnnotatedString {
            for (run in musicOneScriptRuns(text, cjkScript)) {
                val slice = text.substring(run.start, run.end)
                if (run.script == MusicOneScript.ARABIC) {
                    withStyle(SpanStyle(localeList = arabicLocale)) { append(slice) }
                } else append(slice)
            }
        }
    }
    val cjkScript = musicOneScriptOf(text, hint)
    return buildAnnotatedString {
        for (run in musicOneScriptRuns(text, cjkScript)) {
            appendFontSpan(text.substring(run.start, run.end), run.script, display, editorial, arabicLocale)
        }
    }.withMusicOneSymbolFallback(mode)
}

private fun AnnotatedString.Builder.appendFontSpan(
    text: String,
    script: MusicOneScript,
    display: Boolean,
    editorial: Boolean,
    arabicLocale: LocaleList?,
) {
    val family = if (!display) {
        musicOneUiFontFamily(script)
    } else if (editorial && script == MusicOneScript.LATIN) {
        MusicOneDisplayFontFamilies.displayLatin
    } else {
        musicOneDisplayFontFamily(script)
    }
    val locale = if (script == MusicOneScript.ARABIC) arabicLocale else null
    withStyle(SpanStyle(fontFamily = family, localeList = locale)) {
        append(text)
    }
}

internal fun scriptFor(char: Char, cjkScript: MusicOneScript): MusicOneScript = when {
    isKorean(char) -> MusicOneScript.KO
    isJapanese(char) -> MusicOneScript.JA
    isCjk(char) -> cjkScript
    isArabicOrPersianChar(char) -> MusicOneScript.ARABIC
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
