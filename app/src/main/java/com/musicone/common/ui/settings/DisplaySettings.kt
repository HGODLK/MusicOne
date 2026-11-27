package com.musicone

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable

/** Android 12（API 31）起系统才支持 RenderEffect / BlurEffect 实时模糊。 */
internal fun supportsBlurSettings(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
    sdkInt >= Build.VERSION_CODES.S

/** 一级分组“显示”：歌词、播放页、外观与动效、页面与底栏四个二级分组。 */
@Composable
internal fun DisplaySettings(onOpenCustomFonts: () -> Unit = {}) {
    val options = ExperiencePreferences.options
    val showBlurSettings = supportsBlurSettings()
    SettingsSection("显示") {
        SettingsGroup("歌词") {
            LyricDisplayContentSettings()
            LyricFontSizeSettings()
            if (showBlurSettings) {
                DisplaySetting("关闭未播放歌词模糊", "歌词保持清晰，不虚化未播放行", options.disableLyricBlur) {
                    ExperiencePreferences.update(options.copy(disableLyricBlur = it))
                }
            }
            DisplaySetting("关闭逐字歌词渐亮", "改为整行同步高亮", !options.verbatimLyricAnimation) {
                ExperiencePreferences.update(options.copy(verbatimLyricAnimation = !it))
            }
            DesktopLyricsSettings()
        }
        SettingsGroup("播放页") {
            DisplaySetting("关闭横屏播放页", "手机播放页固定竖屏，不随横屏切换",
                PhonePlayerPreferences.disableLandscapePlayer) {
                PhonePlayerPreferences.updateDisableLandscapePlayer(it)
            }
            DisplaySetting("锁定竖屏时仍允许横屏播放", "忽略系统方向锁定，横拿手机仍可切换横屏播放页",
                PhonePlayerPreferences.allowLandscapeWhenRotationLocked) {
                PhonePlayerPreferences.updateAllowLandscapeWhenRotationLocked(it)
            }
            DisplaySetting("交换歌词和播放列表按钮", "仅内置播放页生效",
                PhonePlayerPreferences.swapBottomActions) {
                PhonePlayerPreferences.updateSwapBottomActions(it)
            }
            DisplaySetting("播放页紧凑模式", "缩小底部控件，给封面和歌词留出更多空间",
                options.compactPlayerControls) {
                ExperiencePreferences.update(options.copy(compactPlayerControls = it))
            }
            ScreenOnSetting()
        }
        SettingsGroup("外观与动效") {
            AppFontSettings(onOpenCustomFonts)
            AnimationFrameRateSettings()
            DisplaySetting("始终使用深色模式", "开启后固定深色，关闭则跟随系统", options.alwaysDark) {
                ExperiencePreferences.update(options.copy(alwaysDark = it))
            }
            if (showBlurSettings) {
                DisplaySetting("关闭模糊效果", "改用纯色背景，减少性能消耗", options.disableBlur) {
                    ExperiencePreferences.update(options.copy(disableBlur = it))
                }
                DisplaySetting("关闭播放控件及顶部模糊", "播放控件与顶栏改用纯色渐隐，不影响歌词模糊", options.disablePlayerControlsBlur) {
                    ExperiencePreferences.update(options.copy(disablePlayerControlsBlur = it))
                }
            }
            DisplaySetting("减弱动态效果", "减少界面位移和背景流动动画", options.reduceMotion) {
                ExperiencePreferences.update(options.copy(reduceMotion = it))
            }
        }
        SettingsGroup("页面与底栏") {
            DisplaySetting("关闭推荐卡片取色", "保留封面，卡片背景改用素色", options.disableCardColors) {
                ExperiencePreferences.update(options.copy(disableCardColors = it))
            }
            DisplaySetting("“我喜欢”卡片改为左右并排", "手机端“我喜欢”与歌单卡片并排显示",
                options.phoneSideBySideLibraryCards) {
                ExperiencePreferences.update(options.copy(phoneSideBySideLibraryCards = it))
            }
            DisplaySetting("隐藏综合歌单", "不在“我的”页显示综合歌单，仍可从本地音乐进入", options.hideUnifiedPlaylist) {
                ExperiencePreferences.update(options.copy(hideUnifiedPlaylist = it))
            }
            // 关闭胶囊后经典布局没有意义，“经典底栏”收起（保存的选择不改）；反转始终可用，经典或无胶囊时只镜像播放条。
            DisplaySetting("隐藏底部导航栏", "隐藏后可在页面空白处左右滑动切换",
                options.hideNavigationCapsule) {
                ExperiencePreferences.update(options.copy(hideNavigationCapsule = it))
            }
            SettingsReveal(!options.hideNavigationCapsule) {
                DisplaySetting("经典底栏", "播放条在上、导航栏在下的双层布局", options.classicBottomBar) {
                    ExperiencePreferences.update(options.copy(classicBottomBar = it))
                }
            }
            val mirrorOnlyMini = options.hideNavigationCapsule || bottomBarStyleFor(options).classic
            DisplaySetting("反转底栏",
                if (mirrorOnlyMini) "播放条左右镜像显示"
                else "导航栏与播放条左右互换", options.reverseBottomBar) {
                ExperiencePreferences.update(options.copy(reverseBottomBar = it))
            }
        }
    }
}

/** 随上级开关出现的设置项：高度展开、淡入并略微下滑；收起时反过来，下方设置项平滑上移。 */
@Composable
internal fun SettingsReveal(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible,
        enter = expandVertically(musicMotion(300)) + fadeIn(musicMotion(240)) + slideInVertically(musicMotion(300)) { -it / 5 },
        exit = shrinkVertically(musicMotion(260)) + fadeOut(musicMotion(180)) + slideOutVertically(musicMotion(260)) { -it / 5 }) {
        Column { content() }
    }
}

@Composable
private fun DisplaySetting(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    SettingsSwitchRow(title, subtitle, checked, onChange)
