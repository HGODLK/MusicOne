package com.musicone.demo

/** 远端结果只能参与所属查询，输入变化的首帧不能合入上一个查询的歌曲。 */
internal fun artistSearchTracks(local: List<MusicTrack>, remote: List<MusicTrack>,
    remoteQuery: String, query: String): List<MusicTrack> {
    if (query.isBlank()) return local
    val matchingRemote = if (remoteQuery == query.trim()) remote else emptyList()
    return (local.matchingPlaylistQuery(query) + matchingRemote).distinctBy { it.id }
}
