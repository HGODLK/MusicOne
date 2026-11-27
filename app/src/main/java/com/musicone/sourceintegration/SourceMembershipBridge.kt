package com.musicone.sourceintegration

import android.content.Context
import com.musicone.MusicSource
import com.musicone.source.sdk.SourceEntry
import com.musicone.source.sdk.SourceTrackDetails
import com.musicone.source.sdk.TrackAccessInfo
import com.musicone.source.sdk.currentMembership
import com.musicone.source.sdk.detectMembership

/** 同步读取当前已知的会员状态，不发网络请求也不解析旧文案。 */
internal fun sourceMembershipState(context: Context, source: MusicSource): Boolean? {
    val entry = SourcePackageController.get(context).running(source.id)?.entry ?: return false
    return entry.currentMembership()
}

/** 刷新单曲的播放权益信息（会员状态不能替代单曲本身的版权和购买判断）。 */
internal suspend fun refreshedSourceTrackAccess(entry: SourceEntry, track: SourceTrackDetails): TrackAccessInfo {
    require(entry.descriptor.id == track.track.identity.sourceId) { "歌曲不属于当前音源" }
    entry.detectMembership()
    return entry.mediaPlayback?.availability(listOf(track))?.singleOrNull() ?: track.access
}
