package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

private const val AUTOMIX_LYRIC_HOLD_POLL_MS = 100L

/**
 * 播放页歌词的自然过渡保持状态：非空时歌词窗口继续停在 plan.from。
 * 交接点随歌曲切换同步判定；保持期间低频复查，下一首开唱或被打断时释放并唤醒正常切歌。
 */
@Composable
internal fun rememberAutoMixLyricHold(viewModel: MusicPlaybackRuntime, track: MusicTrack): AutoMixPlan? {
    val polled = remember(viewModel) { mutableStateOf(viewModel.autoMixHeldLyricPlan()) }
    LaunchedEffect(viewModel) {
        combine(AutoMixSession.active, AutoMixSession.lastEnd, viewModel.state, viewModel.seekPreview.position) { _, _, _, _ -> }
            .collectLatest {
                while (true) {
                    val plan = viewModel.autoMixHeldLyricPlan()
                    polled.value = plan
                    if (plan == null) break
                    delay(AUTOMIX_LYRIC_HOLD_POLL_MS)
                }
            }
    }
    // 歌曲在交接点切换的这一帧就要得到保持结果，不能等轮询晚一帧。
    return remember(track.source, track.id, polled.value) { viewModel.autoMixHeldLyricPlan() }
}

/** 过渡途中才挂载的歌词：直接从上一首停留的位置开始，而不是先显示下一首。 */
internal fun autoMixHeldLyricWindow(plan: AutoMixPlan): LyricWindow? {
    val from = plan.from
    if (from.lyrics.isEmpty()) return null
    val presentation = lyricPresentationRequest(from, LyricLoadState.READY, TrackTransitionDirection.NEXT).presentation
        ?: return null
    return LyricWindow(presentation).also {
        it.playback.update(PlaybackProgressSnapshot(from.id, AutoMixLyricHold.heldFromPositionMs(plan)))
    }
}

internal fun LyricPresentation?.isAutoMixHeldFrom(plan: AutoMixPlan?): Boolean =
    this != null && plan != null && id == plan.from.id && source == plan.from.source
