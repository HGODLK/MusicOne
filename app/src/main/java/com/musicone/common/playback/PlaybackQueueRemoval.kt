package com.musicone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal data class PlaybackQueueRemovalPlan(
    val state: MusicOneUiState,
    val removedCurrent: Boolean,
    val nextTrack: MusicTrack?,
)

/** 仅移除播放队列项，不操作歌单、收藏或本地文件。 */
internal fun playbackQueueRemovalPlan(state: MusicOneUiState, selected: MusicTrack): PlaybackQueueRemovalPlan? =
    playbackQueueRemovalPlan(state, listOf(selected))

/**
 * 一次算出批量移除后的队列：只更新一次状态、保存一次。
 * 当前歌曲被移除时切到它原位置之后第一首保留的歌，后面没有就回到第一首。
 */
internal fun playbackQueueRemovalPlan(state: MusicOneUiState, selected: List<MusicTrack>): PlaybackQueueRemovalPlan? {
    fun MusicTrack.key() = source to id
    val keys = selected.mapTo(hashSetOf()) { it.key() }
    if (state.queue.none { it.key() in keys }) return null
    val remaining = state.queue.filterNot { it.key() in keys }
    val removedCurrent = state.currentTrack?.let { it.key() in keys } == true
    if (remaining.isEmpty()) return PlaybackQueueRemovalPlan(state.copy(
        currentTrack = null, queue = emptyList(), queueOrder = PlaybackQueueOrder(),
        isPlaying = false, playerExpanded = false, activeQuality = null,
        availableQualities = emptyList(), qualityLoading = false, qualityChanging = false,
        qualityTarget = null, qualityError = null, lyricLoadState = LyricLoadState.UNAVAILABLE,
        radioActive = false, radioLoading = false, playbackMessage = null,
    ), removedCurrent, null)
    val removedIds = selected.mapTo(hashSetOf()) { it.id }
    val order = state.queueOrder.reconcile(remaining).let {
        if (it.nextAfterId in removedIds || it.nextTrackId in removedIds)
            it.copy(nextAfterId = null, nextTrackId = null) else it
    }
    val next = if (!removedCurrent) null else {
        val currentIndex = state.queue.indexOfFirst { it.key() == state.currentTrack?.key() }
        state.queue.drop(currentIndex + 1).firstOrNull { it.key() !in keys } ?: remaining.first()
    }
    return PlaybackQueueRemovalPlan(state.copy(queue = remaining, queueOrder = order), removedCurrent, next)
}

/** 队列规则与播放器生命周期分离，切曲及清空交给已有会话执行。 */
internal class PlaybackQueueRemoval(
    private val states: MutableStateFlow<MusicOneUiState>,
    private val startNext: (MusicTrack, MusicOneUiState) -> Unit,
    private val clearPlayback: () -> Unit,
    private val save: () -> Unit,
) {
    fun remove(track: MusicTrack) = removeAll(listOf(track))

    /** 批量移除一次完成：当前歌曲也在其中时直接切到后面第一首保留的歌，全部移除则清空播放。 */
    fun removeAll(tracks: List<MusicTrack>) {
        val plan = playbackQueueRemovalPlan(states.value, tracks) ?: return
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
}
