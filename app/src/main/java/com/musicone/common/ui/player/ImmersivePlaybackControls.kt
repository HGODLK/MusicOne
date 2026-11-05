package com.musicone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun ImmersivePlaybackControls(state: MusicOneUiState, track: MusicTrack, motion: PageMotion,
    viewModel: MusicPlaybackRuntime, tablet: Boolean, lyrics: Boolean, onLyrics: () -> Unit,
    onQueue: () -> Unit, modifier: Modifier = Modifier,
    lyricsProgress: () -> Float = { if (lyrics) 1f else 0f }) {
    val metrics = playerControlsMetrics()
    Column(modifier.fillMaxWidth()) {
        PlayerProgressTimeline(viewModel, motion, Modifier.playerDetailReveal(motion))
        Row(Modifier.fillMaxWidth().height(if (tablet) metrics.tabletRow else metrics.row),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            PhoneTurnElement("quality") {
                AudioQualityButton(
                    track.id,
                    track.source,
                    state.activeQuality,
                    viewModel::selectQuality,
                    Modifier.size(metrics.sideButton).playerDetailReveal(motion),
                )
            }
            PhoneTurnElement("previous") {
                PlaybackControlButton(
                    onClick = viewModel::requestPrevious,
                    modifier = Modifier.size(metrics.trackButton).playerDetailReveal(motion),
                    horizontalMotion = (-7).dp,
                ) {
                    val phoneTurn = LocalPhonePlayerTurn.current
                    val presentation = LocalPhonePlayerPresentation.current
                    Icon(PlayerControlIcons.FastRewind, "上一首", Modifier.size(metrics.trackIcon).graphicsLayer {
                        rotationZ = PhonePlayerTrackActionPolicy.iconRotation(0, phoneTurn?.currentPose("previous")?.rotation ?: 0f,
                            presentation?.actionRotation?.value ?: 0f)
                    })
                }
            }
            PhoneTurnElement("play") {
                PlaybackControlButton(
                    onClick = viewModel::togglePlay,
                    modifier = Modifier.size(metrics.playButton)
                        .motionAnchor(motion, "play", true, markSize = metrics.playIcon.value),
                ) {
                    val presentation = LocalPhonePlayerPresentation.current
                    val phoneTurn = LocalPhonePlayerTurn.current
                    PlaybackStateIcon(state.isPlaying, Modifier.size(metrics.playIcon).graphicsLayer {
                        if (phoneTurn != null) rotationZ = presentation?.actionRotation?.value ?: 0f
                    })
                }
            }
            PhoneTurnElement("next") {
                PlaybackControlButton(
                    onClick = viewModel::requestNext,
                    modifier = Modifier.size(metrics.trackButton).playerDetailReveal(motion),
                    horizontalMotion = 7.dp,
                ) {
                    val phoneTurn = LocalPhonePlayerTurn.current
                    val presentation = LocalPhonePlayerPresentation.current
                    Icon(PlayerControlIcons.FastForward, "下一首", Modifier.size(metrics.trackIcon).graphicsLayer {
                        rotationZ = PhonePlayerTrackActionPolicy.iconRotation(0, phoneTurn?.currentPose("next")?.rotation ?: 0f,
                            presentation?.actionRotation?.value ?: 0f)
                    })
                }
            }
            PhoneTurnElement("mode") {
                PlaybackModeButton(
                    playerPlaybackMode(state.shuffle, state.repeatMode),
                    viewModel::cyclePlaybackMode,
                    Modifier.size(metrics.sideButton).playerDetailReveal(motion),
                    enabled = !state.radioActive,
                )
            }
        }
        Column(Modifier.playerDetailReveal(motion)) {
            val configuration = LocalViewConfiguration.current
            val forgivingTap = remember(configuration) {
                object : ViewConfiguration by configuration {
                    override val touchSlop: Float = configuration.touchSlop * 1.5f
                }
            }
            CompositionLocalProvider(LocalViewConfiguration provides forgivingTap) {
            val fixedActions = LocalPhonePlayerFixedActions.current
            val lyricsAction: @Composable () -> Unit = {
                if (!tablet && !fixedActions) {
                    val retryLyrics = state.lyricLoadState == LyricLoadState.RETRYABLE && track.lyrics.isEmpty()
                    IconButton({
                        if (retryLyrics && !lyrics) viewModel.retryLyrics()
                        onLyrics()
                    }, Modifier.size(width = 64.dp, height = 56.dp).semantics {
                        contentDescription = if (lyrics) "关闭歌词" else "开启歌词"
                    }) {
                        LyricsToggleIcon(lyricsProgress, Modifier.size(28.dp))
                    }
                } else Spacer(Modifier.size(48.dp))
            }
            val queueAction: @Composable () -> Unit = {
                if (!tablet && fixedActions) Spacer(Modifier.size(width = 64.dp, height = 56.dp))
                else IconButton(onQueue, Modifier.size(width = 64.dp, height = 56.dp)) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放列表", Modifier.size(28.dp))
                }
            }
            // 紧凑模式下这一行只收窄占位，按钮和手机固定按钮行都保持原尺寸。
            Row(Modifier.fillMaxWidth().height(metrics.bottomRow), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                // 平板横屏只有播放列表按钮，没有可交换的对象。
                if (PhonePlayerPreferences.swapBottomActions && !tablet) { queueAction(); lyricsAction() }
                else { lyricsAction(); queueAction() }
            }
            }
        }
    }
}

