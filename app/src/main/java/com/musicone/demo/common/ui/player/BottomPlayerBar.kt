package com.musicone.demo

import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal class BottomBarMeasurements {
    var miniHeight by mutableStateOf(78.dp)
    var navigationHeight by mutableStateOf(70.dp)
    var systemHeight by mutableStateOf(0.dp)
    val homeInset get() = miniHeight + navigationHeight + systemHeight
    val detailInset get() = miniHeight + systemHeight
}

@Composable
internal fun BottomPlayerBar(
    viewModel: MusicOneViewModel,
    playlistMotion: PageMotion,
    playerMotion: PageMotion,
    measurements: BottomBarMeasurements,
    backdropLayer: GraphicsLayer,
    backdropBounds: Rect,
    controlHandoff: PlayerControlHandoff,
    rootNavigation: RootPageNavigation,
    navigationVisible: Boolean,
    animateQqPlayer: Boolean,
    immersiveVisual: NeteaseProfileVisual? = null,
    modifier: Modifier = Modifier,
    searchProgress: () -> Float = { 0f },
) {
    val state by rememberBottomPlayerPresentation(viewModel.state)
    val favorites = LocalMusicFavorites.current
    val toggleFavorite: (MusicTrack) -> Unit = { displayed ->
        val latest = viewModel.state.value.currentTrack
        favorites.toggle(latest?.let { playerVisualActionTrack(it, displayed) } ?: displayed)
    }
    val density = LocalDensity.current
    val systemBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    SideEffect { measurements.systemHeight = systemBottom }
    val openPlayer = {
        // 先进入准备阶段再切换页面状态，避免展开页抢先显示一帧封面。
        controlHandoff.begin(state.isPlaying)
        playerMotion.request(true)
        viewModel.setPlayerExpanded(true)
    }
    Column(modifier.navigationBarsPadding()) {
        Box(Modifier.onSizeChanged { measurements.miniHeight = with(density) { it.height.toDp() } }) {
            val track = state.currentTrack
            if (animateQqPlayer) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = state.currentTrack != null && state.hasQueue,
                    enter = slideInVertically(animationSpec = musicMotion(360), initialOffsetY = { it }),
                    exit = slideOutVertically(animationSpec = musicMotion(320), targetOffsetY = { it }),
                ) {
                    track?.let {
                        MiniPlayer(it, controlHandoff.displayedPlaying(state.isPlaying), it.id in favorites.state.ids, backdropLayer, backdropBounds,
                            immersiveVisual,
                            onOpen = openPlayer, onToggle = viewModel::togglePlay,
                            onNext = viewModel::requestNext, onFavorite = { toggleFavorite(it) },
                            previewSwipe = viewModel::previewMiniSwipe, commitSwipe = viewModel::commitMiniSwipe)
                    }
                }
            } else {
                track?.let {
                    MiniPlayer(it, controlHandoff.displayedPlaying(state.isPlaying), it.id in favorites.state.ids, backdropLayer, backdropBounds,
                        immersiveVisual,
                        onOpen = openPlayer, onToggle = viewModel::togglePlay,
                        onNext = viewModel::requestNext, onFavorite = { toggleFavorite(it) },
                        previewSwipe = viewModel::previewMiniSwipe, commitSwipe = viewModel::commitMiniSwipe)
                }
            }
        }
        Box(Modifier.clipToBounds().layout { measurable, constraints ->
            val child = measurable.measure(constraints.copy(minHeight = 0))
            val visibility = if (navigationVisible) 1f - maxOf(playlistMotion.value, searchProgress()) else 0f
            val height = (child.height * visibility).roundToInt().coerceAtLeast(0)
            layout(child.width, height) { child.placeRelative(0, (child.height * (1f - visibility)).roundToInt()) }
        }.graphicsLayer { alpha = if (navigationVisible) 1f - maxOf(playlistMotion.value, searchProgress()) else 0f }) {
            Box(Modifier.onSizeChanged { measurements.navigationHeight = with(density) { it.height.toDp() } }) {
                BottomCapsule(
                    page = state.page,
                    backdropLayer = backdropLayer,
                    backdropBounds = backdropBounds,
                    selectionPosition = { rootNavigation.position },
                    onSelectionDrag = rootNavigation::dragTo,
                    onPageChange = rootNavigation::animateTo,
                    onNavigationContact = rootNavigation::beginNavigationInteraction,
                    navigationDragBlocked = rootNavigation::isNavigationDragBlocked,
                    immersiveVisual = immersiveVisual,
                )
            }
        }
    }
}
