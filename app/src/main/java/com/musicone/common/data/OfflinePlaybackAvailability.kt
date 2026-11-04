package com.musicone

import android.content.Context
import android.content.SharedPreferences
import com.musicone.sourceintegration.SourceApplicationHost
import com.musicone.sourceintegration.SourcePackageController
import com.musicone.sourceintegration.sourceCacheNamespace
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
    private val supplementalIndex = context.getSharedPreferences("supplemental_audio_index", Context.MODE_PRIVATE)
    private val sessions = context.getSharedPreferences("platform_settings", Context.MODE_PRIVATE)
    private val sessionChanges = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        sessions.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { sessions.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()
    private val namespaces = combine(sessionChanges, SourceApplicationHost.get(context).accountRevision,
        SourcePackageController.get(context).installed) { _, _, _ ->
        MusicSource.entries.map { sourceCacheNamespace(context, it) }
    }.distinctUntilChanged()
    private val changes = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        index.registerOnSharedPreferenceChangeListener(listener)
        supplementalIndex.registerOnSharedPreferenceChangeListener(listener)
        val cache = MusicDiskCache.get(context)
        // 首次快照只由音频通道发出一次，图片及无关会话字段不再触发整库读取。
        val revisions = launch {
            cache.availabilityRevision.collect { trySend(Unit) }
        }
        awaitClose {
            index.unregisterOnSharedPreferenceChangeListener(listener)
            supplementalIndex.unregisterOnSharedPreferenceChangeListener(listener)
            revisions.cancel()
        }
    }.conflate()

    val state = offlineAvailabilityStates(
        musicNetworkChanges(context),
        changes,
        namespaces,
        offline::availableTrackIdsForDisplay,
    ).stateIn(scope, SharingStarted.WhileSubscribed(5_000), OfflinePlaybackAvailabilityState(online = context.hasMusicNetwork()))

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
    network: Flow<Boolean>, changes: Flow<Unit>,
    namespaces: Flow<Any> = flowOf(Unit),
    readTracks: suspend () -> Set<String>,
): Flow<OfflinePlaybackAvailabilityState> = combine(
    network,
    namespaces.distinctUntilChanged().flatMapLatest {
        // 普通变化只保留一次后续；换号取消旧轮并清空旧账号结果。
        changes.conflate().map {
            try { readTracks() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptySet() }
        }.onStart { emit(emptySet()) }
    },
) { online, tracks -> OfflinePlaybackAvailabilityState(online, tracks) }.distinctUntilChanged()
