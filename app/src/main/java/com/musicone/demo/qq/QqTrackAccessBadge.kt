package com.musicone.demo

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** 列表、队列和播放标题共用订阅，登录或权益刷新后立即重新显示角标。 */
@Composable
internal fun qqDisplayedAccessBadge(track: MusicTrack): MusicAccessBadge? {
    val context = LocalContext.current
    val offline by remember(context) { OfflinePlaybackAvailability.get(context) }.state.collectAsStateWithLifecycle()
    if (!offline.online) return null
    if (track.source != MusicSource.QQ) return track.accessBadge
    val state by QqEntitlements.state.collectAsStateWithLifecycle()
    LaunchedEffect(track.id, track.qqAccess, state.sessionRevision) {
        QqEntitlements.observeTrack(track, state.sessionRevision)
    }
    return QqEntitlements.displayTrack(track, state).accessBadge
}
