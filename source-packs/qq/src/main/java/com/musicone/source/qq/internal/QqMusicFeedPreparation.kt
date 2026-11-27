package com.musicone.source.qq.internal

/** 首屏优先挑可以直接展示的卡片，需要额外补封面或查权限的留给后续批次。 */
internal fun qqFeedInitialCandidates(cards: List<QqMusicFeedCard>): List<QqMusicFeedCard> {
    if (cards.none { it is QqMusicFeedCard.SongShelf && it.section == QqMusicFeedSection.SONG_RECOMMENDATION }) return cards
    return cards.filterNot { card ->
        when (card) {
            is QqMusicFeedCard.Song -> card.lookupQuery != null || needsQqFeedMetadata(card.track, false)
            is QqMusicFeedCard.SongGroup -> true
            else -> false
        }
    }
}

/** 判断卡片是否缺展示必备字段（歌曲 ID、封面、三连卡的专辑信息）；音质和时长等播放时再查。 */
internal fun needsQqFeedMetadata(track: MusicTrack, inSongGroup: Boolean): Boolean =
    (inSongGroup && track.albumId.isBlank() && track.album.isBlank()) ||
        track.artworkUrl.isNullOrBlank() ||
        (track.qqPlaybackMid().isBlank() && !track.canResolveQqFeedTrackOnPlay())

/** 判断推荐流短卡是否具备播放条件（只要有数字 songId 就能在播放时补全详情并取流）。 */
internal fun MusicTrack.canResolveQqFeedTrackOnPlay(): Boolean =
    source == MusicSource.QQ && title.isNotBlank() && catalogId.toLongOrNull()?.let { it > 0L } == true
