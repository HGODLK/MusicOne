package com.musicone

import androidx.compose.runtime.Composable

@Composable
internal fun LyricFontSizeSettings() {
    val selected = ExperiencePreferences.options.lyricFontSize
    PlaybackSettingsMenuButton("歌词大小 · ${selected.label}", "歌词大小",
        LyricFontSize.entries.map { size ->
            PlaybackSettingsMenuOption(size.label, size == selected) {
                ExperiencePreferences.update(ExperiencePreferences.options.copy(lyricFontSize = size))
            }
        })
}
