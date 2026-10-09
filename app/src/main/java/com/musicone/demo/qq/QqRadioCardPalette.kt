package com.musicone.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** 海报使用独立的深绿、暖白和浅绿语义配色，兼顾应用明暗主题。 */
internal data class QqRadioCardPalette(
    val background: Color,
    val foreground: Color,
    val wave: Color,
    val badge: Color,
    val badgeInk: Color,
)

@Composable
internal fun qqRadioCardPalette(): QqRadioCardPalette =
    if (MaterialTheme.colorScheme.surface.luminance() < .22f) {
        QqRadioCardPalette(Color(0xFF0D624B), Color(0xFFF2F8E9), Color(0xFF8ED5B0),
            Color(0xFFEBF5DF), Color(0xFF133B2B))
    } else {
        QqRadioCardPalette(Color(0xFF09664C), Color(0xFFF4FAED), Color(0xFF9EE7B9),
            Color(0xFFF3FAED), Color(0xFF073D2F))
    }
