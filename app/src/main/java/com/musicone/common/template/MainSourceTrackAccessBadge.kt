package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musicone.source.sdk.SourceTrackBadge
import com.musicone.source.sdk.TrackAccessBadge
import com.musicone.source.sdk.TrackAccessInfo
import com.musicone.sourceintegration.*
import kotlinx.coroutines.CancellationException

/** 展示资料可离线读取；在线权益刷新失败时保留上次已知声明。 */
@Composable
internal fun mainSourceDisplayedAccessBadge(track: MusicTrack): SourceTrackBadge? {
    val context = LocalContext.current
    val offline by remember(context) { OfflinePlaybackAvailability.get(context) }.state.collectAsStateWithLifecycle()
    val entry = SourcePackageController.get(context).running(track.source.id)?.entry
    val revision = entry?.authentication?.currentAccount()?.revision
    var membership by remember(entry, revision) { mutableStateOf(sourceAccessMembership(entry, track.source)) }
    var access by remember(entry, revision, track.source.id, track.id, track.accessBadge, track.trialAvailable, track.displayBadge) {
        mutableStateOf(TrackAccessInfo(when (track.accessBadge) {
            MusicAccessBadge.VIP -> TrackAccessBadge.MEMBERSHIP
            MusicAccessBadge.PAID -> TrackAccessBadge.PURCHASE
            null -> null
        }, track.trialAvailable, track.playable, track.unavailableReason, track.displayBadge))
    }
    LaunchedEffect(track, revision, offline.online, entry) {
        membership = sourceAccessMembership(entry, track.source)
        if (!offline.online) return@LaunchedEffect
        try {
            entry?.let { refreshedSourceTrackAccess(it, track.toSourceTrack()) }?.let {
                access = it
                membership = sourceAccessMembership(entry, track.source)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 网络失败保留已知资料，不推断新的会员或购买权益。 */ }
    }
    return mainSourceAccessPresentation(track.source, access, membership)
}
