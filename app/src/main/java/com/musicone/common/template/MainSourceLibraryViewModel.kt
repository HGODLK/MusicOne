package com.musicone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.*

internal data class MainSourceLibraryUiState(
    val signedIn: Boolean = false,
    val favoritePlaylist: MusicPlaylist? = null,
    val createdPlaylists: List<MusicPlaylist> = emptyList(),
    val collectedPlaylists: List<MusicPlaylist> = emptyList(),
    val loadingFavorites: Boolean = false,
    val loadingCreated: Boolean = false,
    val loadingCollected: Boolean = false,
    val favoritesMessage: String? = null,
    val createdMessage: String? = null,
    val collectedMessage: String? = null,
    val updatingCollections: Set<String> = emptySet(),
    val collectionMessage: String? = null,
    val artworkVersions: Map<String, Long> = emptyMap(),
)

internal class MainSourceLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PlatformPreferences(application)
    private var configuredSource = preferences.selectedSource()
    private val controller = SourcePackageController.get(application)
    private val _state = MutableStateFlow(MainSourceLibraryUiState())
    val state: StateFlow<MainSourceLibraryUiState> = _state.asStateFlow()
    private var configurationKey = ""
    private var favoritesJob: Job? = null
    private var createdJob: Job? = null
    private var collectedJob: Job? = null
    private val collectionJobs = mutableMapOf<String, Job>()
    private var favoriteChanges: Map<String, Pair<MusicTrack, Boolean>> = emptyMap()
    private val favoriteSnapshot = MainSourceFavoritePlaylistSnapshot()
    private val snapshots = MainSourceLibrarySnapshotStore(application)
    private var configuredNamespace = ""
    private var restoreJob: Job? = null
    private var changesJob: Job? = null
    private val restoreRefresh = MainSourceLibraryRestoreRefresh()

    private fun favoriteAccess(source: MusicSource, signedIn: Boolean): MainSourceFavoriteAccess {
        val entry = controller.running(source.id)?.entry
        return mainSourceFavoriteAccess(signedIn, source.requiresAccount, entry?.authentication != null,
            SourceLibrarySection.FAVORITE_TRACKS in entry?.collections?.librarySections.orEmpty())
    }

    private suspend fun loadLibrary(source: MusicSource, section: SourceLibrarySection, policy: SourceFetchPolicy): List<MusicPlaylist> {
        val service = requireNotNull(controller.running(source.id)?.entry?.collections) { "音源未启用资料库" }
        return service.loadMainSourceLibrary(section, policy).map { it.toMusicPlaylist() }
    }
    fun syncFavoriteChanges(changes: Map<String, Pair<MusicTrack, Boolean>>) {
        favoriteChanges = changes
        _state.update { state -> state.copy(favoritePlaylist = favoriteSnapshot.present(changes)) }
    }

    fun toggleCollected(playlist: MusicPlaylist) {
        val favorites = controller.running(configuredSource.id)?.entry?.favorites
        if (!playlist.canCollectWith(favorites, preferences.readSession(configuredSource).account?.userId)) return
        if (playlist.id in _state.value.updatingCollections) return
        val session = preferences.readSession(configuredSource)
        val requestedKey = configurationKey
        val collected = _state.value.collectedPlaylists.none { sameSourceCollection(it, playlist, favorites, session.account?.userId) }
        collectionJobs[playlist.id] = viewModelScope.launch {
            _state.update { it.copy(updatingCollections = it.updatingCollections + playlist.id, collectionMessage = null) }
            try {
                requireNotNull(controller.running(session.source.id)?.entry?.favorites).setCollectionFavorite(playlist.toSourceCollection(session.account?.userId), collected)
                if (configurationKey == requestedKey) {
                    collectedJob?.cancel()
                    _state.update {
                        val remaining = it.collectedPlaylists.filterNot { item -> sameSourceCollection(item, playlist, favorites, session.account?.userId) }
                        it.copy(collectedPlaylists = if (collected) listOf(playlist) + remaining else remaining,
                            loadingCollected = false)
                    }
                    val playlists = _state.value.collectedPlaylists
                    withContext(Dispatchers.IO) { snapshots.saveCollected(session, playlists) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (configurationKey == requestedKey) _state.update { it.copy(collectionMessage = error.asUserMessage()) }
            } finally {
                if (configurationKey == requestedKey) _state.update { it.copy(updatingCollections = it.updatingCollections - playlist.id) }
            }
        }
    }

    fun configure(source: MusicSource, sessionRevision: Long) {
        val session = preferences.readSession(source)
        val accountState = controller.running(source.id)?.entry?.authentication?.currentAccount()
        val authenticated = accountState == null || accountState.status == SourceSessionStatus.AUTHENTICATED
        val key = "$source\n$sessionRevision\n${session.credential}\n$authenticated"
        if (key == configurationKey) return
        configurationKey = key
        restoreJob?.cancel()
        changesJob?.cancel()
        restoreRefresh.cancel()
        favoritesJob?.cancel()
        createdJob?.cancel()
        collectedJob?.cancel()
        collectionJobs.values.forEach { it.cancel() }
        collectionJobs.clear()
        configuredSource = source
        val signedIn = authenticated && session.credential.isNotBlank() && session.account != null
        val readable = signedIn || favoriteAccess(source, signedIn) == MainSourceFavoriteAccess.AVAILABLE
        val sameAccount = readable && configuredNamespace == session.cacheNamespace()
        configuredNamespace = if (readable) session.cacheNamespace() else ""
        if (!sameAccount) {
            favoriteChanges = emptyMap()
            favoriteSnapshot.clear()
        }
        val restoration = if (readable) restoreRefresh.begin() else null
        // 同账号续期只更新请求归属，不能先清空已显示的目录。
        _state.value = if (sameAccount) _state.value.copy(
            loadingFavorites = false, loadingCreated = false, loadingCollected = false,
            updatingCollections = emptySet(), collectionMessage = null,
        ) else MainSourceLibraryUiState(signedIn = signedIn)
        if (restoration == null) return
        session.account?.let { account ->
            changesJob = viewModelScope.observeSourceLibraryChanges(controller.running(source.id)?.entry?.collections,
                account.userId, { configurationKey == key }) { sections ->
                if (restoreRefresh.defer(SourceFetchPolicy.CACHE_FIRST, emptySet())) return@observeSourceLibraryChanges
                if (SourceLibrarySection.FAVORITE_TRACKS in sections) loadFavorites(session, key)
                if (SourceLibrarySection.CREATED_COLLECTIONS in sections) loadCreated(session, key)
                if (SourceLibrarySection.SAVED_COLLECTIONS in sections) loadCollected(session, key)
            }
        }
        restoreJob = viewModelScope.launch {
            if (!sameAccount) {
                val cached = withContext(Dispatchers.IO) { snapshots.read(session) }
                if (key != configurationKey) return@launch
                cached.favoritePlaylist?.let(favoriteSnapshot::record)
                _state.value = cached.copy(signedIn = signedIn, favoritePlaylist = favoriteSnapshot.present(favoriteChanges))
            }
            val request = restoreRefresh.complete(restoration) ?: return@launch
            loadFavorites(session, key, request.forceArtworkIds, request.policy)
            loadCreated(session, key, request.forceArtworkIds, request.policy)
            loadCollected(session, key, request.forceArtworkIds, request.policy)
        }
    }

    fun refresh() {
        refresh(SourceFetchPolicy.REFRESH, emptySet())
    }

    fun refreshAfterPlaylistReturn(playlistId: String, contentChanged: Boolean) {
        refresh(libraryReturnFetchPolicy(contentChanged), forcedArtworkRefreshIds(playlistId, contentChanged))
    }

    private fun refresh(policy: SourceFetchPolicy, forceArtworkIds: Set<String>) {
        val session = preferences.readSession(configuredSource)
        if ((!_state.value.signedIn || session.account == null) &&
            favoriteAccess(configuredSource, false) != MainSourceFavoriteAccess.AVAILABLE) return
        if (restoreRefresh.defer(policy, forceArtworkIds)) return
        loadFavorites(session, configurationKey, forceArtworkIds, policy)
        loadCreated(session, configurationKey, forceArtworkIds, policy)
        loadCollected(session, configurationKey, forceArtworkIds, policy)
    }

    private fun loadFavorites(
        session: PlatformSession,
        requestedKey: String,
        forceArtworkIds: Set<String> = emptySet(),
        policy: SourceFetchPolicy = SourceFetchPolicy.CACHE_FIRST,
    ) {
        if (favoriteAccess(session.source, _state.value.signedIn && session.account != null) != MainSourceFavoriteAccess.AVAILABLE) return
        favoritesJob?.cancel()
        favoritesJob = viewModelScope.launch {
            _state.update { it.copy(loadingFavorites = true, favoritesMessage = null) }
            try {
                val playlist = withContext(Dispatchers.IO) {
                    val service = requireNotNull(controller.running(session.source.id)?.entry?.collections)
                    val (detail, tracks) = service.loadMainSourceFavorites(policy)
                    detail.toMusicPlaylist(tracks.map { it.toMusicTrack() })
                }
                if (requestedKey == configurationKey) {
                    withContext(Dispatchers.IO) { snapshots.saveFavorites(session, playlist) }
                    if (requestedKey != configurationKey) return@launch
                    favoriteSnapshot.record(playlist)
                    _state.update { state ->
                        val refreshed = requireNotNull(favoriteSnapshot.present(favoriteChanges))
                        state.copy(
                            favoritePlaylist = refreshed,
                            loadingFavorites = false,
                            artworkVersions = refreshedArtworkVersions(
                                state.artworkVersions,
                                listOfNotNull(state.favoritePlaylist),
                                listOf(refreshed),
                                forceArtworkIds,
                            ),
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestedKey == configurationKey) {
                    _state.update { it.copy(loadingFavorites = false, favoritesMessage = error.asUserMessage()) }
                }
            }
        }
    }

    private fun loadCreated(
        session: PlatformSession,
        requestedKey: String,
        forceArtworkIds: Set<String> = emptySet(),
        policy: SourceFetchPolicy = SourceFetchPolicy.CACHE_FIRST,
    ) {
        val account = session.account ?: return
        createdJob?.cancel()
        createdJob = viewModelScope.launch {
            _state.update { it.copy(loadingCreated = true, createdMessage = null) }
            try {
                val playlists = withContext(Dispatchers.IO) {
                    loadLibrary(session.source, SourceLibrarySection.CREATED_COLLECTIONS, policy)
                }
                if (requestedKey == configurationKey) {
                    withContext(Dispatchers.IO) { snapshots.saveCreated(session, playlists) }
                    if (requestedKey != configurationKey) return@launch
                    _state.update { state -> state.copy(
                        createdPlaylists = playlists,
                        loadingCreated = false,
                        artworkVersions = refreshedArtworkVersions(
                            state.artworkVersions,
                            state.createdPlaylists,
                            playlists,
                            forceArtworkIds,
                        ),
                    ) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestedKey == configurationKey) {
                    _state.update { it.copy(loadingCreated = false, createdMessage = error.asUserMessage()) }
                }
            }
        }
    }

    private fun loadCollected(
        session: PlatformSession,
        requestedKey: String,
        forceArtworkIds: Set<String> = emptySet(),
        policy: SourceFetchPolicy = SourceFetchPolicy.CACHE_FIRST,
    ) {
        val account = session.account ?: return
        collectedJob?.cancel()
        collectedJob = viewModelScope.launch {
            _state.update { it.copy(loadingCollected = true, collectedMessage = null) }
            try {
                val playlists = withContext(Dispatchers.IO) {
                    loadLibrary(session.source, SourceLibrarySection.SAVED_COLLECTIONS, policy)
                }
                if (requestedKey == configurationKey) {
                    withContext(Dispatchers.IO) { snapshots.saveCollected(session, playlists) }
                    if (requestedKey != configurationKey) return@launch
                    _state.update { state -> state.copy(
                        collectedPlaylists = playlists,
                        loadingCollected = false,
                        artworkVersions = refreshedArtworkVersions(
                            state.artworkVersions,
                            state.collectedPlaylists,
                            playlists,
                            forceArtworkIds,
                        ),
                    ) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestedKey == configurationKey) {
                    _state.update { it.copy(loadingCollected = false, collectedMessage = error.asUserMessage()) }
                }
            }
        }
    }

    override fun onCleared() {
        // 即使音源错误吞掉取消，旧请求也不能再写入已销毁的资料库。
        configurationKey = ""
        super.onCleared()
    }
}

internal fun libraryReturnFetchPolicy(contentChanged: Boolean): SourceFetchPolicy =
    if (contentChanged) SourceFetchPolicy.REFRESH else SourceFetchPolicy.CACHE_FIRST

internal fun forcedArtworkRefreshIds(playlistId: String, contentChanged: Boolean): Set<String> =
    if (contentChanged) setOf(playlistId) else emptySet()

internal fun refreshedArtworkVersions(
    current: Map<String, Long>,
    previous: List<MusicPlaylist>,
    refreshed: List<MusicPlaylist>,
    forceRefreshIds: Set<String> = emptySet(),
): Map<String, Long> {
    val previousById = previous.associateBy(MusicPlaylist::id)
    val result = current.toMutableMap()
    refreshed.forEach { playlist ->
        val previousPlaylist = previousById[playlist.id]
        // 首次出现时应直接复用磁盘缓存；只有已展示内容变化后才强制刷新远端封面。
        if (playlist.id in forceRefreshIds ||
            previousPlaylist != null && !previousPlaylist.hasSameLibraryCardPresentation(playlist)
        ) {
            result[playlist.id] = (result[playlist.id] ?: 0L) + 1L
        }
    }
    return result
}

internal fun MusicPlaylist.withFavoriteChanges(changes: Map<String, Pair<MusicTrack, Boolean>>): MusicPlaylist {
    val kept = tracks
        .filterNot { changes[it.id]?.second == false }
        // 已存在的远端收藏也要采用本地刚补全的封面，不能只在新增行时使用变更对象。
        .map { track -> changes[track.id]?.takeIf { it.second }?.first ?: track }
    val additions = changes.values.filter { (track, liked) ->
        liked && track.source == source && kept.none { it.id == track.id }
    }.map { it.first }
    val updated = (if (isPlatformFavorites) additions.asReversed() else additions) + kept
    return copy(tracks = updated, count = (count + updated.size - tracks.size).coerceAtLeast(0),
        artworkUrl = if (isPlatformFavorites) updated.firstOrNull()?.artworkUrl else artworkUrl)
}
