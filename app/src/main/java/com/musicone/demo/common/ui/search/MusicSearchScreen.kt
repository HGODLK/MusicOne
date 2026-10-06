package com.musicone.demo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MusicSearchScreen(
    search: MusicSearchUiState,
    player: MusicOneUiState,
    bottomInset: Dp,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onTrackClick: (MusicTrack) -> Unit,
    onCollectionClick: (MusicPlaylist) -> Unit,
    animateLikeQq: Boolean = false,
    active: Boolean = true,
) {
    val favorites = LocalMusicFavorites.current
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager: FocusManager = LocalFocusManager.current
    LaunchedEffect(active) {
        if (active) {
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回首页") }
            OutlinedTextField(
                value = search.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
                placeholder = { Text("搜索歌曲、歌手或专辑") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = if (search.query.isNotBlank()) {
                    { IconButton({ onQueryChange("") }) { Icon(Icons.Default.Close, "清除搜索") } }
                } else null,
                singleLine = true,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(340.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = bottomInset + 18.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    when {
                        search.query.isBlank() -> "搜索音乐"
                        search.searching -> "正在搜索"
                        search.collections.isNotEmpty() -> "搜索结果"
                        else -> "搜索结果 · ${search.results.size} 首歌曲"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            item("search-feedback", span = { GridItemSpan(maxLineSpan) }) {
                val status = when {
                    search.query.isBlank() -> "empty"
                    search.searching -> "loading"
                    search.results.isEmpty() && search.collections.isEmpty() -> "missing"
                    else -> "ready"
                }
                if (animateLikeQq) {
                    AnimatedContent(
                        targetState = status,
                        transitionSpec = {
                            (fadeIn(musicMotion(240)) + slideInVertically { it / 5 }) togetherWith
                                (fadeOut(musicMotion(200)) + slideOutVertically { -it / 5 })
                        },
                        label = "酷狗搜索结果反馈",
                    ) { SearchFeedback(it, search.message) }
                } else {
                    SearchFeedback(status, search.message)
                }
            }
            if (!search.searching && search.query.isNotBlank() &&
                (search.results.isNotEmpty() || search.collections.isNotEmpty())) {
                    if (search.collections.isNotEmpty()) {
                        item("collections-heading", span = { GridItemSpan(maxLineSpan) }) {
                            Box(if (animateLikeQq) Modifier.animateItem(placementSpec = musicMotion(320)) else Modifier) {
                                Text("专辑与歌单", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                            }
                        }
                        items(search.collections, key = { "collection:${it.id}" }) { playlist ->
                            Box(if (animateLikeQq) Modifier.animateItem(fadeInSpec = musicMotion(260),
                                placementSpec = musicMotion(320), fadeOutSpec = musicMotion(220)) else Modifier) {
                                if (animateLikeQq) SearchResultEntrance {
                                    MusicSearchCollectionRow(playlist) { onCollectionClick(playlist) }
                                } else MusicSearchCollectionRow(playlist) { onCollectionClick(playlist) }
                            }
                        }
                    }
                    if (search.results.isNotEmpty()) item("songs-heading", span = { GridItemSpan(maxLineSpan) }) {
                        Box(if (animateLikeQq) Modifier.animateItem(placementSpec = musicMotion(320)) else Modifier) {
                            Text("歌曲", fontSize = 19.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        }
                    }
                    items(search.results, key = { it.id }) { track ->
                        Box(if (animateLikeQq) Modifier.animateItem(fadeInSpec = musicMotion(260),
                            placementSpec = musicMotion(320), fadeOutSpec = musicMotion(220)) else Modifier) {
                            val row = @Composable {
                                MusicTrackRow(
                                    track = track,
                                    current = player.currentTrack?.id == track.id,
                                    playing = player.isPlaying && player.currentTrack?.id == track.id,
                                    favorite = track.id in favorites.state.ids,
                                    onClick = { onTrackClick(track) },
                                    onFavorite = { favorites.toggle(track) },
                                )
                            }
                            if (animateLikeQq) SearchResultEntrance(row) else row()
                        }
                    }
            }
        }
    }
}

@Composable
private fun SearchFeedback(status: String, message: String?) {
    when (status) {
        "loading" -> Row(Modifier.fillMaxWidth().padding(32.dp), horizontalArrangement = Arrangement.Center) {
            CircularProgressIndicator()
        }
        "empty" -> EmptyState("输入歌曲、歌手或专辑开始搜索")
        "missing" -> EmptyState(message ?: "没有找到匹配的歌曲")
    }
}

@Composable
private fun MusicSearchCollectionRow(playlist: MusicPlaylist, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "打开${playlist.title}", onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RemoteArtwork(
            playlist.artworkUrl, playlist.artworkStart, playlist.artworkEnd, playlist.artworkMark,
            Modifier.size(64.dp), 24.sp,
            androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        )
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(playlist.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(playlist.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}
