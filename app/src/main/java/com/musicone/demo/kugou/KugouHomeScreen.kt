package com.musicone.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.util.Calendar

@Composable
internal fun KugouHomeScreen(
    state: MusicOneUiState,
    catalog: MusicCatalogUiState,
    viewModel: MusicOneViewModel,
    bottomInset: Dp,
    actions: PlatformHomeActions,
) {
    val favorites = LocalMusicFavorites.current
    val scroll = rememberLazyGridState()
    val daily = catalog.recommendedTracks.takeIf(List<MusicTrack>::isNotEmpty)?.let { tracks ->
        MusicPlaylist(
            id = "kugou-daily", source = MusicSource.KUGOU, title = "每日推荐",
            subtitle = "根据你的音乐口味更新", description = "酷狗每日推荐歌曲",
            count = tracks.size, artworkStart = 0xFF45B8FF, artworkEnd = 0xFF0877D1,
            artworkMark = currentKugouDay().toString(), tracks = tracks,
        )
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { actions.onRefreshRecommendedTracks(false) }
    LaunchedEffect(state.page) {
        if (state.page == MusicOnePage.HOME) actions.onRefreshRecommendedTracks(false)
    }
    ReportPrimaryHeaderScroll(MusicOnePage.HOME, scroll)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(160.dp),
        state = scroll,
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = LocalPlayerMotion.current?.mounted != true &&
            LocalPlaylistMotion.current?.mounted != true && LocalPlaylistCardTransition.current?.busy != true,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 82.dp, bottom = bottomInset + 18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("daily", span = { GridItemSpan(maxLineSpan) }) {
            when {
                daily != null -> KugouDailyCard(
                    daily,
                    state.isPlaying && daily.tracks.any { it.id == state.currentTrack?.id },
                    onOpen = { actions.onOpenLoadedPlaylist(daily) },
                    onPlay = { viewModel.playPlaylist(daily) },
                )
                catalog.loadingRecommendedTracks -> KugouLoadingCard("正在生成今日推荐…")
                else -> KugouLoadingCard(catalog.recommendedTracksMessage ?: "今日推荐暂时不可用")
            }
        }
        item("library-heading", span = { GridItemSpan(maxLineSpan) }) {
            KugouSectionHeading("乐库", "精选歌单")
        }
        when {
            catalog.loadingRecommendations -> item("library-loading", span = { GridItemSpan(maxLineSpan) }) {
                Text("正在加载乐库…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            catalog.recommendations.isEmpty() -> item("library-empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(catalog.recommendationMessage ?: "乐库暂时不可用")
            }
            else -> items(catalog.recommendations, key = MusicPlaylist::id) { playlist ->
                QqPlaylistCard(
                    playlist = playlist,
                    onClick = { actions.onOpenRecommendation(playlist) },
                    onPlay = { actions.onPlayRecommendation(playlist) },
                    modifier = Modifier.fillMaxWidth(),
                    width = 640.dp,
                )
            }
        }
        item("songs-heading", span = { GridItemSpan(maxLineSpan) }) {
            KugouSectionHeading(
                "为你推荐",
                if (catalog.loadingRecommendedTracks) "正在换一批" else "猜你喜欢",
                actionLabel = if (catalog.loadingRecommendedTracks) "刷新中" else "换一批",
                actionEnabled = !catalog.loadingRecommendedTracks,
                onAction = { actions.onRefreshRecommendedTracks(true) },
            )
        }
        if (catalog.recommendedTracks.isEmpty() && !catalog.loadingRecommendedTracks) {
            item("songs-empty", span = { GridItemSpan(maxLineSpan) }) {
                EmptyState(catalog.recommendedTracksMessage ?: "推荐歌曲暂时不可用")
            }
        } else items(
            catalog.recommendedTracks,
            key = MusicTrack::id,
            span = { GridItemSpan(maxLineSpan.coerceAtMost(2)) },
        ) { track ->
            MusicTrackRow(
                track = track,
                current = state.currentTrack?.id == track.id,
                playing = state.isPlaying && state.currentTrack?.id == track.id,
                favorite = track.id in favorites.state.ids,
                onClick = { viewModel.playTrack(track) },
                onFavorite = { favorites.toggle(track) },
                showSource = false,
            )
        }
        listOfNotNull(catalog.actionMessage, state.playbackMessage, favorites.state.message)
            .distinct().forEachIndexed { index, message ->
                item("message-$index", span = { GridItemSpan(maxLineSpan) }) { EmptyState(message) }
            }
    }
}

@Composable
private fun KugouDailyCard(playlist: MusicPlaylist, playing: Boolean, onOpen: () -> Unit, onPlay: () -> Unit) {
    val day = currentKugouDay().toString().padStart(2, '0')
    Box(
        Modifier.fillMaxWidth().height(236.dp).clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF00A8FF), Color(0xFF0874D1))))
            .clickable(onClickLabel = "打开每日推荐", onClick = onOpen),
    ) {
        Box(Modifier.size(190.dp).align(Alignment.TopEnd).padding(top = 14.dp, end = 12.dp)
            .clip(CircleShape).background(Color.White.copy(alpha = .09f)))
        Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("每日推荐", color = Color.White, style = MaterialTheme.typography.headlineLarge)
                    Text("每天更新你的专属歌单", color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
                }
                Text(day, color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Light)
            }
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                playlist.tracks.take(3).forEachIndexed { index, track ->
                    Text(
                        "${index + 1}. ${track.title} · ${track.artists}",
                        color = Color.White.copy(alpha = if (index == 0) 1f else .76f),
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Surface(
            onClick = onPlay, shape = CircleShape, color = Color.White,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).size(48.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, if (playing) "重新播放每日推荐" else "播放每日推荐",
                    tint = Color(0xFF0874D1), modifier = Modifier.size(25.dp))
            }
        }
    }
}

private fun currentKugouDay(): Int = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)

@Composable
private fun KugouLoadingCard(message: String) {
    Surface(Modifier.fillMaxWidth().height(150.dp), shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Box(contentAlignment = Alignment.Center) {
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun KugouSectionHeading(
    title: String,
    note: String,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onAction: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-.5).sp))
            Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        if (actionLabel != null && onAction != null) FilledTonalButton(
            onClick = onAction, enabled = actionEnabled,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(actionLabel, fontSize = 13.sp)
        }
    }
}
