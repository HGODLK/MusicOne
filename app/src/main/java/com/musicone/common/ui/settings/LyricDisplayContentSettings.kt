package com.musicone

import androidx.compose.runtime.Composable

@Composable
internal fun LyricDisplayContentSettings() {
    val selected = ExperiencePreferences.options.lyricDisplayContent
    SettingsMenuRow("歌词显示内容", selected.label,
        LyricDisplayContent.entries.map { content ->
            PlaybackSettingsMenuOption(content.label, content == selected, description = content.description) {
                ExperiencePreferences.update(ExperiencePreferences.options.copy(lyricDisplayContent = content))
            }
        })
}
