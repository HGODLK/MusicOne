package com.musicone

import org.json.JSONObject

/** 新旧存储格式共用队列容量、顺序与暂停恢复规则。 */
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
