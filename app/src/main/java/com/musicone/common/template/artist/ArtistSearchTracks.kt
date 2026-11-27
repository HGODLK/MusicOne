package com.musicone

/** 只有远端结果对应当前关键词时才合并进去，输入刚改的那一帧不要把上次搜索结果混进来。 */
internal fun artistSearchTracks(local: List<MusicTrack>, remote: List<MusicTrack>,
    remoteQuery: String, query: String): List<MusicTrack> {
    if (query.isBlank()) return local
    val matchingRemote = if (remoteQuery == query.trim()) remote else emptyList()
    return (local.matchingPlaylistQuery(query) + matchingRemote).distinctBy { it.id }
}
