package com.musicone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class MainSourceSimilarRecommendationUiState(
    val baseTrack: MusicTrack? = null,
    val recommendations: List<MainSourceSimilarRecommendation> = emptyList(),
    val loading: Boolean = false,
)

internal class MainSourceSimilarRecommendationViewModel(application: Application) : AndroidViewModel(application) {
    private var configuredSource = PlatformPreferences(application).selectedSource()
    private val repository = MainSourceSimilarRecommendationRepository(application)
    private val _state = MutableStateFlow(MainSourceSimilarRecommendationUiState())
    val state: StateFlow<MainSourceSimilarRecommendationUiState> = _state.asStateFlow()
    private var configuredSessionRevision: Long? = null
    private var configurationKey = ""
    private var loadJob: Job? = null
    private var recentCandidates: List<MusicTrack> = emptyList()
    private var snapshotStore: MainSourceSimilarRecommendationSnapshotStore? = null
    private var refreshedDay: String? = null

    fun configure(sessionRevision: Long, recentTracks: List<MusicTrack>, source: MusicSource = PlatformPreferences(getApplication()).selectedSource()) {
        val sessionChanged = configuredSessionRevision != sessionRevision || configuredSource != source
        configuredSource = source
        recentCandidates = mainSourceSimilarRecentCandidates(recentTracks)
        if (sessionChanged) {
            configuredSessionRevision = sessionRevision
            configurationKey = ""
            loadJob?.cancel()
            snapshotStore = null
            refreshedDay = null
            _state.value = MainSourceSimilarRecommendationUiState(loading = true)
            loadJob = viewModelScope.launch {
                val (store, cached) = withContext(Dispatchers.IO) {
                    val session = PlatformPreferences(getApplication()).readSession(configuredSource)
                    val store = MainSourceSimilarRecommendationSnapshotStore(MusicDiskCache.get(getApplication()), session.cacheNamespace())
                    store to store.read()
                }
                if (configuredSessionRevision != sessionRevision) return@launch
                snapshotStore = store
                refreshedDay = cached?.refreshedDay
                prepareCachedArtwork(cached?.pages.orEmpty().flatMap { page -> page.songs.map { it.track.artworkUrl } },
                    getApplication<Application>())
                if (configuredSessionRevision != sessionRevision || configuredSource != source) return@launch
                _state.value = MainSourceSimilarRecommendationUiState(baseTrack = cached?.pages?.firstOrNull()?.baseTrack,
                    recommendations = cached?.pages.orEmpty())
                refresh(force = false)
            }
            return
        }
        refresh(force = false)
    }

    fun refresh(force: Boolean = true) {
        val snapshot = _state.value
        if (snapshotStore == null || snapshot.loading || !homeDailyRefreshDue(force,
                snapshot.recommendations.isNotEmpty(), refreshedDay, homeRefreshDay())) return
        val seeds = recentCandidates.shuffled().take(MAIN_SOURCE_SIMILAR_PAGE_COUNT)
        val baseTrack = seeds.firstOrNull() ?: return
        configurationKey = "${configuredSessionRevision.orEmptyText()}\n${seeds.joinToString { it.id }}"
        _state.update { it.copy(baseTrack = baseTrack) }
        load(seeds, configurationKey, replaceWhenComplete = snapshot.recommendations.isNotEmpty())
    }

    private fun load(
        baseTracks: List<MusicTrack>,
        requestedConfiguration: String,
        replaceWhenComplete: Boolean = false,
    ) {
        val day = homeRefreshDay()
        val store = snapshotStore
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val pages = mutableListOf<MainSourceSimilarRecommendation>()
            val previousRecommendations = _state.value.recommendations
            val shownTrackIds = if (replaceWhenComplete) {
                previousRecommendations.flatMapTo(mutableSetOf()) { page ->
                    page.songs.map { it.track.id }
                }
            } else {
                mutableSetOf()
            }
            shownTrackIds += baseTracks.map(MusicTrack::id)
            try {
                loadOrderedMainSourceSimilarRecommendations(baseTracks, load = { pageBase ->
                    repository.load(baseTrack = pageBase, amount = MAIN_SOURCE_SIMILAR_REQUEST_SIZE)
                }) { recommendation ->
                    if (requestedConfiguration != configurationKey) return@loadOrderedMainSourceSimilarRecommendations false
                    val visibleSongs = recommendation.songs
                        .filterNot { it.track.id in shownTrackIds }
                        .take(MAIN_SOURCE_SIMILAR_SIZE)
                    if (visibleSongs.isEmpty()) return@loadOrderedMainSourceSimilarRecommendations false
                    val page = recommendation.copy(songs = visibleSongs)
                    pages += page
                    shownTrackIds += visibleSongs.map { it.track.id }
                    if (!replaceWhenComplete) {
                        preloadMainSourceArtwork(visibleSongs.map { it.track.artworkUrl }, viewModelScope)
                        _state.update {
                            it.copy(
                                recommendations = pages.toList(),
                                loading = true,
                            )
                        }
                    }
                    true
                }
                preloadMainSourceArtwork(
                    pages.flatMap { page -> page.songs.map { it.track.artworkUrl } },
                    viewModelScope,
                )
                if (requestedConfiguration == configurationKey) {
                    _state.update {
                        it.copy(
                            recommendations = pages.takeIf { loaded -> loaded.isNotEmpty() }
                                ?: previousRecommendations,
                            loading = false,
                        )
                    }
                    if (pages.isNotEmpty()) {
                        refreshedDay = day
                        withContext(Dispatchers.IO) { store?.write(MainSourceSimilarRecommendationSnapshot(pages.toList(), day)) }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (requestedConfiguration != configurationKey) return@launch
                // 首页的相似推荐是增强内容，请求失败时不挤占主推荐流的错误区域。
                _state.update { it.copy(loading = false) }
            }
        }
    }

}

/** 仅在最近播放的较新窗口内随机取种子，避免旧歌或当前播放器状态改变首页推荐。 */
internal fun mainSourceSimilarRecentCandidates(recentTracks: List<MusicTrack>): List<MusicTrack> = recentTracks
    .asSequence()
    .filter(::canRequestMainSourceSimilarRecommendation)
    .distinctBy(MusicTrack::id)
    .take(MAIN_SOURCE_RECENT_WINDOW_SIZE)
    .toList()

private fun Long?.orEmptyText(): String = this?.toString().orEmpty()

internal fun canRequestMainSourceSimilarRecommendation(track: MusicTrack): Boolean =
    track.source != MusicSource.LOCAL && track.remoteId().isNotBlank()

internal fun mainSourceSimilarRecommendationRefreshSeed(state: MainSourceSimilarRecommendationUiState): MusicTrack? =
    state.recommendations.asReversed().firstNotNullOfOrNull { recommendation ->
        recommendation.songs.asReversed().firstNotNullOfOrNull { song ->
            song.track.takeIf(::canRequestMainSourceSimilarRecommendation)
        }
    } ?: state.baseTrack?.takeIf(::canRequestMainSourceSimilarRecommendation)
