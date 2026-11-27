package com.musicone

import org.json.JSONObject

/** 恢复持久化播放快照的公共逻辑（兼容新旧格式，统一处理队列上限、顺序和暂停状态）。 */
internal fun restorePlaybackSnapshot(queue: List<MusicTrack>, currentId: String?, order: List<String>,
    shuffle: Boolean = false, repeat: RepeatMode = RepeatMode.ALL, radio: Boolean = false,
    initial: MusicOneUiState = MusicOneUiState()): MusicOneUiState? {
    if (queue.isEmpty()) return null
    val bounded = limitPlaybackQueue(queue, queue.firstOrNull { it.id == currentId })
    val current = bounded.firstOrNull { it.id == currentId } ?: bounded.first()
    return initial.copy(currentTrack = current, queue = bounded,
        queueOrder = PlaybackQueueOrder(order).reconcile(bounded), isPlaying = false,
        shuffle = shuffle, repeatMode = repeat,
        radioActive = radio && bounded.all { it.source == current.source }, lyricLoadState = LyricLoadState.LOADING)
}

internal fun JSONObject.toMixedPlaybackState(initial: MusicOneUiState = MusicOneUiState()): MusicOneUiState? {
    if (optInt("version") != 1) return null
    val queue = optJSONArray("queue")?.let { values ->
        (0 until values.length()).mapNotNull { values.optJSONObject(it)?.toStoredTrack() }
    }.orEmpty()
    val order = optJSONArray("order")?.let { values -> (0 until values.length()).map { values.optString(it) } }.orEmpty()
    return restorePlaybackSnapshot(queue, optString("current"), order, optBoolean("shuffle"),
        RepeatMode.entries.firstOrNull { it.name == optString("repeat") } ?: RepeatMode.ALL,
        optBoolean("radio"), initial)
}
