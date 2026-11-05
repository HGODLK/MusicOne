package com.musicone

import androidx.compose.runtime.Composable

@Composable
internal fun LyricDisplayContentSettings() {
    val selected = ExperiencePreferences.options.lyricDisplayContent
    PlaybackSettingsMenuButton("歌词显示内容 · ${selected.label}", "歌词显示内容",
        LyricDisplayContent.entries.map { content ->
            PlaybackSettingsMenuOption(content.label, content == selected, description = content.description) {
                ExperiencePreferences.update(ExperiencePreferences.options.copy(lyricDisplayContent = content))
            }
        })
}
