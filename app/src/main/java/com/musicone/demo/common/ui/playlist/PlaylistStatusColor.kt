package com.musicone.demo

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 复用封面仓库与现有主色提取，地址变化时不继承上一张封面的颜色。 */
@Composable
internal fun rememberPlaylistStatusColor(playlist: MusicPlaylist?): Color {
    val base = MaterialTheme.colorScheme.background
    val url = playlist?.artworkUrl ?: playlist?.tracks?.firstOrNull()?.artworkUrl
    val fallback = playlist?.let { Color(it.artworkStart) } ?: base
    val target by produceState(fallback, url, fallback) {
        value = fallback
        val bitmap = ArtworkRepository.load(url) ?: return@produceState
        value = withContext(Dispatchers.Default) { artistAppearanceFromBitmap(bitmap).background }
    }
    val color by animateColorAsState(if (playlist == null) base else target, musicMotion(280), label = "歌单状态栏取色")
    return color
}
