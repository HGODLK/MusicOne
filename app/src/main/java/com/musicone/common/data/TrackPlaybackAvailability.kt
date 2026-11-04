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

/** 只记录平台明确返回的不可播放结果；网络、解码和验证失败不能作为灰名依据。 */
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

/** 所有列表与标题消费同一结果；音源可用时完整缓存优先于在线权限状态。 */
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
