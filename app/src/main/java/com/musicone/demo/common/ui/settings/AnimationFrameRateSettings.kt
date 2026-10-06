package com.musicone.demo

import androidx.compose.runtime.Composable

@Composable
internal fun AnimationFrameRateSettings() {
    val options = ExperiencePreferences.options
    AnimationFrameRateSelector("UI 动画帧率", options.uiAnimationFrameRate) { rate ->
        ExperiencePreferences.update(ExperiencePreferences.options.copy(uiAnimationFrameRate = rate))
    }
    AnimationFrameRateSelector("歌词动画帧率", options.lyricAnimationFrameRate) { rate ->
        ExperiencePreferences.update(ExperiencePreferences.options.copy(lyricAnimationFrameRate = rate))
    }
}

@Composable
private fun AnimationFrameRateSelector(title: String, selected: AnimationFrameRate,
                                      onSelect: (AnimationFrameRate) -> Unit) {
    PlaybackSettingsMenuButton("$title · ${selected.label}", title,
        AnimationFrameRate.entries.map { rate ->
            PlaybackSettingsMenuOption(rate.label, rate == selected) { onSelect(rate) }
        })
}
