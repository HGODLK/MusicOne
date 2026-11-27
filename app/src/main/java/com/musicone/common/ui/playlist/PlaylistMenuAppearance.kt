package com.musicone

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** 歌单页菜单专用的加深玻璃底色，压住背后透出的文字。 */
internal val playlistMenuContainer: Color
    @Composable get() = Color.Black.copy(alpha = .09f)
        .compositeOver(MaterialTheme.colorScheme.surface).copy(alpha = .94f)
