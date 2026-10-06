package com.musicone.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

internal val NeteaseMusicThemeColor = Color(0xFFEC4141)
internal val KugouMusicThemeColor = Color(0xFF009AF3)
internal val MusicOneNeutralAccent = Color(0xFF3C4043)
internal val MusicOneNeutralContainer = Color(0xFFF1F3F4)
internal val LocalPlatformNeutral = staticCompositionLocalOf { true }

/** 每个平台持有自己的品牌色，页面切换时不沿用上一平台的主题状态。 */
@Composable
internal fun PlatformMusicTheme(
    source: MusicSource,
    neutral: Boolean,
    content: @Composable () -> Unit,
) {
    val platformAccent = when (source) {
        MusicSource.NETEASE -> NeteaseMusicThemeColor
        MusicSource.QQ -> QqMusicThemeColor
        MusicSource.KUGOU -> KugouMusicThemeColor
    }
    val dark = MaterialTheme.colorScheme.background == MusicOneDarkColors.background
    val accent = if (neutral) { if (dark) MusicOneDarkColors.primary else MusicOneNeutralAccent }
        else if (dark) lerp(platformAccent, Color.White, .25f) else platformAccent
    val container = if (dark) lerp(accent, MaterialTheme.colorScheme.surface, .8f)
        else if (neutral) MusicOneNeutralContainer else lerp(accent, Color.White, .84f)
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = accent,
            onPrimary = if (dark) Color.Black else Color.White,
            primaryContainer = container,
            onPrimaryContainer = if (dark) accent else lerp(accent, Color.Black, .32f),
            secondary = accent,
            onSecondary = if (dark) Color.Black else Color.White,
            secondaryContainer = container,
            onSecondaryContainer = if (dark) accent else lerp(accent, Color.Black, .32f),
            tertiary = accent,
            tertiaryContainer = container,
            surfaceTint = if (neutral) Color.Transparent else accent,
        ),
        content = { CompositionLocalProvider(LocalPlatformNeutral provides neutral, content = content) },
    )
}
