package com.musicone

import com.musicone.source.sdk.SourceContent

/** 收藏内容与歌曲行共用同一套排序，播放和批量操作才能与列表顺序一致。 */
internal fun sortedSupplementalContents(contents: List<SourceContent>, sort: PlaylistSort): List<SourceContent> {
    if (sort == PlaylistSort.ADDED_DESC) return contents
    val byTrack = contents.associateBy { it.asSupplementalTrack().id }
    return sortedPlaylistTracks(contents.map { it.asSupplementalTrack() }, sort).mapNotNull { byTrack[it.id] }
}
