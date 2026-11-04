package com.musicone.source.kugou

/** 只读取推荐窗口覆盖的分页；歌单详情仍使用完整分页链路。 */
internal fun collectKugouRecommendationWindow(
    limit: Int, page: Int, load: (PlaylistTrackCursor?) -> PlaylistTrackPage,
): List<MusicTrack> {
    var result = mergePlaylistTrackPage(null, load(null))
    while (result.next != null) {
        val total = result.playlist.count
        if (total > 0) {
            val indices = recommendationWindowIndices(total, limit, page)
            if (indices.all { it < result.playlist.tracks.size }) {
                return indices.map { result.playlist.tracks[it] }
            }
        }
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        result = mergePlaylistTrackPage(result, load(result.next))
    }
    return recommendationWindow(result.playlist.tracks, limit, page)
}
