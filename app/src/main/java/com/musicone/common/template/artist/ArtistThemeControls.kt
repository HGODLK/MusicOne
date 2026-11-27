package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 绘制阶段再读文字颜色，滑写真切配色时不会触发文字重新排版。 */
@Composable
internal fun ArtistAccentText(text: String, appearance: State<ArtistAppearance>, modifier: Modifier, style: TextStyle) {
    BasicText(text, modifier, style, overflow = TextOverflow.Ellipsis, maxLines = 1,
        color = { appearance.value.accent })
}

/** 播放按钮自己订阅动态配色，不牵连头图、简介和轮播重组。 */
@Composable
internal fun ArtistPhotoPlayButton(appearance: State<ArtistAppearance>, playable: Boolean, onPlay: () -> Unit) {
    val colors = appearance.value
    Button(onPlay, enabled = playable, modifier = Modifier.width(132.dp).height(52.dp).playlistDetailReveal(),
        shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.background)) {
        Icon(Icons.Default.PlayArrow, "播放歌手歌曲", Modifier.size(28.dp))
    }
}

/** 关闭后的退场仍保留原主题；彻底隐藏后不再订阅写真颜色。 */
@Composable
internal fun ArtistReadingWindow(open: Boolean, appearance: State<ArtistAppearance>, usePhotoTheme: Boolean,
    name: String, introduction: String, onClose: () -> Unit) {
    var retained by remember { mutableStateOf<ColorScheme?>(null) }
    val colors = if (open && usePhotoTheme) appearance.value.colors else retained ?: MaterialTheme.colorScheme
    SideEffect { if (open && usePhotoTheme) retained = colors }
    MaterialTheme(colorScheme = if (usePhotoTheme) colors else MaterialTheme.colorScheme) {
        GlassReadingWindow(open, "$name · 简介", introduction, onClose)
    }
}
