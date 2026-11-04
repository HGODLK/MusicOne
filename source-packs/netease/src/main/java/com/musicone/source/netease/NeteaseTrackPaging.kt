package com.musicone.source.netease

internal const val PLAYLIST_TRACK_PAGE_SIZE = 100

internal data class NeteaseTrackCursor(val offset: Int, val trackIds: List<String>? = null)

internal data class NeteaseTrackPage(val playlist: NeteasePlaylist, val next: NeteaseTrackCursor? = null)

/** 页大小只限制单次请求；下一页按服务端原始条数推进，不能按去重后的歌曲数推进。 */
internal fun nextPlaylistTrackCursor(
    offset: Int,
    received: Int,
    total: Int,
    hasMore: Boolean? = null,
): NeteaseTrackCursor? {
    require(offset >= 0 && received >= 0)
    val end = offset + received
    val more = hasMore ?: if (total > 0) end < total else received >= PLAYLIST_TRACK_PAGE_SIZE
    if (received == 0) {
        check(!more) { "歌曲分页未返回后续内容，请重试" }
        return null
    }
    return if (more) NeteaseTrackCursor(end) else null
}

internal fun mergePlaylistTrackPage(previous: NeteaseTrackPage?, incoming: NeteaseTrackPage): NeteaseTrackPage {
    if (previous == null) return incoming.copy(playlist = incoming.playlist.copy(
        tracks = incoming.playlist.tracks.distinctBy(NeteaseTrack::id)))
    val before = previous.playlist.tracks
    val tracks = (before + incoming.playlist.tracks).distinctBy(NeteaseTrack::id)
    check(incoming.next == null || incoming.next.offset > (previous.next?.offset ?: -1)) {
        "歌曲分页位置没有前进，请重试"
    }
    check(incoming.playlist.tracks.isEmpty() || tracks.size > before.size) {
        "歌曲分页返回了重复内容，请重试"
    }
    return incoming.copy(playlist = previous.playlist.copy(count = incoming.playlist.count, tracks = tracks))
}

internal inline fun collectPlaylistTrackPages(load: (NeteaseTrackCursor?) -> NeteaseTrackPage): NeteasePlaylist {
    var result: NeteaseTrackPage? = null
    do {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        result = mergePlaylistTrackPage(result, load(result?.next))
    } while (result.next != null)
    return requireNotNull(result).playlist
}
