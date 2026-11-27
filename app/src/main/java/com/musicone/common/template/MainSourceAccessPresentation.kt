package com.musicone

import com.musicone.source.sdk.SourceTrackBadge
import com.musicone.source.sdk.TrackAccessBadge
import com.musicone.source.sdk.TrackAccessInfo
import com.musicone.source.sdk.SourceEntry
import com.musicone.source.sdk.currentMembership
import com.musicone.source.sdk.membershipDetectorOrNull

/** 读取当前会员状态：酷狗需要明确区分“已确认非会员(false)”和“未知(null)”，所以单独直读检测器。 */
internal fun sourceAccessMembership(entry: SourceEntry?, source: MusicSource): Boolean? =
    if (source == MusicSource.KUGOU) entry?.membershipDetectorOrNull()?.currentMembership else entry?.currentMembership()

/**
 * 酷狗先确认没有音乐会员才展示会员提示；未知状态不沿用旧快照里的 VIP 字样。
 * assumeMember：本次还没在线核对、但上次核对是会员，先不显示会员提示，避免冷启动先闪再消失。
 */
internal fun mainSourceAccessPresentation(source: MusicSource, access: TrackAccessInfo, membership: Boolean?,
    assumeMember: Boolean = false): SourceTrackBadge? {
    if (source == MusicSource.KUGOU && access.badge == TrackAccessBadge.MEMBERSHIP && membership != false) return null
    if (assumeMember && access.badge == TrackAccessBadge.MEMBERSHIP) return null
    return resolvedSongAccessBadge(access)
}
