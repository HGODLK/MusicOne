package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun PlayerMotionHost(
    motion: PageMotion,
    viewModel: MusicPlaybackRuntime,
    controlHandoff: PlayerControlHandoff,
    retention: PlayerRetentionState,
    launchCovering: Boolean,
    transitionBackdrop: PlayerTransitionBackdrop,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.playerExpanded) {
        if (motion.wantsOpen != state.playerExpanded) motion.request(state.playerExpanded)
    }
    LaunchedEffect(motion.phase) {
        if (motion.phase == MotionPhase.SHOWN || motion.phase == MotionPhase.HIDDEN) controlHandoff.complete()
    }
    val dismiss = {
        controlHandoff.begin(state.isPlaying)
        onDismiss()
    }
    BackHandler(enabled = motion.mounted, onBack = dismiss)
    if (!retention.shouldMount(motion.mounted)) return
    val prewarming = launchCovering && retention.allowed && !motion.mounted
    // 隐藏时只同步歌曲和歌词变更以准备本地布局，不跟随播放进度持续刷新。
    var retainedState by remember { mutableStateOf(state) }
    val displayed = if (motion.mounted || prewarming) state else retainedState
    SideEffect {
        if (motion.mounted || prewarming || retainedState.currentTrack != state.currentTrack ||
            retainedState.lyricLoadState != state.lyricLoadState) retainedState = state
    }
    RetainedPlayerContent(motion.mounted, prewarming, retention) {
        CompositionLocalProvider(LocalPlayerTransitionBackdrop provides transitionBackdrop) {
            PlayerMotionContent(motion, displayed, viewModel, controlHandoff, dismiss)
        }
    }
}

@Composable
private fun PlayerMotionContent(motion: PageMotion, state: MusicOneUiState,
    viewModel: MusicPlaybackRuntime, controlHandoff: PlayerControlHandoff, dismiss: () -> Unit) {
    val track = state.currentTrack ?: PlayerWarmupTrack
    val visualTrack by rememberPlayerVisualTrack(track, viewModel.rapidTrackSwitch, viewModel.state)
    val prewarming = LocalPlayerPrewarming.current
    val regions = remember { PlayerTouchRegions() }
    val qualityMenu = remember { PlayerQualityMenuState() }
    val qualityBackdrop = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val textHandoff = rememberPlayerTextHandoff()
    val atmosphereTexture = rememberGraphicsLayer()
    val drag = if (qualityMenu.expanded) Modifier else
        Modifier.playerDismissGesture(motion, regions) { if (!it) dismiss() }
    val displayedPlaying = controlHandoff.displayedPlaying(state.isPlaying)
    val displayedState = if (displayedPlaying == state.isPlaying) state else state.copy(isPlaying = displayedPlaying)
    val revealShape = remember(motion) { PlayerRevealShape(motion) }
    val contentActivated = rememberPlayerContentActivated(motion)
    CompositionLocalProvider(
        LocalPlayerTouchRegions provides regions,
        LocalPlayerQualityMenu provides qualityMenu,
        LocalPlayerTextHandoff provides textHandoff,
        LocalPlayerAtmosphereTexture provides atmosphereTexture,
    ) {
        PlayerRelatedMenuHost(displayedState, viewModel) { relatedOpen ->
        Box(Modifier.fillMaxSize().then(if (relatedOpen) Modifier else drag).onGloballyPositioned { motion.updateHost(it.boundsInRoot()) }) {
            Box(Modifier.fillMaxSize().playerQualityBackdropSnapshot(qualityBackdrop, qualityMenu.expanded)) {
                // 常驻命中层阻止空白区域穿透；不消费事件，让父级退出手势和歌词正常工作。
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                })
                if (motion.moving || motion.phase == MotionPhase.PREPARING) {
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = motion.value }
                        .background(Color.Black.copy(alpha = .22f)).clickable(onClick = dismiss))
                }
                Box(Modifier.align(Alignment.BottomCenter).fillMaxSize()
                    .motionAnchor(motion, "surface", true, hideDuringMotion = false)) {
                    val surfaceModifier = when {
                        prewarming -> Modifier
                        motion.phase == MotionPhase.PREPARING -> Modifier.graphicsLayer { alpha = 0f }
                        motion.moving -> Modifier.graphicsLayer {
                            // 迷你玻璃留在源位置，页面背景在最终坐标中逐步显露，避免把模糊纹理拉伸到全屏。
                            alpha = playerSurfaceReveal(motion.value)
                            shape = revealShape
                            clip = true
                        }
                        // 隐藏阶段也保留绘制门控，不能在父布局撤下前露出已复位的标题。
                        else -> Modifier.graphicsLayer { alpha = if (motion.mounted) 1f else 0f }
                    }
                    Box(Modifier.fillMaxSize().then(surfaceModifier)) {
                        PlayerVisualContent(displayedState, track, motion, viewModel, dismiss, contentActivated)
                    }
                }
                FlyingPlayerGlass(motion)
                FlyingPlayerArtwork(motion, track)
                FlyingPlayerControls(motion, visualTrack, displayedPlaying)
                FlyingMiniPlayerAccessories(motion, track.id in LocalMusicFavorites.current.state.ids)
            }
            PlayerQualityMenuOverlay(
                qualityMenu, displayedState, viewModel::refreshQualityOptions, motion, qualityBackdrop,
            )
            if (motion.moving || motion.phase == MotionPhase.PREPARING) {
                Box(Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                })
            }
        }
        }
    }
}

@Composable
private fun PlayerVisualContent(state: MusicOneUiState, track: MusicTrack, motion: PageMotion,
    viewModel: MusicPlaybackRuntime, onDismiss: () -> Unit, contentActivated: Boolean) {
    val visualTrack by rememberPlayerVisualTrack(track, viewModel.rapidTrackSwitch, viewModel.state)
    PlayerAtmosphere(visualTrack, contentActivated)
    ExpandedPlayerContent(state, track, visualTrack, motion, viewModel, onDismiss, contentActivated)
}

internal fun playerSurfaceReveal(progress: Float): Float {
    val p = (progress.coerceIn(0f, 1f) / .32f).coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

private class PlayerRevealShape(private val motion: PageMotion) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val target = motion.targetSnapshot["surface"]?.bounds ?: motion.targets["surface"]?.bounds
        val source = playerSourceAnchor(motion, "surface")?.bounds
        val p = motion.value
        val rect = if (motion.moving && source != null && target != null) {
            motionRect(source, target, p).translate(-target.left, -target.top)
        } else Rect(0f, 0f, size.width, size.height)
        val corner = CornerRadius(with(density) { playerSurfaceCorner(p).dp.toPx() })
        return Outline.Rounded(RoundRect(rect, corner, corner, corner, corner))
    }
}
