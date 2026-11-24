package com.musicone

import androidx.compose.runtime.Composable

@Composable
internal fun AppFontSettings() {
    val selected = ExperiencePreferences.options.fontMode
    SettingsMenuRow("字体", selected.label,
        AppFontMode.entries.map { mode ->
            PlaybackSettingsMenuOption(mode.label, mode == selected, description = when (mode) {
                AppFontMode.DEFAULT -> "跟随应用默认字体"
                AppFontMode.SANS_SERIF -> "歌词全部使用更纱黑体"
                AppFontMode.SYSTEM -> "全局使用系统字体"
            }) {
                ExperiencePreferences.update(ExperiencePreferences.options.copy(fontMode = mode))
            }
        })
}
