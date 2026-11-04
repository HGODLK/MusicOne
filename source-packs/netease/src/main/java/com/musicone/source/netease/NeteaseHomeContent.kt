package com.musicone.source.netease

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

@Composable internal fun NeteaseHomeContent(owner: NeteasePackageState, actions: NeteaseTrackActions) {
    key(owner.dataRevision) {
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            if (owner.account != null) { owner.refreshHome(); actions.refreshFavorites() }
        }
    }
    if (owner.account == null) { NeteaseSignedOut(owner, "连接网易云，获取你的推荐音乐"); return }
    LazyVerticalGrid(columns = GridCells.Adaptive(340.dp), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 82.dp, bottom = (owner.bottomInset + 20).dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            NeteaseSectionHeading("今日推荐", "为你整理的音乐氛围")
            if (owner.homePlaylists.loading && owner.recommendations.isEmpty()) Text("正在加载推荐…", fontSize = 12.sp)
            else if (owner.recommendations.isEmpty()) Text(owner.homePlaylists.error ?: "暂时无法加载推荐歌单")
            else NeteaseRecommendations(owner)
        } }
        item(span = { GridItemSpan(maxLineSpan) }) { NeteaseSectionHeading("推荐歌曲", if (owner.homeTracks.loading) "正在更新" else "今天可以听这些",
            onRefresh = { owner.refreshHome(true) }, enabled = !owner.homePlaylists.loading && !owner.homeTracks.loading) }
        if (owner.recommendedTracks.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Text(if (owner.homeTracks.loading) "正在加载推荐歌曲…" else owner.homeTracks.error ?: "暂时无法加载推荐歌曲") }
        items(owner.recommendedTracks, key = { it.id }) { track ->
            // 首页推荐保持原有不展示 VIP／付费角标规则，其他页面仍按真实权限显示。
            NeteaseTrackRow(owner, actions, track, showBadges = false)
        }
        owner.message?.let { text -> item(span = { GridItemSpan(maxLineSpan) }) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
    }
}
@Composable internal fun NeteaseSectionHeading(title: String, note: String, onRefresh: (() -> Unit)? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-.5).sp))
            Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
        if (onRefresh != null) FilledTonalButton(onClick = onRefresh, enabled = enabled,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("刷新", fontSize = 13.sp)
        }
    }
}
@Composable private fun NeteaseRecommendations(owner: NeteasePackageState) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        val width = if (compact) (maxWidth * .74f).coerceIn(226.dp, 264.dp).coerceAtMost(maxWidth)
            else (maxWidth * .31f).coerceIn(260.dp, 300.dp).coerceAtMost(maxWidth)
        val height = width / if (compact) .82f else .84f
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 20.dp)) {
            items(owner.recommendations, key = { it.id }) { playlist ->
                val bitmap by rememberArtworkBitmap(playlist.artworkUrl)
                Box(Modifier.width(width).height(height).clip(RoundedCornerShape(26.dp)).clickable { owner.openPlaylist(playlist) }) {
                    PlaylistSyncedArtwork(bitmap, bitmap == null, playlist.artworkUrl, playlist.artworkStart, playlist.artworkEnd,
                        playlist.artworkMark, Modifier.fillMaxSize(), 70.sp, RoundedCornerShape(26.dp), playlist.id)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .7f)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(18.dp).padding(end = 36.dp)) {
                        Text(playlist.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(playlist.subtitle, color = Color.White.copy(alpha = .78f), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    FilledIconButton(onClick = { owner.action {
                        val expected = owner.revision.get(); val cookie = owner.credential(); val vip = owner.account?.hasVipAccess == true
                        val full = withContext(Dispatchers.IO) { owner.api.playlistDetail(playlist.remoteId(), cookie, vip) }
                        owner.check(expected); owner.play(full.tracks)
                    } }, modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) { Icon(Icons.Default.PlayArrow, "播放歌单") }
                }
            }
        }
    }
}
@Composable internal fun NeteaseSignedOut(owner: NeteasePackageState, message: String) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("网易云音乐", style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(10.dp)); Text(message)
        Button(onClick = { owner.open("authentication") }, modifier = Modifier.padding(top = 20.dp)) { Text("登录网易云音乐") }
    }
}
