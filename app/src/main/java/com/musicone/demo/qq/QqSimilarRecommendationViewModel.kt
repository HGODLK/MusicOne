package com.musicone.demo

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

internal data class QqSimilarRecommendationUiState(
    val baseTrack: MusicTrack? = null,
    val recommendations: List<QqSimilarRecommendation> = emptyList(),
    val loading: Boolean = false,
)

internal class QqSimilarRecommendationViewModel(application: Application) : AndroidViewModel(application) {
    private val artworkAliases = QqArtworkAliasStore(application)
    private val repository = QqSimilarRecommendationRepository(application, artworkAliases)
    private val _state = MutableStateFlow(QqSimilarRecommendationUiState())
    val state: StateFlow<QqSimilarRecommendationUiState> = _state.asStateFlow()
    private var configuredSessionRevision: Long? = null
    private var configurationKey = ""
    private var loadJob: Job? = null
    private var recentCandidates: List<MusicTrack> = emptyList()
    private var snapshotStore: QqSimilarRecommendationSnapshotStore? = null
    private var refreshedDay: String? = null

    init {
        viewModelScope.launch {
            QqArtworkAliasStore.changes.collect(::applyArtworkAlias)
        }
    }

    fun configure(sessionRevision: Long, recentTracks: List<MusicTrack>) {
        val sessionChanged = configuredSessionRevision != sessionRevision
        recentCandidates = qqSimilarRecentCandidates(recentTracks)
        if (sessionChanged) {
            configuredSessionRevision = sessionRevision
            configurationKey = ""
            loadJob?.cancel()
            snapshotStore = null
            refreshedDay = null
            _state.value = QqSimilarRecommendationUiState(loading = true)
            loadJob = viewModelScope.launch {
                val (store, cached) = withContext(Dispatchers.IO) {
                    val session = PlatformPreferences(getApplication()).readSession(MusicSource.QQ)
                    val store = QqSimilarRecommendationSnapshotStore(MusicDiskCache.get(getApplication()), session.cacheNamespace())
                    store to store.read()
                }
                if (configuredSessionRevision != sessionRevision) return@launch
                snapshotStore = store
                refreshedDay = cached?.refreshedDay
                _state.value = QqSimilarRecommendationUiState(baseTrack = cached?.pages?.firstOrNull()?.baseTrack,
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
        val seeds = recentCandidates.shuffled().take(QQ_SIMILAR_RECOMMENDATION_PAGE_COUNT)
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
            val pages = mutableListOf<QqSimilarRecommendation>()
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
                loadOrderedQqSimilarRecommendations(baseTracks, load = { pageBase ->
                    repository.load(baseTrack = pageBase, amount = QQ_SIMILAR_RECOMMENDATION_REQUEST_SIZE)
                }) { recommendation ->
                    if (requestedConfiguration != configurationKey) return@loadOrderedQqSimilarRecommendations false
                    val visibleSongs = recommendation.songs
                        .filterNot { it.track.id in shownTrackIds }
                        .take(QQ_SIMILAR_RECOMMENDATION_SIZE)
                    if (visibleSongs.isEmpty()) return@loadOrderedQqSimilarRecommendations false
                    val page = recommendation.copy(songs = visibleSongs)
                    pages += page
                    shownTrackIds += visibleSongs.map { it.track.id }
                    if (!replaceWhenComplete) {
                        preloadQqArtwork(visibleSongs.map { it.track.artworkUrl }, viewModelScope)
                        _state.update {
                            it.copy(
                                recommendations = pages.toList(),
                                loading = true,
                            )
                        }
                    }
                    true
                }
                preloadQqArtwork(
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
                        withContext(Dispatchers.IO) { store?.write(QqSimilarRecommendationSnapshot(pages.toList(), day)) }
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

    private fun applyArtworkAlias(trackId: String) {
        _state.update { state ->
            val resolvedBase = state.baseTrack?.let { if (it.id == trackId) artworkAliases.apply(it) else it }
            val resolvedRecommendations = state.recommendations.map { recommendation ->
                recommendation.copy(
                    baseTrack = recommendation.baseTrack.let {
                        if (it.id == trackId) artworkAliases.apply(it) else it
                    },
                    songs = recommendation.songs.map { song ->
                        if (song.track.id == trackId) song.copy(track = artworkAliases.apply(song.track)) else song
                    },
                )
            }
            if (resolvedBase == state.baseTrack && resolvedRecommendations == state.recommendations) state
            else state.copy(baseTrack = resolvedBase, recommendations = resolvedRecommendations)
        }
    }
}

/** 仅在最近播放的较新窗口内随机取种子，避免旧歌或当前播放器状态改变首页推荐。 */
internal fun qqSimilarRecentCandidates(recentTracks: List<MusicTrack>): List<MusicTrack> = recentTracks
    .asSequence()
    .filter(::canRequestQqSimilarRecommendation)
    .distinctBy(MusicTrack::id)
    .take(QQ_RECENT_SONG_WINDOW_SIZE)
    .toList()

private fun Long?.orEmptyText(): String = this?.toString().orEmpty()

internal fun canRequestQqSimilarRecommendation(track: MusicTrack): Boolean =
    track.source == MusicSource.QQ && (track.catalogId.toLongOrNull() ?: 0L) > 0L

internal fun qqSimilarRecommendationRefreshSeed(state: QqSimilarRecommendationUiState): MusicTrack? =
    state.recommendations.asReversed().firstNotNullOfOrNull { recommendation ->
        recommendation.songs.asReversed().firstNotNullOfOrNull { song ->
            song.track.takeIf(::canRequestQqSimilarRecommendation)
        }
    } ?: state.baseTrack?.takeIf(::canRequestQqSimilarRecommendation)
