package com.musicone.demo

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

internal data class AppPlaybackNavigation(val page: MusicOnePage, val playerExpanded: Boolean)

internal fun appPlaybackNavigation(states: Flow<MusicOneUiState>): Flow<AppPlaybackNavigation> =
    states.map { AppPlaybackNavigation(it.page, it.playerExpanded) }.distinctUntilChanged()

internal fun visiblePagePlaybackStates(
    states: Flow<MusicOneUiState>,
    visible: Flow<Boolean>,
): Flow<MusicOneUiState> = combine(states, visible) { state, shown ->
    state.takeIf { shown }
}.filterNotNull().distinctUntilChanged()

@Composable
internal fun rememberAppPlaybackNavigation(states: StateFlow<MusicOneUiState>): State<AppPlaybackNavigation> =
    remember(states) { appPlaybackNavigation(states) }.collectAsStateWithLifecycleFrom(states) {
        AppPlaybackNavigation(it.page, it.playerExpanded)
    }

@Composable
internal fun rememberPagePlaybackState(states: StateFlow<MusicOneUiState>, player: PageMotion): State<MusicOneUiState> =
    remember(states, player) {
        // 完全被播放页遮挡时保留底页快照，拖动或收起开始即接回最新值，不补放中间歌曲。
        visiblePagePlaybackStates(states, snapshotFlow { player.phase != MotionPhase.SHOWN })
    }.collectAsStateWithLifecycleFrom(states) { it }

@SuppressLint("StateFlowValueCalledInComposition")
@Composable
internal fun <S, T> Flow<T>.collectAsStateWithLifecycleFrom(
    source: StateFlow<S>,
    initialValue: (S) -> T,
): State<T> {
    // value 只提供同步首帧；后续变化仍由同一来源派生的 Flow 持续收集，避免恢复时闪回占位状态。
    return collectAsStateWithLifecycle(initialValue = initialValue(source.value))
}