@Composable
internal fun PlayerProgressTimeline(viewModel: MusicPlaybackRuntime, motion: PageMotion?, modifier: Modifier,
    compactBottomTrack: Boolean = false) {
    val ink = LocalContentColor.current
    val visible = LocalPlayerVisible.current
    // 横屏底边进度条有自己的布局，不跟随竖屏紧凑模式。
    val metrics = if (compactBottomTrack) PlayerControlsMetrics.Normal else playerControlsMetrics()
    val timeline by rememberPlayerTimelineSnapshot(viewModel)
    val switching = timeline.switching
    val lyricSeek by viewModel.seekPreview.lyricSeek.collectAsStateWithLifecycle()
    val entrance = if (motion == null) null else
        rememberPlayerProgressEntrance(motion, timeline.trackId, switching, lyricSeek?.revision)
    val position = timeline.positionMs
    val progressMotion = rememberPlayerProgressMotion(
        trackId = timeline.trackId,
        positionMs = position,
        durationMs = timeline.durationMs,
        advancing = visible && timeline.advancing,
        switching = visible && switching,
        lyricSeek = lyricSeek,
        active = visible,
    )
    val scope = rememberCoroutineScope()
    val sliderInteraction = remember { MutableInteractionSource() }
    val dragging by sliderInteraction.collectIsDraggedAsState()
    var seeking by remember(timeline.trackId) { mutableStateOf<Float?>(null) }
    var userDragged by remember(timeline.trackId) { mutableStateOf(false) }
    var tapSeekJob by remember(timeline.trackId) { mutableStateOf<Job?>(null) }
    PlayerHighFrameRateWhile { tapSeekJob?.isActive == true || progressMotion.interacting || (entrance?.value ?: 1f) < 1f }
    DisposableEffect(timeline.trackId) { onDispose { tapSeekJob?.cancel(); viewModel.seekPreview.clear() } }
    val progress = seeking ?: if (switching) 0f else playerProgressFraction(position, timeline.durationMs)
    Column(modifier) {
        PlayerThinSlider(progress, {
            entrance?.cancel()
            if (seeking == null) userDragged = dragging
            if (dragging) {
                tapSeekJob?.cancel()
                userDragged = true
            }
            seeking = it
            // 单击先保留歌词当前画面；只有确认拖动后才让歌词实时跟手。
            if (shouldPreviewLyricsDuringProgressChange(dragging, userDragged)) {
                viewModel.seekPreview.update(it, timeline.durationMs)
            }
        }, "播放进度", onFinished = {
            val target = seeking ?: return@PlayerThinSlider
            // 在任何挂起或预览清空之前封存目标，中间态歌词不能回读上一帧正式位置。
            val commit = viewModel.seekPreview.prepareProgressCommit(timeline.trackId,
                viewModel.playbackProgress.value.generation, (timeline.durationMs * target).toLong())
            if (userDragged) {
                scope.launch {
                    // 拖动过程已经跟手，松手只需把权威进度接到同一像素，不能先跳回旧位置。
                    progressMotion.snapTo(target)
                    if (!viewModel.seekToForTrack(timeline.trackId, target, commit.revision)) return@launch
                    seeking = null
                    userDragged = false
                }
            } else {
                val revision = progressMotion.beginSeek(target)
                // 先记录歌词的真实起点，再提交音频；歌词和时间轴分别从各自当前帧续接。
                val lyricRequest = viewModel.seekPreview.requestProgressLyricSeek(
                    timeline.trackId,
                    position,
                    target,
                    generation = viewModel.playbackProgress.value.generation,
                )
                tapSeekJob?.cancel()
                tapSeekJob = scope.launch {
                    // 先完成目标歌词准备，避免新窗口构建与音频跳转解码同时竞争；未挂载时仍按原上限放行。
                    withTimeoutOrNull(120L) { lyricRequest.targetReady.await() }
                    if (!viewModel.seekToForTrack(timeline.trackId, target, commit.revision)) return@launch
                    seeking = null
                    userDragged = false
                    progressMotion.animateSeek(revision)
                }
            }
        }, enabled = !switching, interactionSource = sliderInteraction,
            visualValue = { playerProgressVisualValue(seeking, userDragged, progressMotion.value) * (entrance?.value ?: 1f) },
            compactBottomTrack = compactBottomTrack, height = metrics.timeline)
        if (!compactBottomTrack) Row(Modifier.fillMaxWidth().playerTimeLabelsLift(metrics.timeLabelsLift),
            horizontalArrangement = Arrangement.SpaceBetween) {
            PhoneTurnElement("elapsed") {
                Text(formatDuration((progress * timeline.durationMs).toLong()), color = ink.copy(alpha = .48f), fontSize = 11.sp)
            }
            PhoneTurnElement("remaining") {
                Text("−" + formatDuration(((1f - progress) * timeline.durationMs).toLong()), color = ink.copy(alpha = .48f), fontSize = 11.sp)
            }
        }
    }
}

