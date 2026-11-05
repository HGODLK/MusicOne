package com.musicone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.sin

/**
 * 两张真实歌单卡片共享连续进度；方向确认由水平手势检测保留纵向滚动。
 * second 为空时只显示第一张；phoneSideBySide 让手机复用平板的左右并排形态。
 */
@Composable
internal fun PairedLibraryCards(first: MusicPlaylist, second: MusicPlaylist?, onOpen: (MusicPlaylist) -> Unit, onPlay: (MusicPlaylist) -> Unit,
    tabletCardWidth: Dp? = null, firstSharedTransition: Boolean = true, phoneSideBySide: Boolean = false) {
    val cards = listOfNotNull(first, second)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        when {
            tabletCardWidth != null -> SideBySideLibraryCards(cards, onOpen, onPlay,
                tabletCardWidth.coerceAtMost((maxWidth - 20.dp) / 2), 20.dp, first.id, firstSharedTransition)
            // 手机并排宽度与下方两列歌单网格一致，上下卡片边缘对齐。
            phoneSideBySide -> SideBySideLibraryCards(cards, onOpen, onPlay,
                (maxWidth - 16.dp) / 2, 16.dp, first.id, firstSharedTransition)
            second == null -> MainSourcePlaylistCard(first, { onOpen(first) }, { onPlay(first) },
                Modifier.fillMaxWidth().padding(horizontal = 12.dp), width = 1280.dp, prominentTitle = true,
                sharedTransition = firstSharedTransition, sourceKey = "library-pair:${first.id}")
            else -> StackedLibraryCards(first, second, onOpen, onPlay, firstSharedTransition)
        }
    }
}

@Composable
private fun SideBySideLibraryCards(cards: List<MusicPlaylist>, onOpen: (MusicPlaylist) -> Unit, onPlay: (MusicPlaylist) -> Unit,
    cardWidth: Dp, spacing: Dp, firstId: String, firstSharedTransition: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
        cards.forEach { playlist ->
            MainSourcePlaylistCard(playlist, { onOpen(playlist) }, { onPlay(playlist) }, Modifier,
                width = cardWidth,
                prominentTitle = false,
                sharedTransition = playlist.id != firstId || firstSharedTransition,
                sourceKey = "library-pair:${playlist.id}")
        }
    }
}

@Composable
private fun StackedLibraryCards(first: MusicPlaylist, second: MusicPlaylist, onOpen: (MusicPlaylist) -> Unit, onPlay: (MusicPlaylist) -> Unit,
    firstSharedTransition: Boolean) {
    var selected by rememberSaveable(first.id, second.id) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val swipe = remember(first.id, second.id) { LibraryCardSwipeState(selected, scope) { selected = it } }
    val transition = LocalPlaylistCardTransition.current
    val front by remember(swipe) { derivedStateOf { if (swipe.value < .5f) 0 else 1 } }
    fun select(index: Int) { if (transition?.blocksCardSwipe != true) swipe.select(index) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf(first, second).forEachIndexed { index, playlist ->
                Text(playlist.title, fontWeight = FontWeight.Bold,
                    color = if (selected == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.heightIn(min = 44.dp).clickable { select(index) }.padding(vertical = 10.dp))
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).pointerInput(first.id, second.id) {
            val velocity = VelocityTracker()
            var distance = 0f
            var accepted = false
            fun settle(cancelled: Boolean) {
                if (!accepted) return
                accepted = false
                val speed = velocity.calculateVelocity().x
                val target = libraryCardSwipeTarget(swipe.value, distance, speed, size.width.toFloat(),
                    cancelled || transition?.blocksCardSwipe == true, selected)
                swipe.select(target, -speed / size.width.coerceAtLeast(1))
            }
            detectHorizontalDragGestures(
                onDragStart = {
                    distance = 0f; velocity.resetTracking()
                    accepted = transition?.blocksCardSwipe != true
                    if (accepted) swipe.begin()
                },
                onDragEnd = { settle(false) }, onDragCancel = { settle(true) },
            ) { change, delta ->
                if (accepted && transition?.blocksCardSwipe != true) {
                    change.consume()
                    velocity.addPosition(change.uptimeMillis, change.position)
                    distance += delta
                    swipe.move(distance, size.width.toFloat())
                }
            }
        }) {
            listOf(first, second).forEachIndexed { index, playlist ->
                MainSourcePlaylistCard(playlist,
                    { if (selected != index || swipe.moving) select(index) else onOpen(playlist) },
                    { if (selected != index || swipe.moving) select(index) else onPlay(playlist) },
                    Modifier.fillMaxWidth().zIndex(if (front == index) 1f else 0f).graphicsLayer {
                        val p = swipe.value
                        val amount = if (index == 0) p else 1f - p
                        val side = if (index == 0) -1f else 1f
                        translationX = side * size.width * (.10f * amount + .48f * sin(Math.PI.toFloat() * p))
                        scaleX = 1f - .08f * amount; scaleY = scaleX
                    }, width = 1280.dp, prominentTitle = true,
                    sharedTransition = selected == index && !swipe.moving && (index != 0 || firstSharedTransition),
                    sourceKey = "library-pair:${playlist.id}")
            }
        }
    }
}
