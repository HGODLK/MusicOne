package com.musicone.demo
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.material.icons.filled.MoreHoriz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun QueueRow(index: Int, track: MusicTrack, current: Boolean, playing: Boolean = false) {
    val menu = LocalQqPlaylistSongMenu.current
    val anchor = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    Row(Modifier.fillMaxWidth().onGloballyPositioned { anchor.value = it.boundsInRoot() }
        .background(if (current) Color.White.copy(alpha = .1f) else Color.Transparent,
        RoundedCornerShape(14.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1}", color = Color.White.copy(alpha = .45f), fontSize = 11.sp, modifier = Modifier.width(28.dp))
        RemoteArtwork(track.artworkUrl, track.artworkStart, track.artworkEnd, track.artworkMark,
            Modifier.size(46.dp), 19.sp, RoundedCornerShape(16.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            TrackTitle(track, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                badgeOnDark = true)
            Text(track.artists, color = Color.White.copy(alpha = .55f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
        }
        androidx.compose.animation.AnimatedVisibility(current,
            enter = androidx.compose.animation.fadeIn(musicMotion(220)) + androidx.compose.animation.expandHorizontally(musicMotion(260)),
            exit = androidx.compose.animation.fadeOut(musicMotion(180)) + androidx.compose.animation.shrinkHorizontally(musicMotion(220))) {
            QueuePlayingIndicator(playing)
        }
        if (track.source == MusicSource.QQ && menu != null) androidx.compose.material3.IconButton(
            onClick = { menu.open(track, anchor.value) },
            modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.MoreHoriz, "更多", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}
