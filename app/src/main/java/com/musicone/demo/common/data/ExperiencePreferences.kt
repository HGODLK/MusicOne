package com.musicone.demo

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class ExperienceOptions(
    val disableBlur: Boolean = false,
    val disableLyricBlur: Boolean = false,
    val disablePlayerControlsBlur: Boolean = false,
    val reduceMotion: Boolean = false,
    val disableCardColors: Boolean = false,
    val alwaysDark: Boolean = false,
    val verbatimLyricAnimation: Boolean = true,
    val uiAnimationFrameRate: AnimationFrameRate = AnimationFrameRate.DISPLAY,
    val lyricAnimationFrameRate: AnimationFrameRate = AnimationFrameRate.DISPLAY,
)

/** 显示偏好独立保存，渲染层读取同一份可观察状态。 */
internal object ExperiencePreferences {
    var options by mutableStateOf(ExperienceOptions())
        private set
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        if (preferences != null) return
        val store = context.applicationContext.getSharedPreferences("experience", Context.MODE_PRIVATE)
        preferences = store
        options = ExperienceOptions(
            disableBlur = store.getBoolean("disable_blur", false),
            disableLyricBlur = store.getBoolean("disable_lyric_blur", false),
            disablePlayerControlsBlur = store.getBoolean("disable_player_controls_blur", false),
            reduceMotion = store.getBoolean("reduce_motion", false),
            disableCardColors = store.getBoolean("disable_card_colors", false),
            alwaysDark = store.getBoolean("always_dark", false),
            // 保存键继续表示实际启用状态，界面反向开关不会翻转已有选择。
            verbatimLyricAnimation = store.getBoolean("verbatim_lyric_animation", true),
            uiAnimationFrameRate = AnimationFrameRate.fromStored(store.getInt("ui_animation_fps", 0)),
            lyricAnimationFrameRate = AnimationFrameRate.fromStored(store.getInt("lyric_animation_fps", 0)),
        )
    }

    fun update(value: ExperienceOptions) {
        options = value
        preferences?.edit()?.putBoolean("disable_blur", value.disableBlur)
            ?.putBoolean("disable_lyric_blur", value.disableLyricBlur)
            ?.putBoolean("disable_player_controls_blur", value.disablePlayerControlsBlur)
            ?.putBoolean("reduce_motion", value.reduceMotion)
            ?.putBoolean("disable_card_colors", value.disableCardColors)
            ?.putBoolean("always_dark", value.alwaysDark)
            ?.putBoolean("verbatim_lyric_animation", value.verbatimLyricAnimation)
            ?.putInt("ui_animation_fps", value.uiAnimationFrameRate.fps)
            ?.putInt("lyric_animation_fps", value.lyricAnimationFrameRate.fps)?.apply()
    }
}
