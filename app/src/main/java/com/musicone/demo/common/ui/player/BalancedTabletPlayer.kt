package com.musicone.demo

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer

@Composable
internal fun BalancedTabletPlayer(
    state: MusicOneUiState,
    track: MusicTrack,
    visualTrack: MusicTrack,
    motion: PageMotion,
    viewModel: MusicOneViewModel,
    contentActivated: Boolean,
    onQueue: () -> Unit,
) {
    val favorites = LocalMusicFavorites.current
    val actionTrack = playerVisualActionTrack(track, visualTrack)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .fillMaxSize()
                .widthIn(max = 1_440.dp)
                .padding(horizontal = 52.dp),
            horizontalArrangement = Arrangement.spacedBy(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .padding(vertical = 28.dp),
            ) {
                PlayerCoverStage(visualTrack, state.isPlaying, motion, Modifier.weight(1f).fillMaxWidth())
                PlayerSongInformation(
                    visualTrack,
                    visualTrack.id in favorites.state.ids,
                    motion,
                    actionTrack = actionTrack,
                ) { favorites.toggle(actionTrack) }
                ImmersivePlaybackControls(
                    state,
                    track,
                    motion,
                    viewModel,
                    true,
                    true,
                    {},
                    onQueue,
                    Modifier,
                )
            }
            Box(
                // 右侧歌词保持完整可用高度，切换与换曲动画才能抵达真实上下边缘。
                Modifier.weight(1f).fillMaxHeight(),
            ) {
                TabletLyricsEntrance(
                    contentActivated,
                    track,
                    state.lyricLoadState,
                    state.trackTransitionDirection,
                    viewModel,
                    motion,
                )
            }
        }
    }
}

@Composable
private fun TabletLyricsEntrance(
    visible: Boolean,
    track: MusicTrack,
    loadState: LyricLoadState,
    trackTransitionDirection: TrackTransitionDirection,
    viewModel: MusicOneViewModel,
    motion: PageMotion,
) {
    val prewarming = LocalPlayerPrewarming.current
    val active = LocalPlayerVisible.current
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(motion.phase, visible) {
        if (!motion.mounted) entrance.snapTo(0f)
        else if (visible && motion.phase == MotionPhase.SHOWN) entrance.animateTo(1f, musicMotion(360))
    }
    // 歌词列表常驻，仅移动外层；预热时在遮罩后真实绘制，进入时保留原上滑效果。
    if (visible) Box(Modifier.fillMaxSize().graphicsLayer {
        translationY = if (prewarming) 0f else size.height * (1f - entrance.value)
    }) {
        PlayerSyncedLyrics(
            track,
            loadState,
            viewModel,
            Modifier.fillMaxSize().playerDetailReveal(motion),
            currentAnchorFraction = .22f,
            trackTransitionDirection = trackTransitionDirection,
            active = active,
            followPlayback = active,
            prepareWhileHidden = prewarming,
        )
    }
}
