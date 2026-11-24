package com.musicone

import androidx.compose.runtime.Composable

/** “我喜欢”按钮功能设置，说明能力兜底和仅对之后点击生效的边界。 */
@Composable
internal fun FavoriteButtonSettings() {
    val selected = ExperiencePreferences.options.favoriteButtonBehavior
    SettingsMenuRow(
        title = "我喜欢按钮功能",
        value = selected.label,
        subtitle = "音源不支持“我喜欢”时自动存入综合歌单",
        options = FavoriteButtonBehavior.entries.map { behavior ->
            PlaybackSettingsMenuOption(behavior.label, behavior == selected, description = behavior.description) {
                ExperiencePreferences.update(ExperiencePreferences.options.copy(favoriteButtonBehavior = behavior))
            }
        },
    )
}