internal fun playerProgressVisualValue(seeking: Float?, dragged: Boolean, animated: Float): Float =
    if (dragged) seeking ?: animated else animated

internal fun shouldPreviewLyricsDuringProgressChange(dragging: Boolean, dragged: Boolean): Boolean =
    dragging || dragged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerThinSlider(value: Float, onValue: (Float) -> Unit, label: String,
    modifier: Modifier = Modifier, onFinished: (() -> Unit)? = null, enabled: Boolean = true,
    interactionSource: MutableInteractionSource,
    visualValue: () -> Float = { value }, compactBottomTrack: Boolean = false, height: Dp = 64.dp) {
    val ink = LocalContentColor.current
    // 扩大上下触摸空间，细线绘制与原有点击、拖动判定保持独立。
    // 底边模式连同滑块的命中区域一起下移，避免只移动细线后触摸仍留在上方。
    PhoneTurnElement("timeline", modifier.fillMaxWidth().height(height)
        .offset(y = if (compactBottomTrack) 27.dp else 0.dp)) {
    Slider(value, onValue, Modifier.fillMaxWidth()
        .semantics { contentDescription = label }, enabled = enabled,
        onValueChangeFinished = onFinished, interactionSource = interactionSource,
        thumb = { Spacer(Modifier.size(0.dp)) },
        track = { slider ->
            Canvas(Modifier.fillMaxWidth().height(5.dp)) {
                val y = size.height / 2
                drawLine(ink.copy(alpha = .23f), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                drawLine(ink.copy(alpha = .72f), Offset(0f, y), Offset(size.width * visualValue(), y), size.height, StrokeCap.Round)
            }
        })
    }
}
