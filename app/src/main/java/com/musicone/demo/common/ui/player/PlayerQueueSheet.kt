package com.musicone.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

@Composable
internal fun PlayerQueueSheet(state: PlayerQueuePresentation, viewModel: MusicPlaybackRuntime,
    queueState: PlayerQueueState) {
    val list = rememberLazyListState()
    val reorder = rememberPlayerQueueReorder(list, viewModel)
    val overscroll = rememberOverscrollEffect()
    // 仅在本次挂载时定位，切歌与中途反向打开不抢走用户的滚动位置。
    LaunchedEffect(Unit) {
        if (state.queue.isNotEmpty()) {
            list.scrollToItem(state.queue.indexOfFirst { it.id == state.currentTrackId }.coerceAtLeast(0))
        }
    }
    val context = remember {
        MusicPlaylist("player-queue", MusicSource.QQ, "播放列表", "", "", 0, 0L, 0L, "", emptyList())
    }
    val density = LocalDensity.current
    val unscaled = remember(density.density) { Density(density.density, 1f) }
    MaterialTheme(colorScheme = MusicOneDarkColors) {
        CompositionLocalProvider(LocalPlaybackQueueActions provides reorder.actions) {
            QqPlaylistSongMenuHost(context, emptyList(), 0.dp, {}, {}, {}, addOnly = true,
                avoidQueueRow = true, queueMenu = true) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))) {
                    // 实体底板独立命中，标题间隙和空列表不能把点击交给后面的关闭遮罩。
                    Box(Modifier.matchParentSize().background(Color(0xFF242629)).pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false).consume()
                            do {
                                val event = awaitPointerEvent()
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    })
                    // 字号隔离只覆盖队列内容，更多菜单继续使用原来的字体环境。
                    CompositionLocalProvider(LocalDensity provides unscaled) {
                        Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                            PlayerQueueHeader(state.queue.size,
                                Modifier.queueDismissGesture(queueState) { true })
                            Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)
                                .clipToBounds().onGloballyPositioned {
                                    reorder.positionViewport(it.positionInRoot().y, it.size.height)
                                }) {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize().nestedScroll(reorder.scrollConnection)
                                        .onGloballyPositioned { reorder.layoutQueue(state.queue) }
                                        .queueDismissGesture(queueState) {
                                        queueListCanStartDismiss(!list.canScrollBackward,
                                            list.isScrollInProgress, overscroll?.isInProgress == true,
                                            queueState.motion.phase)
                                    },
                                    state = list,
                                    overscrollEffect = overscroll,
                                    contentPadding = PaddingValues(bottom = 24.dp),
                                ) {
                                    // 空状态也留在同一个列表里，最后一行仍能完成退出动画。
                                    if (state.queue.isEmpty()) item(key = "queue-empty", contentType = "queue-empty") {
                                        Box(Modifier.fillParentMaxHeight().fillMaxWidth().animateItem(
                                            fadeInSpec = musicMotion(220), placementSpec = null, fadeOutSpec = musicMotion(180)),
                                            contentAlignment = Alignment.Center) {
                                            Text("播放列表为空", color = Color.White.copy(alpha = .6f))
                                        }
                                    }
                                    itemsIndexed(state.queue, key = { _, track -> track.id },
                                        contentType = { _, _ -> "queue-track" }) { index, track ->
                                        val owned = reorder.owns(track.id)
                                        var wasOwned by remember(track.id) { mutableStateOf(false) }
                                        SideEffect { if (owned) wasOwned = true }
                                        val itemMotion = if (owned || wasOwned) Modifier else Modifier.animateItem(
                                            fadeInSpec = musicMotion(220), placementSpec = musicSpring(),
                                            fadeOutSpec = musicMotion(180))
                                        // 先以自然位置交接，布局确认后再恢复原生动画。
                                        Box(itemMotion.queueReorderRow(reorder, track.id, owned)
                                            .onGloballyPositioned { if (!owned && wasOwned) wasOwned = false }
                                            .clickable(enabled = !owned) { viewModel.playTrack(track) }) {
                                            QueueRow(index, track, state.currentTrackId == track.id, state.isPlaying,
                                                interactive = !owned)
                                        }
                                    }
                                }
                                PlayerQueueReorderOverlay(reorder)
                            }
                        }
                    }
                }
            }
        }
    }
}
