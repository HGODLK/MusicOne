package com.musicone.source.kugou

import com.musicone.source.sdk.SourceTrackDetails
import java.text.Collator
import java.util.Locale

/** 首页卡片自行取完整歌单后播放，这里和公共详情页用同一套规则排列，默认保持接口顺序。 */
internal fun sortedKugouCollectionTracks(tracks: List<SourceTrackDetails>, sortId: String?): List<SourceTrackDetails> = when (sortId) {
    "ADDED_ASC" -> tracks.reversed()
    "NAME" -> {
        val collator = Collator.getInstance(Locale.CHINA)
        tracks.sortedWith { a, b -> collator.compare(a.track.title, b.track.title) }
    }
    else -> tracks
}
