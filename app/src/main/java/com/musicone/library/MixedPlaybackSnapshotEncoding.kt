package com.musicone

import org.json.JSONArray
import org.json.JSONObject

/** 缓存序列化后的队列 JSON：只有队列真变了才重编，平时定时存进度和拖动直接复用。 */
internal class MixedPlaybackSnapshotEncoding {
    private data class Content(
        val currentId: String?, val tracks: List<MusicTrack>, val order: List<String>,
        val shuffle: Boolean, val repeat: RepeatMode, val radio: Boolean,
    )
    private var previous: Content? = null

    fun contentIfChanged(state: MusicOneUiState): String? {
        val current = state.currentTrack
        if (current == null && state.queue.isNotEmpty()) return null
        val content = Content(current?.id, state.queue, state.queueOrder.originalIds, state.shuffle,
            state.repeatMode, state.radioActive)
        if (previous == content) return null
        val encoded = JSONObject().put("version", 1).put("current", current?.id ?: JSONObject.NULL)
            .put("shuffle", state.shuffle).put("repeat", state.repeatMode.name).put("radio", state.radioActive)
            .put("order", JSONArray(state.queueOrder.originalIds))
            .put("queue", JSONArray().apply { state.queue.forEach { put(it.toStoredTrackJson()) } }).toString()
        previous = content
        return encoded
    }
}
