package com.musicone.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun QqRecentPlaySection(
    state: QqRecentPlayUiState,
    signedIn: Boolean,
    onOpenPlaylist: (MusicPlaylist) -> Unit,
) {
    val playlists = state.snapshot.asRecentPlaylists()
        .filter { it.tracks.isNotEmpty() || it.id != QQ_RECENT_SONGS_PLAYLIST_ID }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("最近播放", fontSize = 23.sp, fontWeight = FontWeight.Bold)
        when {
            playlists.isNotEmpty() -> BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cardWidth = if (maxWidth >= 600.dp) 168.dp else 148.dp
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(playlists, key = MusicPlaylist::id) { playlist ->
                        Column(Modifier.width(cardWidth)) {
                            QqLibraryPlaylistCard(
                                playlist = playlist,
                                artworkVersion = 0L,
                                sourceKey = "qq-my-recent:${playlist.id}",
                                onClick = { onOpenPlaylist(playlist) },
                                supportText = if (playlist.id == QQ_RECENT_SONGS_PLAYLIST_ID) {
                                    "${playlist.count}首"
                                } else {
                                    "歌单"
                                },
                                reserveTwoTitleLines = true,
                            )
                        }
                    }
                }
            }
            !signedIn -> QqInlineMessage("登录后同步最近播放")
            state.loading -> QqInlineMessage("正在同步最近播放…")
            else -> QqInlineMessage(state.message ?: "还没有最近播放记录")
        }
    }
}
