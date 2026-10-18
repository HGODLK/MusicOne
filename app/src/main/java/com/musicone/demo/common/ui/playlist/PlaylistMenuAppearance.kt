package com.musicone.demo

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** 只供歌单及其资料编辑入口使用，压住透出的背景字形。 */
internal val playlistMenuContainer: Color
    @Composable get() = Color.Black.copy(alpha = .09f)
        .compositeOver(MaterialTheme.colorScheme.surface).copy(alpha = .94f)
