package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp

/** 横屏播放键位于物理底边中央，与两侧固定入口共用安全区。 */
@Composable
internal fun PhoneLandscapePlaybackActions(
    state: MusicOneUiState,
    track: MusicTrack,
    motion: PageMotion,
    viewModel: MusicPlaybackRuntime,
    rotation: Int,
    modifier: Modifier = Modifier,
) {
    val presentation = LocalPhonePlayerPresentation.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val buttonSize = minOf(48.dp, ((maxWidth - 52.dp - 128.dp) / 3).coerceAtLeast(1.dp))
        val glyphScale = buttonSize.value / 48f
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides buttonSize) {
            Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().height(58.dp)
                .playerDetailReveal(motion), verticalAlignment = Alignment.CenterVertically) {
                val previousOnLeft = PhonePlayerTrackActionPolicy.previousOnLeft(rotation)
                PhoneLandscapeTrackAction(previousOnLeft, viewModel, buttonSize, rotation)
                PhoneTurnElement("play") {
                    PlaybackControlButton(viewModel::togglePlay,
                        Modifier.size(buttonSize).motionAnchor(motion, "play", true, markSize = 40f * glyphScale)) {
                        PlaybackStateIcon(state.isPlaying, Modifier.size(40.dp * glyphScale).graphicsLayer {
                            rotationZ = presentation?.actionRotation?.value ?: rotation.toFloat()
                        })
                    }
                }
                PhoneLandscapeTrackAction(!previousOnLeft, viewModel, buttonSize, rotation)
            }
        }
        // 非横屏常驻控件保留同名离屏端点，返回时从原退场位置续接。
        PhoneTurnElement("quality", Modifier.align(Alignment.BottomCenter)
            .offset(x = -(maxWidth / 2 + 48.dp)).navigationBarsPadding().height(58.dp)) {
            CompositionLocalProvider(LocalPlayerQualityMenu provides null) {
                AudioQualityButton(track.id, track.source, state.activeQuality, {},
                    Modifier.align(Alignment.Center).size(48.dp).playerDetailReveal(motion))
            }
        }
        PhoneTurnElement("mode", Modifier.align(Alignment.BottomCenter)
            .offset(x = maxWidth / 2 + 48.dp).navigationBarsPadding().height(58.dp)) {
            PlaybackModeButton(playerPlaybackMode(state.shuffle, state.repeatMode), {},
                Modifier.align(Alignment.Center).size(48.dp).playerDetailReveal(motion), enabled = !state.radioActive)
        }
        PhoneLandscapeTimeEndpoints(viewModel, Modifier.align(Alignment.BottomCenter)
            .fillMaxWidth().offset(y = 96.dp).navigationBarsPadding().height(58.dp))
    }
}

@Composable
private fun PhoneLandscapeTrackAction(previous: Boolean, viewModel: MusicPlaybackRuntime, buttonSize: Dp,
    rotation: Int) {
    val key = if (previous) "previous" else "next"
    val turn = LocalPhonePlayerTurn.current
    val presentation = LocalPhonePlayerPresentation.current
    PhoneTurnElement(key) {
        PlaybackControlButton(if (previous) viewModel::requestPrevious else viewModel::requestNext,
            Modifier.size(buttonSize), horizontalMotion = (7 * PhonePlayerTrackActionPolicy.pressDirection(rotation, previous)).dp) {
            Icon(if (previous) PlayerControlIcons.FastRewind else PlayerControlIcons.FastForward,
                if (previous) "上一首" else "下一首", Modifier.size(buttonSize * (32f / 48f)).graphicsLayer {
                    // 图标沿同一次转向逐步旋转，不提前跳到横屏最终方向。
                    rotationZ = PhonePlayerTrackActionPolicy.iconRotation(rotation, turn?.currentPose(key)?.rotation ?: 0f,
                        presentation?.actionRotation?.value ?: rotation.toFloat())
                })
        }
    }
}

@Composable
private fun PhoneLandscapeTimeEndpoints(viewModel: MusicPlaybackRuntime, modifier: Modifier) {
    val timeline by rememberPlayerTimelineSnapshot(viewModel)
    val ink = LocalContentColor.current.copy(alpha = .48f)
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        PhoneTurnElement("elapsed") {
            Text(formatDuration(timeline.positionMs), color = ink, fontSize = 11.sp)
        }
        PhoneTurnElement("remaining") {
            Text("−" + formatDuration((timeline.durationMs - timeline.positionMs).coerceAtLeast(0L)),
                color = ink, fontSize = 11.sp)
        }
    }
}
