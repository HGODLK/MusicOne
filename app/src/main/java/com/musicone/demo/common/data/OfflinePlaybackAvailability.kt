package com.musicone.demo

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

internal data class OfflinePlaybackAvailabilityState(
    val online: Boolean = true,
    val trackIds: Set<String> = emptySet(),
) {
    fun showsBadge(trackId: String): Boolean = !online && trackId in trackIds
}

/** 所有标题共享网络与缓存索引，只在后台刷新一次，不在歌曲行内逐个扫描磁盘。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class OfflinePlaybackAvailability private constructor(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val offline = OfflineMusicStore(context)
    private val index = context.getSharedPreferences("offline_music_index", Context.MODE_PRIVATE)
    private val sessions = context.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)
    private val changes = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        index.registerOnSharedPreferenceChangeListener(listener)
        sessions.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose {
            index.unregisterOnSharedPreferenceChangeListener(listener)
            sessions.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.conflate()

    val state = offlineAvailabilityStates(
        musicNetworkChanges(context),
        merge(changes, MusicDiskCache.get(context).audioRevision.map { Unit }),
        offline::availableTrackIds,
    ).stateIn(scope, SharingStarted.WhileSubscribed(5_000), OfflinePlaybackAvailabilityState())

    companion object {
        @Volatile private var instance: OfflinePlaybackAvailability? = null
        fun get(context: Context): OfflinePlaybackAvailability = instance ?: synchronized(this) {
            instance ?: OfflinePlaybackAvailability(context.applicationContext).also { instance = it }
        }
    }
}

/** 索引与网络独立更新；断网无需等待整批媒体重新校验，联网也能立即收起角标。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun offlineAvailabilityStates(
    network: Flow<Boolean>, changes: Flow<Unit>, readTracks: suspend () -> Set<String>,
): Flow<OfflinePlaybackAvailabilityState> = combine(
    network,
    changes.conflate().mapLatest {
        try { readTracks() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptySet() }
    }.onStart { emit(emptySet()) },
) { online, tracks -> OfflinePlaybackAvailabilityState(online, tracks) }.distinctUntilChanged()
