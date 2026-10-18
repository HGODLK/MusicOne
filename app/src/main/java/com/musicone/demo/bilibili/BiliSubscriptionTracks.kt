package com.musicone.demo

/** 收藏列表只创建待解析条目；已导入分 P 的身份与资料继续保留。 */
internal fun biliSubscriptionTracks(video: BiliVideoItem, previous: List<MusicTrack>): List<MusicTrack> {
    val tracks = previous.filter { it.source == MusicSource.BILIBILI &&
        (if (video.bvid.isNotBlank()) it.bilibiliBvid == video.bvid else it.catalogId == video.aid.toString()) }
    if (tracks.isEmpty()) return listOf(BiliTrackMapper.toFallbackTrack(video))
    return tracks.map {
        if (video.isInvalid || video.bvid.isBlank()) it.copy(playable = false, unavailableReason = "B站资源已失效")
        else it.copy(playable = true, unavailableReason = null)
    }
}
