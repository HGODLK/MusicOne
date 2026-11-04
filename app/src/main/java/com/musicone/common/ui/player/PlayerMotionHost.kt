package com.musicone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun PlayerMotionHost(
    motion: PageMotion,
    viewModel: MusicPlaybackRuntime,
    controlHandoff: PlayerControlHandoff,
    retention: PlayerRetentionState,
    launchCovering: Boolean,
    transitionBackdrop: PlayerTransitionBackdrop,
    sourcePlayer: com.musicone.sourceintegration.SourcePlayerUiBridge? = null,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PlayerKeepScreenOn(motion.mounted, state.isPlaying)
    val phone = isPhonePlayerConfiguration(androidx.compose.ui.platform.LocalConfiguration.current)
    val presentation = LocalPhonePlayerPresentation.current
    val flight = presentation?.flight?.takeIf { phone && sourcePlayer == null }
    val latestDismiss by rememberUpdatedState(onDismiss)
    DisposableEffect(motion) {
        motion.onPreparationAborted = { latestDismiss() }
        onDispose { motion.onPreparationAborted = {} }
    }
    LaunchedEffect(state.playerExpanded) {
        if (motion.wantsOpen != state.playerExpanded) motion.request(state.playerExpanded,
            settle = if (!state.playerExpanded && flight != null) ({ flight.settle() }) else null)
    }
    LaunchedEffect(motion.phase) {
        if (motion.phase == MotionPhase.SHOWN || motion.phase == MotionPhase.HIDDEN) {
            controlHandoff.complete()
            flight?.complete()
        }
    }
    val dismiss = {
        val close = {
            controlHandoff.begin(state.isPlaying)
            onDismiss()
        }
        if (phone && presentation != null) presentation.requestDismiss(close) else close()
    }
    BackHandler(enabled = motion.mounted, onBack = dismiss)
    if (sourcePlayer != null) {
        if (motion.mounted) com.musicone.sourceintegration.SourcePlayerMotionHost(motion, sourcePlayer, dismiss)
        return
    }
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
        RetainedPlayerLayout(motion, presentation.takeIf { phone }, retention) {
            CompositionLocalProvider(LocalPlayerTransitionBackdrop provides transitionBackdrop,
                LocalPhonePlayerFlightRotation provides flight) {
                PhonePlayerPreparedTurnHost(presentation?.preparation?.takeIf { phone }) {
                    PlayerMotionContent(motion, displayed, viewModel, controlHandoff, dismiss)
                }
            }
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
    val rotation = LocalPhonePlayerRotation.current
    val presentation = LocalPhonePlayerPresentation.current
    val flight = LocalPhonePlayerFlightRotation.current
    val surfaceFrame = remember(motion, flight, rotation) { playerSurfaceFlightState(motion, flight, rotation) }
    val drag = when {
        qualityMenu.expanded || presentation?.menuLocks?.active == true -> Modifier
        flight != null && presentation != null && (rotation != 0 || presentation.bodyMoving || flight.handoffActive) -> Modifier.phoneLandscapePlayerDismiss(
            motion, flight, enabled = motion.mounted, turn = presentation.turn,
            coverBounds = {
                val source = if (motion.moving) playerSourceAnchor(motion, "cover") else null
                val target = motion.targetSnapshot["cover"]
                if (source != null && target != null) {
                    val geometry = phonePlayerFlightGeometry(motion, flight, rotation, "cover", source.bounds, target.bounds,
                        surfaceFrame.value)
                    phonePlayerFlightBounds(geometry.bounds, geometry.rotation)
                } else presentation.turn.currentPose("cover")?.bounds() ?: motion.targets["cover"]?.bounds ?: Rect.Zero
            }, viewingAngle = {
                val source = if (motion.moving) playerSourceAnchor(motion, "cover") else null
                val target = motion.targetSnapshot["cover"]
                if (source != null && target != null)
                    phonePlayerFlightGeometry(motion, flight, rotation, "cover", source.bounds, target.bounds,
                        surfaceFrame.value).rotation
                else presentation.turn.currentPose("cover")?.rotation ?: rotation.toFloat()
            }, onDragStarted = presentation::cancelPendingDismissForGesture, onDismiss = dismiss)
        rotation != 0 -> Modifier
        else -> Modifier.playerDismissGesture(motion, regions) { if (!it) dismiss() }
    }
    val displayedPlaying = controlHandoff.displayedPlaying(state.isPlaying)
    val displayedState = if (displayedPlaying == state.isPlaying) state else state.copy(isPlaying = displayedPlaying)
    val contentActivated = rememberPlayerContentActivated(motion)
    CompositionLocalProvider(
        LocalPlayerTouchRegions provides regions,
        LocalPlayerQualityMenu provides qualityMenu,
        LocalPlayerTextHandoff provides textHandoff,
        LocalPlayerAtmosphereTexture provides atmosphereTexture,
        LocalPlayerSurfaceFlightFrame provides surfaceFrame,
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
                    PlayerSurfaceReveal(motion, prewarming) {
                        PlayerVisualContent(displayedState, track, visualTrack, motion, viewModel, dismiss, contentActivated)
                    }
                }
                FlyingPlayerGlass(motion)
                PlayerFlyingContentHost(motion) {
                // 飞行层与两端封面同用可见歌曲，预览未提交时收起也不回退到旧歌。
                FlyingPlayerArtwork(motion, visualTrack)
                FlyingPlayerControls(motion, visualTrack, displayedPlaying)
                FlyingMiniPlayerAccessories(motion, visualTrack.id in LocalMusicFavorites.current.state.ids)
                }
            }
            PlayerQualityMenuOverlay(
                qualityMenu, displayedState, viewModel::ensureQualityOptions, { viewModel.refreshQualityOptions(interactive = true) },
                motion, qualityBackdrop,
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
private fun PlayerVisualContent(state: MusicOneUiState, track: MusicTrack, visualTrack: MusicTrack, motion: PageMotion,
    viewModel: MusicPlaybackRuntime, onDismiss: () -> Unit, contentActivated: Boolean) {
    // 预览切歌优先取预览方向；在换色真正开始时读取，自动下一首沿用 NEXT。
    PlayerAtmosphere(visualTrack, contentActivated) {
        viewModel.rapidTrackSwitch.presentation.value?.direction ?: viewModel.state.value.trackTransitionDirection
    }
    ExpandedPlayerContent(state, track, visualTrack, motion, viewModel, onDismiss, contentActivated)
}

internal fun playerSurfaceReveal(progress: Float): Float {
    val p = (progress.coerceIn(0f, 1f) / .32f).coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}
