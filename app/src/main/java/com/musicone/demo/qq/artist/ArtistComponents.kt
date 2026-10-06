package com.musicone.demo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

@Composable
internal fun ArtistAlbumList(
    data: ArtistPageState,
    scroll: LazyListState,
    bottomInset: Dp,
    contentMounted: Boolean = true,
) {
    val navigation = LocalEntityNavigation.current
    LazyColumn(state = scroll, modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomInset + 24.dp)) {
        if (contentMounted) items(data.albums, key = { it.id }) { album ->
            Row(Modifier.fillMaxWidth().clickable { navigation?.open(EntityTarget.Album(album), sourceKey = "artist-album:${album.id}") }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RemoteArtwork(album.artworkUrl, album.artworkStart, album.artworkEnd, album.artworkMark,
                    Modifier.size(80.dp).entityArtworkAnchor("artist-album:${album.id}", 12f), 30.sp, RoundedCornerShape(12.dp))
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(album.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    Text(album.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (contentMounted) item { ArtistLoadMore(data.albumLoading, data.albumError, data.albumNext != null, data::loadAlbums) }
        if (contentMounted && data.albums.isEmpty() && !data.albumLoading && data.albumError == null) {
            item { Text("暂无专辑", Modifier.padding(20.dp)) }
        }
    }
}

@Composable
internal fun ArtistLoadMore(loading: Boolean, error: String?, hasMore: Boolean, load: () -> Unit) {
    if (loading) Text("正在加载…", Modifier.padding(20.dp))
    else if (error != null || hasMore) TextButton(onClick = load, modifier = Modifier.fillMaxWidth()) {
        Text(error?.let { "$it · 重试" } ?: "加载更多")
    }
}
