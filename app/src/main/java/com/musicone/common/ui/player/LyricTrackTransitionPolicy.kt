package com.musicone

import androidx.compose.runtime.staticCompositionLocalOf

/** 通过 CompositionLocal 只传递“是否在运动/是否可见”的布尔状态，逐帧位移留在绘制层读取以防重组。 */
internal val LocalPlayerLyricsMoving = staticCompositionLocalOf { false }
internal val LocalPlayerLyricsDisplayed = staticCompositionLocalOf { false }

internal fun lyricTrackUsesFade(moving: Boolean, gestureHeld: Boolean, fading: Boolean,
    displayed: Boolean = true): Boolean = fading || displayed && (moving || gestureHeld)

/** 仅推迟完全不可见窗口的创建，可见歌词和音频切换不等待页面动画。 */
internal fun deferHiddenLyricTrackChange(moving: Boolean, displayed: Boolean): Boolean = moving && !displayed
