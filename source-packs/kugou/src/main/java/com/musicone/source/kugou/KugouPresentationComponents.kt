package com.musicone.source.kugou

import android.graphics.BitmapFactory
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable internal fun KugouArtwork(url: String?, start: Long, end: Long, mark: String, modifier: Modifier) {
    val image by produceState<android.graphics.Bitmap?>(null, url) {
        value = if (url.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatchingCancellable { PlatformHttp.get(url).bytes.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
        }
    }
    Box(modifier.background(Brush.linearGradient(listOf(Color(start.takeIf { it != 0L } ?: KUGOU_PAGE_COLOR),
        Color(end.takeIf { it != 0L } ?: 0xFF0877D1L)))), contentAlignment = Alignment.Center) {
        image?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text(mark, color = Color.White, fontSize = 28.sp)
    }
}
@Composable internal fun EmptyState(message: String) { Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
@Composable internal fun KugouInlineMessage(message: String) = EmptyState(message)
@Composable internal fun KugouPlaylistCard(playlist: MusicPlaylist, onClick: () -> Unit, onPlay: () -> Unit,
    modifier: Modifier = Modifier, width: Dp) {
    LocalKugouSharedUi.current?.let { shared ->
        shared.RecommendationCollectionCard(KugouSourceMapping.collection(playlist, LocalKugouAccountId.current), modifier,
            widthDp = width.value, onPlay = onPlay, onClick = onClick)
        return
    }
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            KugouArtwork(playlist.artworkUrl, playlist.artworkStart, playlist.artworkEnd, playlist.artworkMark,
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp)))
            IconButton(onClick = onPlay, Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.Black.copy(alpha = .35f), CircleShape)) {
                Icon(Icons.Default.PlayArrow, "播放歌单", tint = Color.White)
            }
        }
        Text(playlist.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(playlist.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable internal fun KugouLibraryPlaylistCard(playlist: MusicPlaylist, artworkVersion: Int, onClick: () -> Unit) {
    LocalKugouSharedUi.current?.let { shared ->
        shared.CollectionCard(KugouSourceMapping.collection(playlist, LocalKugouAccountId.current), onClick = onClick)
        return
    }
    KugouPlaylistCard(playlist, onClick, onClick, Modifier.fillMaxWidth(), 640.dp)
}
@Composable internal fun KugouProfileHeader(account: MusicAccount?, onLogin: () -> Unit) {
    LocalKugouSharedUi.current?.let { shared ->
        shared.ProfileHeader(KUGOU_SOURCE_ID, account?.let {
            com.musicone.source.sdk.SourceAccount(it.userId, it.nickname, it.avatarUrl)
        }, onLogin)
        return
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onLogin), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        KugouArtwork(account?.avatarUrl, KUGOU_PAGE_COLOR, 0xFF0877D1, account?.nickname?.take(1) ?: "酷", Modifier.size(72.dp).clip(CircleShape))
        Column { Text(account?.nickname ?: "登录酷狗音乐", style = MaterialTheme.typography.titleLarge)
            Text(if (account == null) "同步你的喜欢和歌单" else "账号与登录设置", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable internal fun KugouFavoritesRow(playlist: MusicPlaylist?, artworkVersion: Int, signedIn: Boolean, onClick: () -> Unit) {
    LocalKugouSharedUi.current?.let { shared ->
        val account = LocalKugouAccountId.current
        shared.FavoritesRow(playlist?.let { KugouSourceMapping.collection(it, account) }, signedIn, onClick)
        return
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable(onClick = onClick)
        .background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        KugouArtwork(playlist?.artworkUrl, KUGOU_PAGE_COLOR, 0xFF0877D1, "♥", Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)))
        Column { Text("我喜欢", style = MaterialTheme.typography.titleMedium)
            Text(if (signedIn) "${playlist?.count ?: 0} 首歌曲" else "登录后同步", color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable internal fun KugouPlaylistSegment(created: Boolean, createdCount: Int, collectedCount: Int, onCreated: () -> Unit, onCollected: () -> Unit) {
    LocalKugouSharedUi.current?.let { shared ->
        shared.CollectionSegment(created, createdCount, collectedCount, onCreated, onCollected)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilterChip(created, onCreated, label = { Text("创建 $createdCount") })
        FilterChip(!created, onCollected, label = { Text("收藏 $collectedCount") })
    }
}
@Composable internal fun MusicTrackRow(track: MusicTrack, current: Boolean, playing: Boolean, favorite: Boolean,
    onClick: () -> Unit, onFavorite: () -> Unit, showSource: Boolean = true) {
    LocalKugouSharedUi.current?.let { shared ->
        shared.TrackRow(KugouSourceMapping.track(track, LocalKugouAccountId.current), current, playing,
            favorite, onClick, onFavorite, showSource)
        return
    }
    val menu = LocalKugouTrackMenu.current
    Row(Modifier.fillMaxWidth().clickable(enabled = track.playable, onClick = onClick).padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        KugouArtwork(track.artworkUrl, track.artworkStart, track.artworkEnd, track.artworkMark, Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)))
        Column(Modifier.weight(1f).padding(horizontal = 13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.title, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, false))
                track.accessBadge?.let { Text(if (it == MusicAccessBadge.VIP) " VIP" else " 付费", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary) }
            }
            Text("${track.artists} · ${track.album}", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (current) Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, null, Modifier.size(19.dp))
        IconButton(onFavorite) { Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "收藏", tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        IconButton({ menu(track) }) { Icon(Icons.Default.MoreHoriz, "更多") }
    }
}
