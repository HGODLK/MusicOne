package com.musicone

import com.musicone.source.sdk.SourceTrackBadge
import com.musicone.source.sdk.TrackAccessBadge
import com.musicone.source.sdk.TrackAccessInfo
import com.musicone.source.sdk.SourceEntry
import com.musicone.source.sdk.currentMembership
import com.musicone.source.sdk.membershipDetectorOrNull

/** 通用会员筛选将 false 折叠为未知；角标需要保留酷狗检测器明确确认的非会员状态。 */
internal fun sourceAccessMembership(entry: SourceEntry?, source: MusicSource): Boolean? =
    if (source == MusicSource.KUGOU) entry?.membershipDetectorOrNull()?.currentMembership else entry?.currentMembership()

/** 酷狗先确认没有音乐会员才展示会员提示；未知状态不沿用旧快照里的 VIP 字样。 */
internal fun mainSourceAccessPresentation(source: MusicSource, access: TrackAccessInfo, membership: Boolean?): SourceTrackBadge? {
    if (source == MusicSource.KUGOU && access.badge == TrackAccessBadge.MEMBERSHIP && membership != false) return null
    return resolvedSongAccessBadge(access)
}
