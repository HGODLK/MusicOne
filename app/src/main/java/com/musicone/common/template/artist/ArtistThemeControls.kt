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

/** 文字颜色在绘制时读取，写真滑动不重新排版标题和简介入口。 */
@Composable
internal fun ArtistAccentText(text: String, appearance: State<ArtistAppearance>, modifier: Modifier, style: TextStyle) {
    BasicText(text, modifier, style, overflow = TextOverflow.Ellipsis, maxLines = 1,
        color = { appearance.value.accent })
}

/** 动态按钮只更新自身配色，不带动头像、简介和轮播布局。 */
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
