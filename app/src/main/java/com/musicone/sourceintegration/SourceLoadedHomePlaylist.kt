package com.musicone.sourceintegration

import com.musicone.MusicPlaylist
import com.musicone.source.sdk.SourceCollection
import com.musicone.source.sdk.SourceTrackDetails

/** 公共首页卡片接收完整已加载歌曲，公开的空账号身份保持原样。 */
internal fun loadedHomePlaylist(
    collection: SourceCollection,
    tracks: List<SourceTrackDetails>,
    sourceId: String,
    accountId: String?,
): MusicPlaylist {
    require(collection.identity.sourceId == sourceId &&
        (collection.identity.accountId == null || collection.identity.accountId == accountId)) {
        "首页歌单属于其他音源或账号"
    }
    require(tracks.all { it.track.identity.sourceId == sourceId &&
        (it.track.identity.accountId == null || it.track.identity.accountId == accountId) }) {
        "首页歌曲属于其他音源或账号"
    }
    return collection.toMusicPlaylist(tracks.map { it.toMusicTrack() })
}
