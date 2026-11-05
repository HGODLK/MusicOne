package com.musicone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal data class PlaybackQueueRemovalPlan(
    val state: MusicOneUiState,
    val removedCurrent: Boolean,
    val nextTrack: MusicTrack?,
)

/** 仅移除播放队列项，不操作歌单、收藏或本地文件。 */
internal fun playbackQueueRemovalPlan(state: MusicOneUiState, selected: MusicTrack): PlaybackQueueRemovalPlan? {
    fun MusicTrack.matches(other: MusicTrack) = source == other.source && id == other.id
    val index = state.queue.indexOfFirst { it.matches(selected) }
    if (index < 0) return null
    val remaining = state.queue.filterNot { it.matches(selected) }
    val removedCurrent = state.currentTrack?.matches(selected) == true
    if (remaining.isEmpty()) return PlaybackQueueRemovalPlan(state.copy(
        currentTrack = null, queue = emptyList(), queueOrder = PlaybackQueueOrder(),
        isPlaying = false, playerExpanded = false, activeQuality = null,
        availableQualities = emptyList(), qualityLoading = false, qualityChanging = false,
        qualityTarget = null, qualityError = null, lyricLoadState = LyricLoadState.UNAVAILABLE,
        radioActive = false, radioLoading = false, playbackMessage = null,
    ), removedCurrent, null)
    val order = state.queueOrder.reconcile(remaining).let {
        if (selected.id == it.nextAfterId || selected.id == it.nextTrackId)
            it.copy(nextAfterId = null, nextTrackId = null) else it
    }
    return PlaybackQueueRemovalPlan(state.copy(queue = remaining, queueOrder = order), removedCurrent,
        if (removedCurrent) remaining.getOrNull(index) ?: remaining.first() else null)
}

/** 队列规则与播放器生命周期分离，切曲及清空交给已有会话执行。 */
internal class PlaybackQueueRemoval(
    private val states: MutableStateFlow<MusicOneUiState>,
    private val startNext: (MusicTrack, MusicOneUiState) -> Unit,
    private val clearPlayback: () -> Unit,
    private val save: () -> Unit,
) {
    fun remove(track: MusicTrack) {
        val plan = playbackQueueRemovalPlan(states.value, track) ?: return
        when {
            plan.state.queue.isEmpty() -> {
                states.value = plan.state
                clearPlayback()
            }
            plan.removedCurrent -> {
                states.update { it.copy(queueOrder = plan.state.queueOrder) }
                startNext(requireNotNull(plan.nextTrack), plan.state)
            }
            else -> { states.value = plan.state; save() }
        }
    }

    /** 批量移除：先移除非当前项，最后处理当前歌曲，避免切到同样要被移除的下一首。 */
    fun removeAll(tracks: List<MusicTrack>) {
        val current = states.value.currentTrack
        val (playing, others) = tracks.partition { current != null && it.source == current.source && it.id == current.id }
        others.forEach(::remove)
        playing.firstOrNull()?.let(::remove)
    }
}
