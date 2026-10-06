package com.musicone.demo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerQueueSheet(state: MusicOneUiState, viewModel: MusicOneViewModel, onDismiss: () -> Unit) {
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() * .8f }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) {
        list.scrollToItem(state.queue.indexOfFirst { it.id == state.currentTrack?.id }.coerceAtLeast(0))
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = true, dragHandle = null,
        containerColor = Color(0xFF242629), contentColor = Color.White) {
        val context = androidx.compose.runtime.remember { MusicPlaylist("player-queue", MusicSource.QQ,
            "播放列表", "", "", 0, 0L, 0L, "", emptyList()) }
        Box(Modifier.fillMaxWidth().height(height)) {
        MaterialTheme(colorScheme = MusicOneDarkColors) {
        QqPlaylistSongMenuHost(context, emptyList(), 0.dp, {}, {}, {}, addOnly = true, avoidQueueRow = true) {
        Column(Modifier.fillMaxSize().background(Color(0xFF242629)).padding(top = 18.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(4.dp)
            .background(Color.White.copy(alpha = .28f), RoundedCornerShape(2.dp)))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("播放列表", fontSize = 23.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp), state = list,
            contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(state.queue, key = { _, track -> track.id }) { index, track ->
                Box(Modifier.clickable { viewModel.playTrack(track) }) {
                    QueueRow(index, track, state.currentTrack?.id == track.id, state.isPlaying)
                }
            }
        }
        }
        }
        }
        }
    }
}
