package com.musicone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/** 只加载可见收藏夹的首屏，完整内容仍由打开、播放和缓存操作获取。 */
internal class CachePlaylistPreviews(
    private val directory: CachePlaylistDirectory,
    private val offline: OfflineMusicStore,
    private val mutable: MutableStateFlow<MusicCacheState>,
    private val owners: () -> List<Pair<String, String>>,
    private val onChanged: () -> Unit,
) {
    private val previewSlots = Semaphore(4)
    // minSdk 23 没有 ConcurrentHashMap.newKeySet（API 24），用等价的并发集合。
    private val previewed: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    fun clear() = previewed.clear()

    /** 随可见卡片请求预览；离屏取消等待，不把所有收藏夹排进网络队列。 */
    suspend fun load(playlist: MusicPlaylist) = withContext(Dispatchers.IO) {
        val requestedOwners = owners()
        val revision = mutable.value.directoryRevision
        val playlistKey = cachePlaylistKey(playlist)
        val key = "$revision:$playlistKey"
        if (key in previewed || !offline.hasNetwork()) return@withContext
        previewSlots.withPermit {
            if (!previewed.add(key)) return@withPermit
            try {
                val detail = directory.preview(playlist) ?: return@withPermit
                ensureActive()
                if (owners() == requestedOwners && mutable.value.directoryRevision == revision && directory.signature() == requestedOwners) {
                    mutable.update { state -> if (state.directoryRevision != revision || owners() != requestedOwners) state
                        else state.copy(playlists = state.playlists.map { current ->
                        if (cachePlaylistKey(current) != playlistKey) current else detail.copy(
                            tracks = if (current.tracks.size > detail.tracks.size) current.tracks else detail.tracks)
                    }) }
                    onChanged()
                }
            } catch (cancelled: CancellationException) { previewed.remove(key); throw cancelled }
            catch (_: Exception) { previewed.remove(key) }
        }
    }

}
