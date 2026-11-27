package com.musicone.source.kugou

import com.musicone.source.sdk.SourceTrackDetails
import java.text.Collator
import java.util.Locale

/** 首页卡片直接播放歌单时，按和详情页一致的规则排序（默认保持接口顺序）。 */
internal fun sortedKugouCollectionTracks(tracks: List<SourceTrackDetails>, sortId: String?): List<SourceTrackDetails> = when (sortId) {
    "ADDED_ASC" -> tracks.reversed()
    "NAME" -> {
        val collator = Collator.getInstance(Locale.CHINA)
        tracks.sortedWith { a, b -> collator.compare(a.track.title, b.track.title) }
    }
    else -> tracks
}
