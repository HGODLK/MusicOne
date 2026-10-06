package com.musicone.demo

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

internal val QqMusicThemeColor = Color(0xFF31C27C)
internal val QqMusicThemeContainerColor = Color(0xFFDDF7E9)

private val MusicOneColors = lightColorScheme(
    primary = Color(0xFFFF7867),
    onPrimary = Color(0xFF321416),
    primaryContainer = Color(0xFFFFE4DE),
    onPrimaryContainer = Color(0xFF5D2021),
    secondary = Color(0xFF5ABFA7),
    background = Color.White,
    onBackground = Color(0xFF19202C),
    surface = Color.White,
    onSurface = Color(0xFF19202C),
    surfaceVariant = Color(0xFFF4F5F7),
    onSurfaceVariant = Color(0xFF687181),
    outline = Color(0xFFDCE0E6),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicOneTheme(content: @Composable () -> Unit) {
    ExperiencePreferences.initialize(androidx.compose.ui.platform.LocalContext.current)
    val dark = useDarkTheme(ExperiencePreferences.options.alwaysDark, androidx.compose.foundation.isSystemInDarkTheme())
    CompositionLocalProvider(
        LocalIndication provides NoRippleIndication,
        LocalRippleConfiguration provides null,
    ) {
        MaterialTheme(
            colorScheme = if (dark) MusicOneDarkColors else MusicOneColors,
            typography = MusicOneTypography,
        ) {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodyLarge,
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface,
                content = content,
            )
        }
    }
}
