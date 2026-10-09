package com.musicone.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MusicCatalogUiState(
    val source: MusicSource? = null,
    val sessionRevision: Long? = null,
    val recommendations: List<MusicPlaylist> = emptyList(),
    val recommendedTracks: List<MusicTrack> = emptyList(),
    val recommendedTracksDate: String? = null,
    val libraryPlaylists: List<MusicPlaylist> = emptyList(),
    val loadingRecommendations: Boolean = false,
    val loadingRecommendedTracks: Boolean = false,
    val loadingLibrary: Boolean = false,
    val loadingPlaylistId: String? = null,
    val recommendationsSettled: Boolean = false,
    val recommendedTracksSettled: Boolean = false,
    val librarySettled: Boolean = false,
    val recommendationMessage: String? = null,
    val recommendedTracksMessage: String? = null,
    val libraryMessage: String? = null,
    val actionMessage: String? = null,
)

internal class MusicCatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val catalog = PlatformCatalogRepository(application)
    private val qqArtworkAliases = QqArtworkAliasStore(application)
    private val _state = MutableStateFlow(MusicCatalogUiState())
    val state: StateFlow<MusicCatalogUiState> = _state.asStateFlow()
    private var selectedSource = MusicSource.NETEASE
    private var configurationKey = ""
    private val playlistDetails = mutableMapOf<String, MusicPlaylist>()
    private var playlistLoadJob: Job? = null
    private var recommendationsJob: Job? = null
    private var recommendedTracksJob: Job? = null
    private var libraryJob: Job? = null
    private var recommendedTracksLoadedDay: String? = null
    private var recommendedTracksSnapshot: RecommendedTracksSnapshotStore? = null
    private var preparingRecommendedTracks = false
    private var recommendedTracksRequestId = 0L
    private var recommendedTracksPage = 0
    private var recommendationsPage = 0

    init {
        viewModelScope.launch {
            QqArtworkAliasStore.changes.collect(::applyQqArtworkAlias)
        }
    }

    fun configure(source: MusicSource, sessionRevision: Long, connected: Boolean = true) {
        val key = "$source\n$sessionRevision\n$connected"
        if (configurationKey == key) return
        configurationKey = key
        playlistLoadJob?.cancel()
        recommendationsJob?.cancel()
        recommendationsJob = null
        recommendedTracksJob?.cancel()
        libraryJob?.cancel()
        playlistDetails.clear()
        recommendedTracksLoadedDay = null
        recommendedTracksSnapshot = null
        preparingRecommendedTracks = false
        recommendedTracksPage = 0
        recommendationsPage = 0
        _state.value = MusicCatalogUiState(source = source, sessionRevision = sessionRevision)
        selectedSource = source
        if (!connected) {
            _state.update {
                it.copy(
                    recommendationsSettled = true,
                    recommendedTracksSettled = true,
                    librarySettled = true,
                )
            }
            return
        }
        // QQ 的旧歌单推荐暂停请求，由独立音乐流负责分页。
        if (source == MusicSource.QQ) _state.update { it.copy(recommendationsSettled = true) }
        else loadRecommendations()
        restoreRecommendedTracks()
        if (source == MusicSource.NETEASE) refreshLibrary()
        else _state.update { it.copy(librarySettled = true) }
    }

    fun loadPlaylist(playlist: MusicPlaylist, onLoaded: (MusicPlaylist) -> Unit) {
        if (!shouldCachePlaylistDetail(playlist)) {
            // “已播歌曲”本身就是当前完整快照，不能让同一固定 ID 的旧详情覆盖新列表与封面。
            playlistDetails.remove(playlist.id)
            _state.update { state -> state.copy(loadingPlaylistId = null, actionMessage = null) }
            onLoaded(playlist)
            return
        }
        playlistDetails[playlist.id]?.let {
            _state.update { state -> state.copy(loadingPlaylistId = null, actionMessage = null) }
            onLoaded(it)
            return
        }
        playlistLoadJob?.cancel()
        playlistLoadJob = viewModelScope.launch {
            _state.update { it.copy(loadingPlaylistId = playlist.id, actionMessage = null) }
            try {
                val detail = catalog.playlistDetail(playlist)
                playlistDetails[playlist.id] = detail
                _state.update { it.copy(loadingPlaylistId = null) }
                onLoaded(detail)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update { it.copy(loadingPlaylistId = null, actionMessage = error.asUserMessage()) }
            }
        }
    }

    fun invalidatePlaylist(playlistId: String) {
        playlistDetails.remove(playlistId)
    }

    private fun loadRecommendations() {
        if (recommendationsJob?.isActive == true) return
        val requestedConfiguration = configurationKey
        val requestedSource = selectedSource
        val requestedPage = recommendationsPage
        recommendationsJob = viewModelScope.launch {
            _state.update { it.copy(loadingRecommendations = true, recommendationMessage = null) }
            runCatching {
                catalog.recommendedPlaylists(requestedSource, requestedPage)
            }.onSuccess { playlists ->
                if (requestedConfiguration == configurationKey) {
                    recommendationsPage = requestedPage + 1
                    _state.update { it.copy(
                        recommendations = playlists,
                        loadingRecommendations = false,
                        recommendationsSettled = true,
                        recommendationMessage = null,
                    ) }
                }
            }.onFailure { error ->
                if (requestedConfiguration == configurationKey) {
                    _state.update { it.copy(loadingRecommendations = false, recommendationsSettled = true,
                        recommendationMessage = error.asUserMessage()) }
                }
            }
        }
    }

    fun refreshRecommendations() = loadRecommendations()

    fun refreshLibrary() {
        if (selectedSource != MusicSource.NETEASE || libraryJob?.isActive == true) return
        val requestedConfiguration = configurationKey
        val requestedSource = selectedSource
        libraryJob = viewModelScope.launch {
            _state.update { it.copy(loadingLibrary = true, libraryMessage = null) }
            try {
                val playlists = catalog.userPlaylists(requestedSource)
                if (requestedConfiguration != configurationKey) return@launch
                _state.update {
                    it.copy(
                        libraryPlaylists = playlists,
                        loadingLibrary = false,
                        librarySettled = true,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestedConfiguration != configurationKey) return@launch
                _state.update {
                    it.copy(
                        loadingLibrary = false,
                        librarySettled = true,
                        libraryMessage = error.asUserMessage(),
                    )
                }
            }
        }
    }

    private fun restoreRecommendedTracks() {
        val requestedConfiguration = configurationKey
        val requestedSource = selectedSource
        preparingRecommendedTracks = true
        recommendedTracksJob = viewModelScope.launch {
            _state.update { it.copy(loadingRecommendedTracks = true) }
            val (store, cached) = withContext(Dispatchers.IO) {
                val session = PlatformPreferences(getApplication()).readSession(requestedSource)
                val store = RecommendedTracksSnapshotStore(MusicDiskCache.get(getApplication()), session.cacheNamespace())
                store to store.read()
            }
            if (requestedConfiguration != configurationKey) return@launch
            recommendedTracksSnapshot = store
            recommendedTracksLoadedDay = cached?.refreshedDay
            recommendedTracksPage = cached?.nextPage ?: 0
            _state.update {
                it.copy(
                    recommendedTracks = cached?.tracks.orEmpty(),
                    recommendedTracksDate = cached?.refreshedDay,
                    loadingRecommendedTracks = false,
                    recommendedTracksSettled = cached != null,
                )
            }
            preparingRecommendedTracks = false
            recommendedTracksJob = null
            refreshRecommendedTracks(force = false)
        }
    }

    fun refreshRecommendedTracks(force: Boolean = false) {
        if (preparingRecommendedTracks || recommendedTracksJob?.isActive == true) return
        val requestedConfiguration = configurationKey
        val requestedSource = selectedSource
        val requestedPage = recommendedTracksPage
        val store = recommendedTracksSnapshot
        val requestId = ++recommendedTracksRequestId
        recommendedTracksJob = viewModelScope.launch {
            _state.update { it.copy(loadingRecommendedTracks = true, recommendedTracksMessage = null) }
            try {
                var currentDay = homeRefreshDay()
                var tracks: List<MusicTrack>
                while (true) {
                    val attemptStartDay = currentDay
                    val result = catalog.recommendedTracks(
                        requestedSource,
                        requestedPage,
                        policy = CatalogFetchPolicy.SERVER_VERIFY,
                    )
                    val attemptEndDay = homeRefreshDay()
                    if (attemptStartDay == attemptEndDay) {
                        tracks = result
                        currentDay = attemptEndDay
                        break
                    }
                    currentDay = attemptEndDay
                }
                if (requestedConfiguration != configurationKey || requestId != recommendedTracksRequestId) return@launch
                if (tracks.isEmpty()) throw PlatformApiException("暂时没有推荐歌曲，请稍后重试")
                recommendedTracksLoadedDay = currentDay
                recommendedTracksPage = requestedPage + 1
                val existingTracks = _state.value.recommendedTracks
                val sameTracks = existingTracks.isNotEmpty() &&
                    existingTracks.size == tracks.size &&
                    existingTracks.zip(tracks).all { (a, b) -> a.id == b.id }
                _state.update {
                    it.copy(
                        recommendedTracks = if (sameTracks) it.recommendedTracks else tracks,
                        recommendedTracksDate = currentDay,
                        loadingRecommendedTracks = false,
                        recommendedTracksSettled = true,
                        recommendedTracksMessage = null,
                    )
                }
                withContext(Dispatchers.IO) {
                    store?.write(RecommendedTracksSnapshot(tracks, requestedPage + 1, currentDay))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestedConfiguration != configurationKey || requestId != recommendedTracksRequestId) return@launch
                _state.update {
                    it.copy(
                        loadingRecommendedTracks = false,
                        recommendedTracksSettled = true,
                        recommendedTracksMessage = error.asUserMessage(),
                    )
                }
            } finally {
                if (requestId == recommendedTracksRequestId) {
                    recommendedTracksJob = null
                }
            }
        }
    }

    private fun applyQqArtworkAlias(trackId: String) {
        if (selectedSource != MusicSource.QQ) return
        _state.update { state ->
            val resolved = state.recommendedTracks.map { track ->
                if (track.id == trackId) qqArtworkAliases.apply(track) else track
            }
            if (resolved == state.recommendedTracks) state else state.copy(recommendedTracks = resolved)
        }
    }
}

internal fun shouldCachePlaylistDetail(playlist: MusicPlaylist): Boolean =
    playlist.id != QQ_RECENT_SONGS_PLAYLIST_ID

internal fun recommendedTracksRefreshDue(force: Boolean, hasTracks: Boolean, loadedDay: String?, today: String): Boolean =
    homeDailyRefreshDue(force, hasTracks, loadedDay, today)
