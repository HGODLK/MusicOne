package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect

/** 列表、队列和播放标题共用订阅，登录或权益刷新后立即重新显示角标。 */
@Composable
internal fun qqDisplayedAccessBadge(track: MusicTrack): MusicAccessBadge? {
    if (track.source != MusicSource.QQ) return track.accessBadge
    val state by QqEntitlements.state.collectAsState()
    LaunchedEffect(track.id, track.qqAccess, state.sessionRevision) {
        QqEntitlements.observeTrack(track, state.sessionRevision)
    }
    return QqEntitlements.displayTrack(track, state).accessBadge
}
