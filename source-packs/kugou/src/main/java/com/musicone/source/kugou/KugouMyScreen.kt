package com.musicone.source.kugou

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun KugouMyScreen(
    account: MusicAccount?,
    library: KugouLibraryUiState,
    bottomInset: Dp,
    onOpenSettings: () -> Unit,
    onOpenPlaylist: (MusicPlaylist) -> Unit,
    onRefresh: () -> Unit,
    topInset: Dp = 88.dp,
    scroll: LazyGridState = rememberLazyGridState(),
) {
    var created by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    val playlists = if (created) library.createdPlaylists else library.collectedPlaylists

    BoxWithConstraints(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(kugouLibraryColumns(maxWidth)), state = scroll, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = topInset, bottom = bottomInset + 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item("profile", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.padding(bottom = 6.dp)) { KugouProfileHeader(account, onOpenSettings) }
        }
        item("favorite", span = { GridItemSpan(maxLineSpan) }) {
            KugouFavoritesRow(
                playlist = library.favoritePlaylist, artworkVersion = 0, signedIn = library.signedIn,
                onClick = {
                    if (!library.signedIn) onOpenSettings()
                    else library.favoritePlaylist?.let(onOpenPlaylist) ?: onRefresh()
                },
            )
        }
        item("heading", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("我的歌单", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                KugouPlaylistSegment(created, library.createdPlaylists.size, library.collectedPlaylists.size,
                    { created = true }, { created = false })
            }
        }
        items(playlists, key = MusicPlaylist::id) { playlist ->
            KugouLibraryPlaylistCard(playlist, artworkVersion = 0) { onOpenPlaylist(playlist) }
        }
        if (playlists.isEmpty() && (!library.signedIn || !library.loading)) item("status", span = { GridItemSpan(maxLineSpan) }) {
            KugouInlineMessage(when {
                !library.signedIn -> "登录后，你的酷狗歌单会出现在这里"

                library.message != null -> library.message
                created -> "还没有创建的歌单"
                else -> "还没有收藏的歌单"
            })
        }
    }
    }
}


