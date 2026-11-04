package com.musicone

import androidx.compose.runtime.Composable

@Composable
internal fun AppFontSettings() {
    val selected = ExperiencePreferences.options.fontMode
    PlaybackSettingsMenuButton("字体 · ${selected.label}", "字体",
        AppFontMode.entries.map { mode ->
            PlaybackSettingsMenuOption(mode.label, mode == selected, description = when (mode) {
                AppFontMode.DEFAULT -> "保持当前字体规则"
                AppFontMode.SANS_SERIF -> "歌词全部使用更纱黑体"
                AppFontMode.SYSTEM -> "整个 App 使用系统字体，横屏时钟除外"
            }) {
                ExperiencePreferences.update(ExperiencePreferences.options.copy(fontMode = mode))
            }
        })
}
