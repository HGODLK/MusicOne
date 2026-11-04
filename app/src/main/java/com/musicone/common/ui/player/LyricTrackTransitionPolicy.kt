package com.musicone

import androidx.compose.runtime.staticCompositionLocalOf

/** 页面和歌词窗口只发布运动资格，逐帧位移仍留在原图层里读取。 */
internal val LocalPlayerLyricsMoving = staticCompositionLocalOf { false }
internal val LocalPlayerLyricsDisplayed = staticCompositionLocalOf { false }

internal fun lyricTrackUsesFade(moving: Boolean, gestureHeld: Boolean, fading: Boolean,
    displayed: Boolean = true): Boolean = fading || displayed && (moving || gestureHeld)

/** 仅推迟完全不可见窗口的创建，可见歌词和音频切换不等待页面动画。 */
internal fun deferHiddenLyricTrackChange(moving: Boolean, displayed: Boolean): Boolean = moving && !displayed
