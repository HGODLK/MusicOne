package com.musicone

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

internal enum class AppFontMode(val storedValue: String, val label: String) {
    DEFAULT("default", "默认"),
    SANS_SERIF("sans_serif", "黑体"),
    SYSTEM("system", "跟随系统");

    companion object {
        fun fromStored(value: String?): AppFontMode = entries.firstOrNull { it.storedValue == value } ?: DEFAULT
    }
}

/** 仅替换字体家族，沿用应用原有的字号、行高、字重与字距。 */
internal fun musicOneTypography(mode: AppFontMode): Typography {
    if (mode != AppFontMode.SYSTEM) return MusicOneTypography
    return MusicOneSystemTypography
}

private val MusicOneSystemTypography by lazy {
    with(MusicOneTypography) {
        copy(
            displayLarge = displayLarge.copy(fontFamily = FontFamily.Default),
            displayMedium = displayMedium.copy(fontFamily = FontFamily.Default),
            displaySmall = displaySmall.copy(fontFamily = FontFamily.Default),
            headlineLarge = headlineLarge.copy(fontFamily = FontFamily.Default),
            headlineMedium = headlineMedium.copy(fontFamily = FontFamily.Default),
            headlineSmall = headlineSmall.copy(fontFamily = FontFamily.Default),
            titleLarge = titleLarge.copy(fontFamily = FontFamily.Default),
            titleMedium = titleMedium.copy(fontFamily = FontFamily.Default),
            titleSmall = titleSmall.copy(fontFamily = FontFamily.Default),
            bodyLarge = bodyLarge.copy(fontFamily = FontFamily.Default),
            bodyMedium = bodyMedium.copy(fontFamily = FontFamily.Default),
            bodySmall = bodySmall.copy(fontFamily = FontFamily.Default),
            labelLarge = labelLarge.copy(fontFamily = FontFamily.Default),
            labelMedium = labelMedium.copy(fontFamily = FontFamily.Default),
            labelSmall = labelSmall.copy(fontFamily = FontFamily.Default),
        )
    }
}
