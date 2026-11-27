package com.musicone.source.kugou

/** 只拉取首页推荐展示需要的页数，不拉整张歌单。 */
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
