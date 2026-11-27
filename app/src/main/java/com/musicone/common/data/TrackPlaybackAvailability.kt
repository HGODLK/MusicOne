package com.musicone

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation

/** 只有平台明确确认不能播时才抛这个异常并置灰；网络波动或解码失败不能把歌标灰。 */
internal class TrackUnavailableException(message: String) : PlatformApiException(message)

internal class TrackPlaybackAvailability private constructor(private val context: Context) {
    private val verifier = TrackAvailabilityVerifier(context)
    private val store = TrackAvailabilityStore(CoroutineScope(SupervisorJob() + Dispatchers.IO), verifier::verify)
    val state = store.state
    private val accounts = mutableMapOf<String, String>()
    @Synchronized fun revisionFor(track: MusicTrack): Long {
        val account = com.musicone.sourceintegration.sourceAccountState(context, track.source)
        val identity = "${account?.status}:${account?.account?.id}:${account?.revision}"
        val old = accounts.put(track.source.id, identity)
        if (old != null && old != identity) store.reset()
        return state.value.revision
    }

    fun report(track: MusicTrack, revision: Long, reason: String?) = store.report(track, revision, reason)
    suspend fun refresh(track: MusicTrack) { revisionFor(track); store.refresh(track) }

    companion object {
        @Volatile private var instance: TrackPlaybackAvailability? = null
        fun get(context: Context): TrackPlaybackAvailability = instance ?: synchronized(this) {
            instance ?: TrackPlaybackAvailability(context.applicationContext).also { instance = it }
        }
    }
}

/** 统一给列表和标题栏提供歌曲可播状态，音源正常时有完整离线缓存就优先显示可播。 */
@Composable
internal fun displayedPlaybackTrack(track: MusicTrack): MusicTrack {
    if (track.source == MusicSource.LOCAL) return track
    val context = LocalContext.current
    val sourceStatus = rememberQueueSourceStatus(track.source)
    val availability = remember(context) { TrackPlaybackAvailability.get(context) }
    val failures by availability.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestTrack by rememberUpdatedState(track)
    LaunchedEffect(availability, track.id, failures.revision, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            availability.refresh(latestTrack)
            awaitCancellation()
        }
    }
    val offline by remember(context) { OfflinePlaybackAvailability.get(context) }.state.collectAsStateWithLifecycle()
    return displayedTrackAvailability(track, failures.unavailable[track.id], track.id in offline.trackIds,
        offline.online, sourceStatus)
}
