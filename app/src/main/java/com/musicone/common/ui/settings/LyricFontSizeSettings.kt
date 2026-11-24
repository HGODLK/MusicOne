package com.musicone

import androidx.compose.runtime.Composable

@Composable
internal fun LyricFontSizeSettings() {
    val selected = ExperiencePreferences.options.lyricFontSize
    SettingsMenuRow("歌词大小", selected.label,
        LyricFontSize.entries.map { size ->
            PlaybackSettingsMenuOption(size.label, size == selected) {
                ExperiencePreferences.update(ExperiencePreferences.options.copy(lyricFontSize = size))
            }
        })
}
