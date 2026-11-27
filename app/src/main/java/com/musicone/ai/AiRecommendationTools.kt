package com.musicone

/** 临时歌单只保存在当前会话里，不建本地歌单，也不改原歌曲信息。 */
internal val MusicPlaylist.isAiTemporaryPlaylist: Boolean
    get() = id.startsWith("ai-temporary:") || id.startsWith("ai-turn-")

internal fun aiTemporaryPlaylist(id: String, title: String, reason: String, tracks: List<MusicTrack>): MusicPlaylist {
    val first = tracks.firstOrNull()
    return MusicPlaylist(id, MusicSource.LOCAL, title, "AI 临时歌单 · ${tracks.size} 首", reason, tracks.size,
        first?.artworkStart ?: 0L, first?.artworkEnd ?: 0L, first?.artworkMark ?: "♪", tracks,
        artworkUrl = first?.artworkUrl)
}

/** 卡片展示和实际播放共用同一份可播检查结果，不靠试播来探测。 */
internal fun aiTracksWithAvailability(tracks: List<MusicTrack>, validation: AiPlaybackValidation): List<MusicTrack> {
    val available = validation.playableTracks.associateBy(MusicTrack::id)
    val blocked = (validation.skipped + validation.unknown).associateBy { it.optString("id") }
    return tracks.map { track ->
        blocked[track.id]?.let { state -> track.copy(playable = false,
            unavailableReason = state.optString("reason").ifBlank { "暂时无法确认播放权限" }) } ?: available[track.id] ?: track
    }
}
