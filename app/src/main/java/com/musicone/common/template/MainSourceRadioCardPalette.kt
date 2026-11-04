package com.musicone

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp

/** 未声明完整海报配色时，从音源色与当前主题推导通用语义颜色。 */
internal data class MainSourceRadioCardPalette(
    val background: Color,
    val foreground: Color,
    val wave: Color,
    val badge: Color,
    val badgeInk: Color,
)

@Composable
internal fun mainSourceRadioCardPalette(colorArgb: Long? = null, light: List<Long> = emptyList(), dark: List<Long> = emptyList()): MainSourceRadioCardPalette {
    val declared = if (MaterialTheme.colorScheme.surface.luminance() < .22f) dark else light
    if (declared.size == 5) return MainSourceRadioCardPalette(Color(declared[0]), Color(declared[1]), Color(declared[2]), Color(declared[3]), Color(declared[4]))
    val background = colorArgb?.let(::Color) ?: MaterialTheme.colorScheme.primaryContainer
    val foreground = if (background.luminance() < .45f) Color.White else Color.Black
    return MainSourceRadioCardPalette(background, foreground, lerp(background, foreground, .65f), foreground, background)
}
