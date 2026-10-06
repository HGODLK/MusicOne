package com.musicone.demo

import android.content.Context
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class MusicCacheState(
    val playlists: List<MusicPlaylist> = emptyList(),
    val statuses: Map<String, String> = emptyMap(),
    val busyPlaylist: String? = null,
    val message: String? = null,
    val loading: Boolean = false,
)

/** 缓存工作独立于设置页面，退出页面后本次队列继续执行。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicCacheManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val snapshots = CachedPlaylistStore(context)
    private val offline = OfflineMusicStore(context)
    private val catalog = PlatformCatalogRepository(context)
    private val mutable = MutableStateFlow(MusicCacheState())
    val state = mutable.asStateFlow()
    private var namespace = ""
    private var batch: Job? = null
    private var loadJob: Job? = null
    private val operationMutex = Mutex()
    @Volatile private var writer: CacheWriter? = null

    fun configure(source: MusicSource, supplied: List<MusicPlaylist>) {
        val session = PlatformPreferences(context).readSession(source)
        val nextNamespace = session.cacheNamespace()
        if (namespace != nextNamespace) {
            cancel()
            loadJob?.cancel()
            namespace = nextNamespace
            mutable.value = MusicCacheState()
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val saved = snapshots.read(nextNamespace)
            val incoming = supplied.filter { it.source == source }
            val localTracks = (offline.savedTracks(source) +
                if (source == MusicSource.QQ) QqPlaybackSnapshotStore(context).read()?.queue.orEmpty() else emptyList())
                .distinctBy { it.id }.filter { offline.keys(it).isNotEmpty() }
            val localPlaylist = localTracks.takeIf { it.isNotEmpty() }?.let {
                MusicPlaylist("local-cached-${source.name}", source, "已缓存歌曲", "本机音频", "", it.size,
                    0, 0, "缓", it)
            }
            val merged = (incoming + listOfNotNull(localPlaylist) + saved).distinctBy { it.id }.map { playlist ->
                val stored = saved.firstOrNull { it.id == playlist.id }
                if (playlist.tracks.isEmpty() && stored != null) playlist.copy(tracks = stored.tracks) else playlist
            }.sortedBy { if (it.id == QQ_FAVORITES_PLAYLIST_ID || it.title.contains("我喜欢")) 0 else 1 }
            ensureActive()
            if (namespace != nextNamespace) return@launch
            mutable.update { it.copy(playlists = merged) }
            snapshots.save(nextNamespace, merged)
            refreshStatuses()
        }
    }

    fun open(playlist: MusicPlaylist) {
        if (playlist.tracks.size >= playlist.count && playlist.tracks.isNotEmpty() || !offline.hasNetwork()) {
            scope.launch { refreshStatuses() }; return
        }
        val requestedNamespace = namespace
        scope.launch {
            mutable.update { it.copy(loading = true, message = null) }
            try {
                val detail = loadPlaylist(playlist)
                if (namespace == requestedNamespace) updatePlaylist(detail)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (namespace == requestedNamespace) mutable.update { it.copy(message = error.asUserMessage()) } }
            finally { if (namespace == requestedNamespace) mutable.update { it.copy(loading = false) } }
            refreshStatuses()
        }
    }

    fun cache(playlist: MusicPlaylist) {
        if (batch?.isCompleted == false) return
        val requestedNamespace = namespace
        mutable.update { it.copy(busyPlaylist = playlist.id, message = null) }
        batch = scope.launch {
            operationMutex.withLock {
            try {
                if (!offline.hasNetwork()) throw PlatformApiException("请联网后缓存歌曲")
                val detail = if (playlist.tracks.isEmpty() || playlist.tracks.size < playlist.count) loadPlaylist(playlist) else playlist
                ensureActive()
                if (namespace != requestedNamespace) return@launch
                updatePlaylist(detail)
                val resolver = PlatformPlaybackResolver(context, qqCacheRequests = true)
                val preferred = PlaybackQualityPreferences(context).read(detail.source)
                var failed = 0
                for (track in detail.tracks.distinctBy { it.id }) {
                    ensureActive()
                    setStatus(track, "等待缓存")
                    try {
                        val resolved = resolver.resolvePreferred(track, preferred)
                        if (resolved.source.trial) throw PlatformApiException("只有试听音源")
                        ensureActive()
                        val key = playbackCacheKey(context, resolved.track)
                        val disk = MusicDiskCache.get(context)
                        disk.pinAudio(key)
                        val upstream = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
                            .setUserAgent(PlatformHttp.DEFAULT_USER_AGENT).setConnectTimeoutMs(8_000).setReadTimeoutMs(8_000))
                        val dataSource = CacheDataSource.Factory().setCache(disk.audio)
                            .setUpstreamDataSourceFactory(upstream).createDataSource()
                        val currentWriter = CacheWriter(dataSource,
                            DataSpec.Builder().setUri(resolved.source.url).setKey(key).build(), null) { length, bytes, _ ->
                            ensureActive()
                            setStatus(track, if (length > 0) "正在缓存 ${(bytes * 100 / length).coerceIn(0, 100)}%" else "正在缓存")
                        }
                        writer = currentWriter
                        ensureActive()
                        currentWriter.cache()
                        ensureActive()
                        if (resolved.source.verificationPending) {
                            val duration = cachedAudioDuration(disk.audio, key, resolved.source.url)
                            val full = duration != null && qqMediaDurationMatches(duration, track.durationMs)
                            offline.verify(key, full)
                            if (!full) {
                                offline.remove(track)
                                throw PlatformApiException("该音源不是完整歌曲")
                            }
                        }
                        if (!offline.complete(key)) throw PlatformApiException("音频未完整缓存，请重试")
                        disk.audioChanged()
                        setStatus(track, offline.status(track))
                        try { resolver.lyrics(resolved.track) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* 歌词暂时不可用不影响已完成的音频缓存。 */ }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (required: PlatformSecurityVerificationRequired) { throw required }
                    catch (session: QqSessionRequestException) { throw session }
                    catch (error: Exception) { failed++; setStatus(track, "缓存失败 · ${error.asUserMessage()}") }
                    finally { writer = null }
                }
                mutable.update { it.copy(message = when {
                    detail.tracks.size < detail.count -> "已处理 ${detail.tracks.size} 首，平台尚未返回歌单全部歌曲"
                    failed == 0 -> "歌单缓存完成"
                    else -> "$failed 首缓存失败，可重新缓存重试"
                }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (namespace == requestedNamespace) mutable.update { it.copy(message = error.asUserMessage()) } }
            finally {
                if (namespace == requestedNamespace) {
                    mutable.update { it.copy(busyPlaylist = null) }
                    refreshStatuses(preserveFailures = true)
                }
            }
            }
        }
    }

    fun cancel() { writer?.cancel(); batch?.cancel() }

    fun remove(playlist: MusicPlaylist) {
        val oldBatch = batch
        val requestedNamespace = namespace
        cancel()
        scope.launch {
            oldBatch?.join()
            if (namespace != requestedNamespace) return@launch
            operationMutex.withLock {
            try {
                playlist.tracks.forEach(offline::remove)
                refreshStatuses()
                mutable.update { it.copy(message = "已删除歌单音频缓存，正在播放的文件会在切歌后删除") }
            } catch (error: Exception) { mutable.update { it.copy(message = error.asUserMessage()) } }
            }
        }
    }

    private suspend fun loadPlaylist(playlist: MusicPlaylist): MusicPlaylist {
        if (playlist.id.startsWith("local-cached-")) return playlist
        if (playlist.id == QQ_FAVORITES_PLAYLIST_ID) {
            val session = PlatformPreferences(context).readSession(MusicSource.QQ)
            return QqLibraryClient().favoritePlaylist(session.credential, session.account?.userId.orEmpty(),
                session.account?.hasVipAccess == true)
        }
        return catalog.playlistDetail(playlist)
    }

    private fun updatePlaylist(playlist: MusicPlaylist) {
        mutable.update { state -> state.copy(playlists = state.playlists.map { if (it.id == playlist.id) playlist else it }) }
        snapshots.save(namespace, mutable.value.playlists)
    }

    private fun refreshStatuses(preserveFailures: Boolean = false) {
        val statuses = mutable.value.playlists.flatMap { it.tracks }.distinctBy { it.id }.associate { it.id to offline.status(it) }
        mutable.update { state -> state.copy(statuses = statuses.mapValues { (id, status) ->
            state.statuses[id]?.takeIf { (preserveFailures && it.startsWith("缓存失败")) ||
                (state.busyPlaylist != null && (it.startsWith("正在缓存") || it == "等待缓存")) } ?: status
        }) }
    }
    private fun setStatus(track: MusicTrack, text: String) {
        mutable.update { if (it.statuses[track.id] == text) it else it.copy(statuses = it.statuses + (track.id to text)) }
    }

    companion object {
        @Volatile private var instance: MusicCacheManager? = null
        fun get(context: Context): MusicCacheManager = instance ?: synchronized(this) {
            instance ?: MusicCacheManager(context.applicationContext).also { instance = it }
        }
    }
}
