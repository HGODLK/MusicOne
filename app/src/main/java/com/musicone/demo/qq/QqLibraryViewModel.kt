package com.musicone.demo

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

internal data class QqLibraryUiState(
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

internal class QqLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PlatformPreferences(application)
    private val client = QqLibraryClient()
    private val artworkAliases = QqArtworkAliasStore(application)
    private val playlistArtworkAliases = QqPlaylistArtworkAliasResolver(QqApiClient(), artworkAliases)
    private val _state = MutableStateFlow(QqLibraryUiState())
    val state: StateFlow<QqLibraryUiState> = _state.asStateFlow()
    private var configurationKey = ""
    private var favoritesJob: Job? = null
    private var createdJob: Job? = null
    private var collectedJob: Job? = null
    private val collectionJobs = mutableMapOf<String, Job>()
    private var favoriteChanges: Map<String, Pair<MusicTrack, Boolean>> = emptyMap()
    private val favoriteSnapshot = QqFavoritePlaylistSnapshot()

    fun syncFavoriteChanges(changes: Map<String, Pair<MusicTrack, Boolean>>) {
        favoriteChanges = changes
        _state.update { state -> state.copy(favoritePlaylist = favoriteSnapshot.present(changes)) }
    }

    fun toggleCollected(playlist: MusicPlaylist) {
        if (playlist.id in _state.value.updatingCollections) return
        val session = preferences.readSession(MusicSource.QQ)
        val requestedKey = configurationKey
        val collected = _state.value.collectedPlaylists.none { it.id == playlist.id }
        collectionJobs[playlist.id] = viewModelScope.launch {
            _state.update { it.copy(updatingCollections = it.updatingCollections + playlist.id, collectionMessage = null) }
            try {
                withContext(Dispatchers.IO) { setQqPlaylistCollected(session.credential, playlist, collected) }
                if (configurationKey == requestedKey) {
                    collectedJob?.cancel()
                    _state.update {
                        val remaining = it.collectedPlaylists.filterNot { item -> item.id == playlist.id }
                        it.copy(collectedPlaylists = if (collected) listOf(playlist) + remaining else remaining,
                            loadingCollected = false)
                    }
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
        val key = "$source\n$sessionRevision"
        if (key == configurationKey) return
        configurationKey = key
        favoritesJob?.cancel()
        createdJob?.cancel()
        collectedJob?.cancel()
        collectionJobs.values.forEach { it.cancel() }
        collectionJobs.clear()
        favoriteChanges = emptyMap()
        favoriteSnapshot.clear()
        val session = preferences.readSession(MusicSource.QQ)
        val signedIn = source == MusicSource.QQ && session.credential.isNotBlank() && session.account != null
        _state.value = QqLibraryUiState(signedIn = signedIn)
        if (!signedIn) return
        loadFavorites(session, key)
        loadCreated(session, key)
        loadCollected(session, key)
    }

    fun refresh() {
        refresh(emptySet())
    }

    fun refreshAfterPlaylistReturn(playlistId: String, contentChanged: Boolean) {
        refresh(forcedArtworkRefreshIds(playlistId, contentChanged))
    }

    private fun refresh(forceArtworkIds: Set<String>) {
        val session = preferences.readSession(MusicSource.QQ)
        if (!_state.value.signedIn || session.account == null) return
        loadFavorites(session, configurationKey, forceArtworkIds)
        loadCreated(session, configurationKey, forceArtworkIds)
        loadCollected(session, configurationKey, forceArtworkIds)
    }

    private fun loadFavorites(
        session: PlatformSession,
        requestedKey: String,
        forceArtworkIds: Set<String> = emptySet(),
    ) {
        val account = session.account ?: return
        favoritesJob?.cancel()
        favoritesJob = viewModelScope.launch {
            _state.update { it.copy(loadingFavorites = true, favoritesMessage = null) }
            try {
                val playlist = withContext(Dispatchers.IO) {
                    playlistArtworkAliases.resolve(
                        client.favoritePlaylist(session.credential, account.userId, account.hasVipAccess),
                        session.credential,
                    )
                }
                if (requestedKey == configurationKey) {
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
    ) {
        val account = session.account ?: return
        createdJob?.cancel()
        createdJob = viewModelScope.launch {
            _state.update { it.copy(loadingCreated = true, createdMessage = null) }
            try {
                val playlists = withContext(Dispatchers.IO) { client.createdPlaylists(session.credential, account.userId) }
                if (requestedKey == configurationKey) {
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
    ) {
        val account = session.account ?: return
        collectedJob?.cancel()
        collectedJob = viewModelScope.launch {
            _state.update { it.copy(loadingCollected = true, collectedMessage = null) }
            try {
                val playlists = withContext(Dispatchers.IO) {
                    client.collectedPlaylists(session.credential, account.userId)
                }
                if (requestedKey == configurationKey) {
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
}

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
    val additions = changes.values.filter { (track, liked) -> liked && kept.none { it.id == track.id } }.map { it.first }
    val updated = additions + kept
    return copy(tracks = updated, count = (count + updated.size - tracks.size).coerceAtLeast(0))
}
