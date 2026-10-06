package com.musicone.demo

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

internal fun useDarkTheme(alwaysDark: Boolean, systemDark: Boolean): Boolean = alwaysDark || systemDark

internal val MusicOneDarkColors = darkColorScheme(
    primary = Color(0xFFE3E3E3), onPrimary = Color(0xFF202124),
    primaryContainer = Color(0xFF353739), onPrimaryContainer = Color(0xFFF1F1F1),
    secondary = Color(0xFFCECECE), onSecondary = Color(0xFF202124),
    secondaryContainer = Color(0xFF353739), onSecondaryContainer = Color(0xFFF1F1F1),
    tertiary = Color(0xFFCECECE), onTertiary = Color(0xFF202124),
    tertiaryContainer = Color(0xFF353739), onTertiaryContainer = Color(0xFFF1F1F1),
    background = Color(0xFF121314), onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF121314), onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF292B2D), onSurfaceVariant = Color(0xFFBCC0C3),
    surfaceContainerLowest = Color(0xFF0D0E0F), surfaceContainerLow = Color(0xFF191A1B),
    surfaceContainer = Color(0xFF202123), surfaceContainerHigh = Color(0xFF292B2D),
    surfaceContainerHighest = Color(0xFF343638),
    outline = Color(0xFF858A8E), outlineVariant = Color(0xFF424548), surfaceTint = Color.Transparent,
)
