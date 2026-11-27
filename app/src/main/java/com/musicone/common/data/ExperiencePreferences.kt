package com.musicone

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
    val fontMode: AppFontMode = AppFontMode.DEFAULT,
    val lyricDisplayContent: LyricDisplayContent = LyricDisplayContent.TRANSLATION,
    val lyricFontSize: LyricFontSize = LyricFontSize.MEDIUM,
    val compactPlayerControls: Boolean = false,
    // 仅作用于公共模板“我的”页：手机改为左右并排，隐藏对手机和平板都生效。
    val phoneSideBySideLibraryCards: Boolean = false,
    val hideUnifiedPlaylist: Boolean = false,
    // 底栏默认播放条与导航胶囊并排；经典为上下两层。反转在并排时整行互换，经典时只镜像播放条。
    val classicBottomBar: Boolean = false,
    val reverseBottomBar: Boolean = false,
    // 隐藏底栏的首页／我的切换胶囊，两种底栏布局都生效；页面左右滑动切换不受影响。
    val hideNavigationCapsule: Boolean = false,
    // 红心按钮写入目标；仅对之后点击生效，未声明我喜欢能力的音源始终默认进综合歌单。
    val favoriteButtonBehavior: FavoriteButtonBehavior = FavoriteButtonBehavior.SOURCE_ONLY,
)

/** 界面显示偏好单独存本地，Compose 层直接读这份可观察状态。 */
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
            // SharedPreferences 里存的始终是实际开关状态，界面文案怎么写都不反转存储值。
            verbatimLyricAnimation = store.getBoolean("verbatim_lyric_animation", true),
            uiAnimationFrameRate = AnimationFrameRate.fromStored(store.getInt("ui_animation_fps", 0)),
            lyricAnimationFrameRate = AnimationFrameRate.fromStored(store.getInt("lyric_animation_fps", 0)),
            fontMode = AppFontMode.fromStored(store.getString("font_mode", null)),
            lyricDisplayContent = LyricDisplayContent.fromStored(store.getString("lyric_display_content", null)),
            lyricFontSize = LyricFontSize.fromStored(store.getString("lyric_font_size", null)),
            compactPlayerControls = store.getBoolean("compact_player_controls", false),
            phoneSideBySideLibraryCards = store.getBoolean("phone_side_by_side_library_cards", false),
            hideUnifiedPlaylist = store.getBoolean("hide_unified_playlist", false),
            classicBottomBar = store.getBoolean("classic_bottom_bar", false),
            reverseBottomBar = store.getBoolean("reverse_bottom_bar", false),
            hideNavigationCapsule = store.getBoolean("hide_navigation_capsule", false),
            favoriteButtonBehavior = FavoriteButtonBehavior.fromStored(store.getString("favorite_button_behavior", null)),
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
            ?.putInt("lyric_animation_fps", value.lyricAnimationFrameRate.fps)
            ?.putString("font_mode", value.fontMode.storedValue)
            ?.putString("lyric_display_content", value.lyricDisplayContent.storedValue)
            ?.putString("lyric_font_size", value.lyricFontSize.storedValue)
            ?.putBoolean("compact_player_controls", value.compactPlayerControls)
            ?.putBoolean("phone_side_by_side_library_cards", value.phoneSideBySideLibraryCards)
            ?.putBoolean("hide_unified_playlist", value.hideUnifiedPlaylist)
            ?.putBoolean("classic_bottom_bar", value.classicBottomBar)
            ?.putBoolean("reverse_bottom_bar", value.reverseBottomBar)
            ?.putBoolean("hide_navigation_capsule", value.hideNavigationCapsule)
            ?.putString("favorite_button_behavior", value.favoriteButtonBehavior.storedValue)?.apply()
    }
}
