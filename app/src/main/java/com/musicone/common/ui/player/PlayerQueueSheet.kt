package com.musicone

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

@Composable
internal fun PlayerQueueSheet(state: PlayerQueuePresentation, viewModel: MusicPlaybackRuntime,
    queueState: PlayerQueueState) {
    val androidContext = LocalContext.current
    val rotation = LocalPhonePlayerRotation.current
    val internallyRotated = rotation != 0
    val sheetShape = remember(rotation) {
        when {
            rotation > 0 -> RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp)
            rotation < 0 -> RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
            else -> RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        }
    }
    // 首次测量就从当前歌曲开始，避免入场时先排列表头，再跳转并测量另一批行。
    val initialIndex = remember { state.queue.indexOfFirst { it.id == state.currentTrackId }.coerceAtLeast(0) }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val fling = rememberPlayerVerticalFling(rotation)
    val reorder = rememberPlayerQueueReorder(list, viewModel)
    val overscroll = rememberOverscrollEffect()
    val context = remember {
        MusicPlaylist("player-queue", MusicSource.QQ, "播放列表", "", "", 0, 0L, 0L, "", emptyList())
    }
    val density = LocalDensity.current
    val unscaled = remember(density.density) { Density(density.density, 1f) }
    MaterialTheme(colorScheme = MusicOneDarkColors) {
        CompositionLocalProvider(LocalPlaybackQueueActions provides reorder.actions) {
            MainSourcePlaylistSongMenuHost(context, emptyList(), 0.dp, {}, {}, {}, addOnly = true,
                avoidQueueRow = true, queueMenu = true, menuRotation = rotation) {
                PhonePlayerWindowHost(rotation) {
                    Box(Modifier.fillMaxSize().clip(sheetShape)) {
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
                            // 内部横屏的宿主已转换并避让安全区，普通窗口沿用原有导航栏留白。
                            Row(Modifier.fillMaxSize()) {
                                if (rotation > 0) PlayerQueueSideHandle(queueState)
                                Column(Modifier.weight(1f).fillMaxHeight().then(
                                    if (internallyRotated) Modifier else Modifier.navigationBarsPadding())) {
                                    PlayerQueueHeader(state.queue.size,
                                        Modifier.queueDismissGesture(queueState) { true }, showHandle = !internallyRotated)
                                    Box(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp)
                                        .clipToBounds().onGloballyPositioned {
                                            reorder.positionViewport(it)
                                        }) {
                                        LazyColumn(
                                            modifier = Modifier.fillMaxSize().then(fling.modifier).nestedScroll(reorder.scrollConnection)
                                                .onGloballyPositioned { reorder.layoutQueue(state.queue) }
                                                .queueDismissGesture(queueState) {
                                                queueListCanStartDismiss(!list.canScrollBackward,
                                                    list.isScrollInProgress, overscroll?.isInProgress == true,
                                                    queueState.motion.phase)
                                            },
                                            state = list,
                                            flingBehavior = fling.behavior,
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
                                                    .clickable(enabled = !owned) {
                                                        val status = queueSourceStatus(track.source)
                                                        if (status == QueueSourceStatus.AVAILABLE || status == QueueSourceStatus.INITIALIZING) viewModel.playTrack(track)
                                                        else android.widget.Toast.makeText(androidContext, status.message(track.source), android.widget.Toast.LENGTH_SHORT).show()
                                                    }) {
                                                    QueueRow(index, track, state.currentTrackId == track.id, state.isPlaying,
                                                        interactive = !owned)
                                                }
                                            }
                                        }
                                        PlayerQueueReorderOverlay(reorder)
                                    }
                                }
                                if (rotation < 0) PlayerQueueSideHandle(queueState)
                            }
                        }
                    }
                }
            }
        }
    }
}
