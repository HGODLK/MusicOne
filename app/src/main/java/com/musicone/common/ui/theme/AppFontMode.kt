package com.musicone

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontFamily

internal enum class AppFontMode(val storedValue: String, val label: String) {
    DEFAULT("default", "默认"),
    SANS_SERIF("sans_serif", "黑体"),
    SYSTEM("system", "跟随系统"),
    CUSTOM("custom", "自定义");

    companion object {
        fun fromStored(value: String?): AppFontMode = entries.firstOrNull { it.storedValue == value } ?: DEFAULT
    }
}

/** 仅替换字体家族，沿用应用原有的字号、行高、字重与字距。 */
internal fun musicOneTypography(
    mode: AppFontMode,
    customConfig: CustomFontConfig = CustomFontStore.config,
    customRevision: Long = CustomFontStore.revision,
    customFamilyOverride: FontFamily? = null,
): Typography {
    if (mode == AppFontMode.SYSTEM) return MusicOneSystemTypography
    if (mode != AppFontMode.CUSTOM) return MusicOneTypography
    val customFamily = customFamilyOverride
        ?: resolvePrimaryUiFontId(customConfig)?.let(CustomFontStore::fontFamily)
    if (customFamily != null) return cachedCustomTypography(customRevision, customFamily)
    return if (customConfig.fallback == CustomFontFallback.SYSTEM) MusicOneSystemTypography else MusicOneTypography
}

private var cachedTypographyRevision = Long.MIN_VALUE
private var cachedTypographyFamily: FontFamily? = null
private var cachedTypographyValue: Typography? = null

private fun cachedCustomTypography(revision: Long, family: FontFamily): Typography {
    if (cachedTypographyRevision == revision && cachedTypographyFamily == family) {
        cachedTypographyValue?.let { return it }
    }
    return MusicOneTypography.withFontFamily(family).also {
        cachedTypographyRevision = revision
        cachedTypographyFamily = family
        cachedTypographyValue = it
    }
}

private fun Typography.withFontFamily(family: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = family),
    displayMedium = displayMedium.copy(fontFamily = family),
    displaySmall = displaySmall.copy(fontFamily = family),
    headlineLarge = headlineLarge.copy(fontFamily = family),
    headlineMedium = headlineMedium.copy(fontFamily = family),
    headlineSmall = headlineSmall.copy(fontFamily = family),
    titleLarge = titleLarge.copy(fontFamily = family),
    titleMedium = titleMedium.copy(fontFamily = family),
    titleSmall = titleSmall.copy(fontFamily = family),
    bodyLarge = bodyLarge.copy(fontFamily = family),
    bodyMedium = bodyMedium.copy(fontFamily = family),
    bodySmall = bodySmall.copy(fontFamily = family),
    labelLarge = labelLarge.copy(fontFamily = family),
    labelMedium = labelMedium.copy(fontFamily = family),
    labelSmall = labelSmall.copy(fontFamily = family),
)

private val MusicOneSystemTypography by lazy { MusicOneTypography.withFontFamily(FontFamily.Default) }
