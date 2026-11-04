package com.musicone.sourceintegration

import android.content.Context
import com.musicone.MusicSource
import com.musicone.source.sdk.SourceEntry
import com.musicone.source.sdk.SourceTrackDetails
import com.musicone.source.sdk.TrackAccessInfo
import com.musicone.source.sdk.currentMembership
import com.musicone.source.sdk.detectMembership

/** 不读取旧会员文案，也不在同步读取或音质缓存查找时发起检测。 */
internal fun sourceMembershipState(context: Context, source: MusicSource): Boolean? {
    val entry = SourcePackageController.get(context).running(source.id)?.entry ?: return false
    return entry.currentMembership()
}

/** 主音源与补充歌单按曲目原音源复用此入口，会员结果不替代单曲版权和购买判断。 */
internal suspend fun refreshedSourceTrackAccess(entry: SourceEntry, track: SourceTrackDetails): TrackAccessInfo {
    require(entry.descriptor.id == track.track.identity.sourceId) { "歌曲不属于当前音源" }
    entry.detectMembership()
    return entry.mediaPlayback?.availability(listOf(track))?.singleOrNull() ?: track.access
}
