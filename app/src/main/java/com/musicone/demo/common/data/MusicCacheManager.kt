package com.musicone.demo

import android.content.Context
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class MusicCacheState(
    val playlists: List<MusicPlaylist> = emptyList(),
    val cachedTracks: List<MusicTrack> = emptyList(),
    val statuses: Map<String, String> = emptyMap(),
    val audioDetails: Map<String, CachedAudioDetails> = emptyMap(),
    val blocked: Map<String, String> = emptyMap(),
    val protected: Set<String> = emptySet(),
    val usage: CacheUsage = CacheUsage(),
    val limits: CacheLimits = CacheLimits(),
    val appliedLimits: CacheLimits = CacheLimits(),
    val busyPlaylist: String? = null,
    val busyTrack: String? = null,
    val message: String? = null,
    val loading: Boolean = false,
    val initialized: Boolean = false,
)

/** 缓存工作与界面生命周期分离，进度变化不替换歌单目录或重启页面入场。 */
@OptIn(FlowPreview::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicCacheManager private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val directory = CachePlaylistDirectory(context)
    private val offline = OfflineMusicStore(context)
    private val disk = MusicDiskCache.get(context)
    private val mutable = MutableStateFlow(MusicCacheState(limits = disk.limits.value, appliedLimits = disk.limits.value))
    val state = mutable.asStateFlow()
    @Volatile private var owners = emptyList<Pair<String, String>>()
    private val statusLock = Any()
    private var supplied = emptyList<MusicPlaylist>()
    private var configured = false
    private var batch: Job? = null
    private var loadJob: Job? = null
    private val operationMutex = Mutex()
    @Volatile private var writer: CacheWriter? = null
    private val settingsDraft = CacheSettingsDraft()

    @Synchronized fun stageCapacity(independent: Boolean, capacity: CacheCapacity) {
        val next = settingsDraft.select(disk.limits.value, independent, capacity)
        mutable.update { it.copy(limits = next) }
    }

    @Synchronized fun applyPendingSettings() {
        val pending = settingsDraft.consume() ?: return
        disk.applyLimits(pending)
    }

    fun clear(all: Boolean) {
        val oldBatch = batch
        cancel()
        scope.launch { oldBatch?.join(); operationMutex.withLock {
            try { if (all) disk.clearAll() else disk.clearOrdinary() }
            catch (error: Exception) { mutable.update { it.copy(message = "部分缓存暂时无法清理，请稍后重试") } }
            refreshStatuses()
        } }
    }

    init {
        scope.launch { disk.audioRevision.debounce(250).collect { refreshStatuses() } }
        scope.launch { MusicLibraryStore.get(context).state.collect { library ->
            mutable.update { old -> old.copy(playlists = (old.playlists.filter { it.source != MusicSource.LOCAL } + library.playlists)
                .distinctBy(::cachePlaylistKey)) }
            refreshStatuses()
        } }
    }

    fun configure(source: MusicSource, incoming: List<MusicPlaylist>) {
        val nextOwners = directory.signature()
        if (configured && nextOwners == owners && supplied == incoming) return
        val accountChanged = owners != nextOwners
        if (accountChanged) cancel()
        configured = true
        supplied = incoming
        owners = nextOwners
        loadJob?.cancel()
        loadJob = scope.launch {
            val cached = directory.cached(source, incoming)
            if (accountChanged) mutable.update { it.copy(playlists = cached, cachedTracks = emptyList(), statuses = emptyMap(), audioDetails = emptyMap(), message = null) }
            else mutable.update { it.copy(playlists = (cached + it.playlists).distinctBy(::cachePlaylistKey)) }
            refreshStatuses()
            if (!offline.hasNetwork()) return@launch
            for (provider in listOf(MusicSource.QQ, MusicSource.NETEASE, MusicSource.KUGOU)) {
                try {
                    val remote = directory.remote(provider)
                    ensureActive()
                    if (owners != nextOwners || directory.signature() != nextOwners) return@launch
                    if (remote.isNotEmpty()) {
                        val old = mutable.value.playlists
                        val merged = remote.map { playlist ->
                            val previous = old.firstOrNull { cachePlaylistKey(it) == cachePlaylistKey(playlist) }
                            if (playlist.tracks.size < playlist.count && previous != null) playlist.copy(tracks = previous.tracks) else playlist
                        }
                        mutable.update { it.copy(playlists = (it.playlists.filter { p -> p.source != provider } + merged)
                            .distinctBy(::cachePlaylistKey)) }
                        directory.save(mutable.value.playlists)
                        refreshStatuses()
                        remote.filter { it.isPrimaryCachePlaylist }.forEach { favorite ->
                            val detail = directory.load(favorite)
                            ensureActive()
                            if (owners == nextOwners) updatePlaylist(detail)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 刷新失败保留已保存的目录，打开歌单时可以重试。 */ }
            }
            refreshStatuses()
        }
    }

    fun open(playlist: MusicPlaylist) {
        val requestedOwners = owners
        scope.launch {
            if ((playlist.tracks.size >= playlist.count && playlist.tracks.isNotEmpty()) || !offline.hasNetwork()) {
                refreshStatuses(); return@launch
            }
            mutable.update { it.copy(loading = true, message = null) }
            try {
                val detail = directory.load(playlist)
                if (owners == requestedOwners && directory.signature() == requestedOwners) updatePlaylist(detail)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(message = error.asUserMessage()) } }
            finally { mutable.update { it.copy(loading = false) }; refreshStatuses() }
        }
    }

    fun play(playlist: MusicPlaylist, onReady: (MusicPlaylist) -> Unit) {
        val requestedOwners = owners
        scope.launch {
            try {
                val detail = if (offline.hasNetwork() && playlist.tracks.size < playlist.count) directory.load(playlist) else playlist
                if (owners != requestedOwners || directory.signature() != requestedOwners) return@launch
                if (detail.tracks.isEmpty()) { mutable.update { it.copy(message = "暂无可播放歌曲") }; return@launch }
                updatePlaylist(detail)
                withContext(Dispatchers.Main) { onReady(detail) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(message = error.asUserMessage()) } }
        }
    }

    fun cache(playlist: MusicPlaylist) {
        if (batch?.isCompleted == false) return
        val requestedOwners = owners
        mutable.update { it.copy(busyPlaylist = cachePlaylistKey(playlist), message = null) }
        batch = scope.launch {
            operationMutex.withLock {
                try {
                    if (!offline.hasNetwork()) throw PlatformApiException("请联网后缓存歌曲")
                    val detail = if (playlist.tracks.size < playlist.count || playlist.tracks.isEmpty()) directory.load(playlist) else playlist
                    ensureActive()
                    check(directory.signature() == requestedOwners) { "账号已变化，缓存任务已停止" }
                    updatePlaylist(detail)
                    val resolver = PlatformPlaybackResolver(context, qqCacheRequests = true)
                    var failed = 0
                    for (track in detail.tracks.distinctBy { it.cacheIdentity() }) {
                        ensureActive()
                        check(directory.signature() == requestedOwners) { "账号已变化，缓存任务已停止" }
                        mutable.update { it.copy(busyTrack = track.title) }
                        try {
                            if (track.source == MusicSource.LOCAL) { setStatus(track, "本地文件 · 无需缓存"); continue }
                            if (offline.keys(track).any(offline::complete)) { setStatus(track, offline.status(track)); continue }
                            disk.blockReason(track)?.let { throw CacheQuotaException(it) }
                            setStatus(track, "等待缓存")
                            val preferred = PlaybackQualityPreferences(context).read(track.source)
                            val resolved = resolver.resolvePreferred(track, preferred)
                            if (resolved.source.trial) throw PlatformApiException("只有试听音源")
                            ensureActive()
                            check(directory.signature() == requestedOwners) { "账号已变化，缓存任务已停止" }
                            val key = playbackCacheKey(context, resolved.track)
                            val url = cacheDownloadUrl(resolved.source)
                            val upstream = DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()
                                .setDefaultRequestProperties(mapOf("User-Agent" to PlatformHttp.DEFAULT_USER_AGENT))
                                .setConnectTimeoutMs(8_000).setReadTimeoutMs(8_000))
                            val dataSource = disk.streamingFactory(upstream, strict = true).createDataSource()
                            val currentWriter = CacheWriter(dataSource, DataSpec.Builder().setUri(url).setKey(key).setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                                .setHttpRequestHeaders(resolved.source.requestHeaders).build(), null) { length, bytes, _ ->
                                ensureActive()
                                setStatus(track, if (length > 0) "正在缓存 ${(bytes * 100 / length).coerceIn(0, 100)}%" else "正在缓存")
                            }
                            writer = currentWriter
                            try { currentWriter.cache() } finally { writer = null }
                            ensureActive()
                            if (resolved.source.verificationPending) {
                                val duration = cachedAudioDuration(disk.audio, key, url)
                                val full = duration != null && qqMediaDurationMatches(duration, track.durationMs)
                                offline.verify(key, full)
                                if (!full) { offline.remove(track); throw PlatformApiException("该音源不是完整歌曲") }
                            }
                            if (!offline.complete(key)) throw PlatformApiException("音频未完整缓存，请重试")
                            disk.audioChanged()
                            setStatus(track, offline.status(track))
                            try { resolver.lyrics(resolved.track) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { /* 歌词失败不影响已完成的音频。 */ }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (required: PlatformSecurityVerificationRequired) { throw required }
                        catch (session: QqSessionRequestException) { throw session }
                        catch (quota: CacheQuotaException) { setStatus(track, quota.message.orEmpty()); throw quota }
                        catch (error: Exception) { failed++; setStatus(track, "缓存失败 · ${error.asUserMessage()}") }
                    }
                    mutable.update { it.copy(message = if (failed == 0) "本次缓存处理完成" else "$failed 首缓存失败，可重试") }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { mutable.update { it.copy(message = error.asUserMessage()) } }
                finally {
                    mutable.update { it.copy(busyPlaylist = null, busyTrack = null) }
                    refreshStatuses()
                }
            }
        }
    }

    fun cacheTrack(track: MusicTrack) = cache(singleTrackPlaylist(track))
    fun removeTrack(track: MusicTrack) = remove(singleTrackPlaylist(track))
    fun cancel() { writer?.cancel(); batch?.cancel() }
    private fun singleTrackPlaylist(track: MusicTrack) = MusicPlaylist("cache-track:${track.id}", track.source,
        track.title, "", "", 1, track.artworkStart, track.artworkEnd, track.artworkMark, listOf(track), track.artworkUrl)

    fun remove(playlist: MusicPlaylist) {
        val oldBatch = batch
        cancel()
        scope.launch {
            oldBatch?.join()
            operationMutex.withLock {
                try {
                    playlist.tracks.forEach(offline::remove)
                    refreshStatuses()
                    mutable.update { it.copy(message = null) }
                } catch (error: Exception) { mutable.update { it.copy(message = error.asUserMessage()) } }
            }
        }
    }

    private fun updatePlaylist(playlist: MusicPlaylist) {
        mutable.update { state -> state.copy(playlists = (state.playlists.filterNot { cachePlaylistKey(it) == cachePlaylistKey(playlist) } + playlist)
            .distinctBy(::cachePlaylistKey)) }
        directory.save(mutable.value.playlists)
    }

    private fun refreshStatuses() = synchronized(statusLock) {
        val playlists = mutable.value.playlists
        val requestedOwners = owners
        val tracks = (playlists.flatMap { it.tracks } + MusicSource.entries.flatMap(offline::savedTracks))
            .distinctBy { it.cacheIdentity() }
        val protected = disk.protectedIdentities()
        val snapshot = offline.snapshot()
        val cached = tracks.filter { it.source != MusicSource.LOCAL && it.cacheIdentity() in snapshot.cached }
        val statuses = tracks.associate { track -> track.cacheIdentity() to if (track.source == MusicSource.LOCAL) "本地文件 · 无需缓存"
            else when (track.cacheIdentity()) {
                in snapshot.complete -> "已缓存 · ${if (track.cacheIdentity() in protected) "受保护" else "可被清理"}"
                in snapshot.cached -> "部分缓存"
                else -> "未缓存"
            } }
        val usage = disk.usage()
        val blocked = disk.blockReasons(tracks, usage)
        if (owners != requestedOwners || mutable.value.playlists != playlists) return@synchronized
        mutable.update { state -> state.copy(initialized = true, cachedTracks = cached, protected = protected, usage = usage,
            limits = settingsDraft.pending ?: disk.limits.value, appliedLimits = disk.limits.value,
            blocked = blocked, audioDetails = snapshot.audioDetails, statuses = statuses.mapValues { (id, text) ->
                state.statuses[id]?.takeIf { it.startsWith("缓存失败") || (state.busyPlaylist != null && (it.startsWith("正在缓存") || it == "等待缓存")) } ?: text
            }) }
    }
    private fun setStatus(track: MusicTrack, text: String) {
        val id = track.cacheIdentity()
        mutable.update { if (it.statuses[id] == text) it else it.copy(statuses = it.statuses + (id to text)) }
    }

    companion object {
        @Volatile private var instance: MusicCacheManager? = null
        fun get(context: Context): MusicCacheManager = instance ?: synchronized(this) {
            instance ?: MusicCacheManager(context.applicationContext).also { instance = it }
        }
        fun available(): MusicCacheManager? = instance
    }
}
