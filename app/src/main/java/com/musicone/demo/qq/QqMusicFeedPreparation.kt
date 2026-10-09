package com.musicone.demo

/** 官方首批不等待下方尚需搜索、补封面或核对权益的卡片，这些卡片保留给后续批次。 */
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

/** 展示只需要歌曲身份、封面和三歌曲组的专辑身份；音质和时长继续由点播链确认。 */
internal fun needsQqFeedMetadata(track: MusicTrack, inSongGroup: Boolean): Boolean =
    (inSongGroup && track.albumMid.isBlank() && track.album.isBlank()) ||
        track.artworkUrl.isNullOrBlank() ||
        (track.qqPlaybackMid().isBlank() && !track.canResolveQqFeedTrackOnPlay())

/** 信息流短卡通常只有数字 songId；播放入口复用详情补全和正式取票链路。 */
internal fun MusicTrack.canResolveQqFeedTrackOnPlay(): Boolean =
    source == MusicSource.QQ && title.isNotBlank() && catalogId.toLongOrNull()?.let { it > 0L } == true
