package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal data class BottomPlayerPresentation(
    val page: MusicOnePage,
    val currentTrack: MusicTrack?,
    val isPlaying: Boolean,
    val hasQueue: Boolean,
)

private fun MusicOneUiState.bottomPlayerPresentation(pending: MusicTrack?) =
    BottomPlayerPresentation(page, currentTrack?.let { playerVisualTrack(it, pending) }, isPlaying, queue.isNotEmpty())

/** 迷你栏与播放页显示同一首可见歌曲，切歌预览期间收起播放页时封面不会退回已提交的旧歌。 */
internal fun bottomPlayerPresentations(
    states: StateFlow<MusicOneUiState>,
    preview: StateFlow<RapidTrackSwitchPresentation?> = MutableStateFlow(null),
): Flow<BottomPlayerPresentation> =
    combine(states, preview.map { it?.track }.distinctUntilChanged()) { _, _ ->
        // 与播放页一致：直接读取已提交状态，撤下预览时不接回滞后一帧的通知。
        states.value.bottomPlayerPresentation(preview.value?.track)
    }.distinctUntilChanged { previous, current ->
        previous.page == current.page && previous.isPlaying == current.isPlaying &&
            previous.hasQueue == current.hasQueue &&
            previous.currentTrack?.playerVisualKey() == current.currentTrack?.playerVisualKey()
    }

@Composable
internal fun rememberBottomPlayerPresentation(
    states: StateFlow<MusicOneUiState>,
    preview: StateFlow<RapidTrackSwitchPresentation?>,
): State<BottomPlayerPresentation> =
    remember(states, preview) { bottomPlayerPresentations(states, preview) }.collectAsStateWithLifecycleFrom(states) {
        it.bottomPlayerPresentation(preview.value?.track)
    }
