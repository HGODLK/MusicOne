package com.musicone.demo

import androidx.compose.runtime.Composable

/** 设置中的选中态、开关和按钮统一跟随主音源，不改变其他页面主题。 */
@Composable
internal fun SettingsPlatformTheme(source: MusicSource, neutral: Boolean, content: @Composable () -> Unit) {
    PlatformMusicTheme(source, neutral, content)
}
