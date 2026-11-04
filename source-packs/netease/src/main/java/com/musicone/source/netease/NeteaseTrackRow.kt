package com.musicone.source.netease

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.musicone.source.sdk.SourcePlayerState

@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun NeteaseTrackRow(owner: NeteasePackageState, actions: NeteaseTrackActions, track: NeteaseTrack,
    showBadges: Boolean = true, selected: Boolean? = null, onSelect: (() -> Unit)? = null,
    onClick: () -> Unit = { owner.play(listOf(track)) }) {
    var menu by remember(track.id) { mutableStateOf(false) }
    val state = owner.environment.host.playerState?.collectAsState()?.value ?: SourcePlayerState()
    val current = state.sourceId == NETEASE_SOURCE_ID && state.stableTrackId == track.id
    val bitmap by rememberArtworkBitmap(track.artworkUrl)
    val activeColor = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).combinedClickable(onClick = { if (selected != null) onSelect?.invoke() else onClick() },
        onLongClick = { if (onSelect != null) onSelect() else menu = true }).padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (selected != null) Checkbox(selected, onCheckedChange = { onSelect?.invoke() })
        PlaylistSyncedArtwork(bitmap, bitmap == null, track.artworkUrl, track.artworkStart, track.artworkEnd,
            track.artworkMark, Modifier.size(48.dp), 18.sp, RoundedCornerShape(10.dp), track.id)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(track.title, color = activeColor, fontSize = 15.sp, fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (current && state.isPlaying) Icon(Icons.Default.Equalizer, "正在播放", Modifier.padding(start = 6.dp).size(16.dp), tint = activeColor)
                if (showBadges && track.accessBadge != null) Text(track.accessBadge.label, Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
            }
            Text("${track.artists}${track.album.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected == null) {
            IconButton(onClick = { actions.favorite(track) }) { Icon(if (track.id in actions.favoriteIds) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                if (track.id in actions.favoriteIds) "取消喜欢" else "喜欢", tint = if (track.id in actions.favoriteIds) NeteaseMusicThemeColor else MaterialTheme.colorScheme.onSurfaceVariant) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "歌曲菜单") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem({ Text("下一首播放") }, onClick = { menu = false; actions.enqueue(listOf(track), true) })
                    DropdownMenuItem({ Text("加入播放队列") }, onClick = { menu = false; actions.enqueue(listOf(track), false) })
                    DropdownMenuItem({ Text("添加到歌单") }, onClick = { menu = false; actions.chooseDestination(listOf(track)) })
                    DropdownMenuItem({ Text("缓存歌曲") }, onClick = { menu = false; actions.download(track) })
                    DropdownMenuItem({ Text("歌词偏移") }, onClick = { menu = false; actions.offset(track) })
                    if (owner.page == "playlist" && owner.selectedPlaylist?.let { it.isOwned && !it.isPlatformFavorites } == true)
                        DropdownMenuItem({ Text("从歌单移除") }, onClick = { menu = false; actions.remove(track) })
                }
            }
        }
    }
}
