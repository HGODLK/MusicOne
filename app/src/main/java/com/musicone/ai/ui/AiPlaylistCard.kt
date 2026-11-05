package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 歌单沿用现有封面双色组件，点击交给原歌单详情导航。 */
@Composable
internal fun AiPlaylistCard(playlist: MusicPlaylist, onOpenPlaylist: (MusicPlaylist) -> Unit) {
    val motion = LocalPlaylistMotion.current
    val transition = LocalPlaylistCardTransition.current
    val requested by rememberArtworkBitmap(playlist.artworkUrl, maxSide = LocalMainSourceFeedArtworkSizes.current.thumbnail)
    val artwork = requested ?: ArtworkRepository.peek(playlist.artworkUrl, LocalMainSourceFeedArtworkSizes.current.thumbnail)
    Surface(onClick = {
        if (motion?.mounted != true && transition?.busy != true) {
            // 与最近播放卡相同，在导航前冻结当前封面及混合快照。
            if (transition == null) onOpenPlaylist(playlist)
            else transition.open(playlist.id, artwork, {}, { onOpenPlaylist(playlist) })
        }
    }, shape = RoundedCornerShape(16.dp), color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) {
        MainSourceHomeArtworkGradient(artwork, playlist.artworkUrl ?: playlist.id, Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PlaylistSyncedArtwork(artwork, playlist.artworkUrl?.isNotBlank() == true && artwork == null,
                    playlist.artworkStart, playlist.artworkEnd, playlist.artworkMark,
                    Modifier.size(64.dp)
                        .motionAnchor(motion, playlist.id, false, corner = 12f, markSize = 22f, markX = 0f, markY = 0f), 22.sp, RoundedCornerShape(12.dp), Alignment.Center,
                    sourceKey = playlist.id, imageUrl = playlist.artworkUrl)
                Column(Modifier.weight(1f).padding(start = 12.dp).graphicsLayer {
                    alpha = transition?.alphaFor(playlist.id) ?: 1f
                }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(playlist.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(playlist.subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("查看歌单", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
