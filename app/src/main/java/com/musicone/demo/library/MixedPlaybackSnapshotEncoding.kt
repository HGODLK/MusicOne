package com.musicone.demo

import org.json.JSONArray
import org.json.JSONObject

/** 长队列仅在内容变化时编码，五秒进度保存和连续 seek 复用已有内容。 */
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
            state.repeatMode, state.qqRadioActive)
        if (previous == content) return null
        val encoded = JSONObject().put("version", 1).put("current", current?.id ?: JSONObject.NULL)
            .put("shuffle", state.shuffle).put("repeat", state.repeatMode.name).put("radio", state.qqRadioActive)
            .put("order", JSONArray(state.queueOrder.originalIds))
            .put("queue", JSONArray().apply { state.queue.forEach { put(it.toQqStoredTrackJson()) } }).toString()
        previous = content
        return encoded
    }
}
