package com.musicone

import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 状态栏只跟随页面封面；加载期间保留已取颜色，不混入预设色或当前排序首曲。 */
internal fun playlistStatusArtwork(playlist: MusicPlaylist?): String? =
    playlist?.artworkUrl?.takeIf(String::isNotBlank)

@Composable
internal fun rememberPlaylistStatusColor(playlist: MusicPlaylist?): Color = key(playlist?.id) {
    val base = MaterialTheme.colorScheme.background
    val url = playlistStatusArtwork(playlist)
    val target by produceState(base, url) {
        // 地址更新时沿用本歌单已显示的封面色，取得新封面后再交给原过渡。
        val bitmap = ArtworkRepository.load(url) ?: return@produceState
        value = withContext(Dispatchers.Default) { artistAppearanceFromBitmap(bitmap).background }
    }
    val color by animateColorAsState(if (playlist == null) base else target, musicMotion(280), label = "歌单状态栏取色")
    color
}
