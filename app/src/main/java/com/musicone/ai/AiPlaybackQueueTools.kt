package com.musicone

import org.json.JSONObject

/** 播放状态与队列在同一时刻取样，完整曲目沿用现有本机结果表和分页句柄。 */
internal class AiPlaybackQueueTools(
    private val library: AiLibraryTools,
    private val playback: suspend () -> MusicOneUiState?,
) {
    suspend fun read(): JSONObject {
        val snapshot = aiPlaybackQueueSnapshot(requireNotNull(playback()) { "播放器尚未就绪" })
        val result = library.registerResult(snapshot.tracks, "播放队列快照", playback = snapshot.playback)
        return library.readResult(result.getString("handle"))
    }
}

internal data class AiPlaybackQueueSnapshot(val tracks: List<MusicTrack>, val playback: JSONObject)

/** 只输出公开曲目信息；播放地址、本地路径、账号和音源私有参数留在宿主。 */
internal fun aiPlaybackQueueSnapshot(state: MusicOneUiState, capturedAt: Long = System.currentTimeMillis()): AiPlaybackQueueSnapshot {
    val tracks = state.queue.toList()
    val current = state.currentTrack
    val position = if (current == null) -1 else tracks.indexOfFirst { it.id == current.id && it.source == current.source }
    val playback = JSONObject().put("capturedAt", capturedAt).put("isPlaying", state.isPlaying)
        .put("currentPosition", if (position >= 0) position + 1 else JSONObject.NULL)
        .put("currentTrack", current?.let {
            JSONObject().put("id", it.id).put("title", it.title).put("artists", it.artists)
                .put("album", it.album).put("durationMs", it.durationMs)
                .put("sourceId", it.source.id).put("source", it.source.label)
        } ?: JSONObject.NULL)
        .put("shuffle", state.shuffle).put("repeatMode", state.repeatMode.name)
        .put("radioActive", state.radioActive).put("radioLoading", state.radioLoading)
        .put("scope", "当前已加载的播放队列，按实际队列顺序排列；currentPosition从1开始。电台后续未加载歌曲不在此快照内。")
        .put("snapshot", true)
    return AiPlaybackQueueSnapshot(tracks, playback)
}
