package com.musicone

/** 临时歌单只存会话引用，不创建音乐库歌单或改写原歌曲资料。 */
internal fun aiTemporaryPlaylist(id: String, title: String, reason: String, tracks: List<MusicTrack>): MusicPlaylist {
    val first = tracks.firstOrNull()
    return MusicPlaylist(id, MusicSource.LOCAL, title, "AI 临时歌单 · ${tracks.size} 首", reason, tracks.size,
        first?.artworkStart ?: 0L, first?.artworkEnd ?: 0L, first?.artworkMark ?: "♪", tracks,
        artworkUrl = first?.artworkUrl)
}

/** 卡片和实际播放使用同一份预检状态，不通过试播探测歌曲。 */
internal fun aiTracksWithAvailability(tracks: List<MusicTrack>, validation: AiPlaybackValidation): List<MusicTrack> {
    val available = validation.playableTracks.associateBy(MusicTrack::id)
    val blocked = (validation.skipped + validation.unknown).associateBy { it.optString("id") }
    return tracks.map { track ->
        blocked[track.id]?.let { state -> track.copy(playable = false,
            unavailableReason = state.optString("reason").ifBlank { "暂时无法确认播放权限" }) } ?: available[track.id] ?: track
    }
}
