package com.musicone.demo

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    viewModel: MusicOneViewModel, tablet: Boolean, lyrics: Boolean, onLyrics: () -> Unit,
    onQueue: () -> Unit, modifier: Modifier = Modifier,
    lyricsProgress: () -> Float = { if (lyrics) 1f else 0f }) {
    val qqFeedback = track.source == MusicSource.QQ
    Column(modifier.fillMaxWidth()) {
        PlayerProgressTimeline(viewModel, Modifier.playerDetailReveal(motion))
        Row(Modifier.fillMaxWidth().height(if (tablet) 80.dp else 100.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            AudioQualityButton(
                track.id,
                track.source,
                state.activeQuality,
                viewModel::selectQuality,
                Modifier.size(48.dp).playerDetailReveal(motion),
            )
            PlaybackControlButton(
                qqFeedback = qqFeedback,
                onClick = viewModel::requestPrevious,
                modifier = Modifier.size(56.dp).playerDetailReveal(motion),
                horizontalMotion = (-7).dp,
            ) {
                Icon(Icons.Default.FastRewind, "上一首", Modifier.size(38.dp))
            }
            PlaybackControlButton(
                qqFeedback = qqFeedback,
                onClick = viewModel::togglePlay,
                modifier = Modifier.size(70.dp).motionAnchor(motion, "play", true),
            ) {
                PlaybackStateIcon(state.isPlaying, qqFeedback, Modifier.size(54.dp))
            }
            PlaybackControlButton(
                qqFeedback = qqFeedback,
                onClick = viewModel::requestNext,
                modifier = Modifier.size(56.dp).playerDetailReveal(motion),
                horizontalMotion = 7.dp,
            ) {
                Icon(Icons.Default.FastForward, "下一首", Modifier.size(38.dp))
            }
            PlaybackModeButton(
                playerPlaybackMode(state.shuffle, state.repeatMode),
                viewModel::cyclePlaybackMode,
                Modifier.size(48.dp).playerDetailReveal(motion),
                enabled = !state.qqRadioActive,
            )
        }
        Column(Modifier.playerDetailReveal(motion)) {
            val configuration = LocalViewConfiguration.current
            val forgivingTap = remember(configuration) {
                object : ViewConfiguration by configuration {
                    override val touchSlop: Float = configuration.touchSlop * 1.5f
                }
            }
            CompositionLocalProvider(LocalViewConfiguration provides forgivingTap) {
            Row(Modifier.fillMaxWidth().height(58.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                if (!tablet) {
                    IconButton(onLyrics, Modifier.size(width = 64.dp, height = 56.dp).semantics {
                        contentDescription = if (lyrics) "关闭歌词" else "开启歌词"
                    }) {
                        LyricsToggleIcon(lyricsProgress, Modifier.size(28.dp))
                    }
                } else Spacer(Modifier.size(48.dp))
                IconButton(onQueue, Modifier.size(width = 64.dp, height = 56.dp)) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, "播放列表", Modifier.size(28.dp))
                }
            }
            }
        }
    }
}

@Composable
private fun PlayerProgressTimeline(viewModel: MusicOneViewModel, modifier: Modifier) {
    val ink = LocalContentColor.current
    val visible = LocalPlayerVisible.current
    val timeline by rememberPlayerTimelineSnapshot(viewModel)
    val switching = timeline.switching
    val lyricSeek by viewModel.seekPreview.lyricSeek.collectAsStateWithLifecycle()
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
    DisposableEffect(timeline.trackId) { onDispose { tapSeekJob?.cancel(); viewModel.seekPreview.clear() } }
    val progress = seeking ?: if (switching) 0f else playerProgressFraction(position, timeline.durationMs)
    Column(modifier) {
        PlayerThinSlider(progress, {
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
            if (userDragged) {
                scope.launch {
                    // 拖动过程已经跟手，松手只需把权威进度接到同一像素，不能先跳回旧位置。
                    progressMotion.snapTo(target)
                    viewModel.seekToForTrack(timeline.trackId, target)
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
                    // 歌词页未挂载时不阻塞操作；已挂载时先锁住旧图层再更新权威进度。
                    withTimeoutOrNull(120L) { lyricRequest.animationPrepared.await() }
                    viewModel.seekToForTrack(timeline.trackId, target)
                    seeking = null
                    userDragged = false
                    progressMotion.animateSeek(revision)
                }
            }
        }, enabled = !switching, interactionSource = sliderInteraction,
            visualValue = { playerProgressVisualValue(seeking, userDragged, progressMotion.value) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration((progress * timeline.durationMs).toLong()), color = ink.copy(alpha = .48f), fontSize = 11.sp)
            Text("−" + formatDuration(((1f - progress) * timeline.durationMs).toLong()), color = ink.copy(alpha = .48f), fontSize = 11.sp)
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
    visualValue: () -> Float = { value }) {
    val ink = LocalContentColor.current
    Slider(value, onValue, modifier.height(48.dp).semantics { contentDescription = label }, enabled = enabled,
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
