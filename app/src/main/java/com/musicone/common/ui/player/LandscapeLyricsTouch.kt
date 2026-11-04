package com.musicone

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** 留白属于列表触摸区，文字排版仍扣除这段距离，其他播放形态默认不扩展。 */
internal val LocalLandscapeLyricsTouchPadding = staticCompositionLocalOf { 0.dp }
