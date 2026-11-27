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

/** 全局共享一份网络状态和离线可播歌曲集合，后台统一刷新，不在列表里逐行扫磁盘。 */
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
        // 只监听音频缓存变化，下图片或改会话信息不再触发整库重扫。
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

/** 离线索引和网络状态分开流式更新：断网时立刻生效不用等重扫，联网时也能马上隐藏离线灰态。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun offlineAvailabilityStates(
    network: Flow<Boolean>, changes: Flow<Unit>,
    namespaces: Flow<Any> = flowOf(Unit),
    readTracks: suspend () -> Set<String>,
): Flow<OfflinePlaybackAvailabilityState> = combine(
    network,
    namespaces.distinctUntilChanged().flatMapLatest {
        // 缓存频繁变动时合并重扫；一旦换号立刻取消上一轮扫描并清空旧账号数据。
        changes.conflate().map {
            try { readTracks() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { emptySet() }
        }.onStart { emit(emptySet()) }
    },
) { online, tracks -> OfflinePlaybackAvailabilityState(online, tracks) }.distinctUntilChanged()
