package com.musicone.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class MusicSearchUiState(
    val query: String = "",
    val results: List<MusicTrack> = emptyList(),
    val collections: List<MusicPlaylist> = emptyList(),
    val searching: Boolean = false,
    val message: String? = null,
)

internal class MusicSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val catalog = PlatformCatalogRepository(application)
    private val _state = MutableStateFlow(MusicSearchUiState())
    val state: StateFlow<MusicSearchUiState> = _state.asStateFlow()
    private var source = MusicSource.NETEASE
    private var configurationKey = ""
    private var searchJob: Job? = null

    fun configure(source: MusicSource, sessionRevision: Long) {
        val key = "$source\n$sessionRevision"
        if (configurationKey == key) return
        configurationKey = key
        this.source = source
        _state.update { it.copy(results = emptyList(), collections = emptyList(), searching = it.query.isNotBlank(), message = null) }
        if (_state.value.query.isNotBlank()) search(_state.value.query, immediately = true)
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        search(query, immediately = false)
    }

    private fun search(query: String, immediately: Boolean) {
        searchJob?.cancel()
        val normalized = query.trim()
        if (normalized.isBlank()) {
            _state.update { it.copy(results = emptyList(), collections = emptyList(), searching = false, message = null) }
            return
        }
        searchJob = viewModelScope.launch {
            val requestedConfiguration = configurationKey
            val requestedSource = source
            if (!immediately) delay(350)
            _state.update { it.copy(searching = true, message = null) }
            try {
                val (tracks, collections) = coroutineScope {
                    val songs = async { uniqueSearchResults(catalog.search(requestedSource, normalized)) }
                    val groups = async { catalog.searchCollections(requestedSource, normalized).distinctBy(MusicPlaylist::id) }
                    songs.await() to groups.await()
                }
                if (requestedConfiguration == configurationKey && _state.value.query.trim() == normalized) {
                    _state.update { it.copy(results = tracks, collections = collections, searching = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestedConfiguration == configurationKey && _state.value.query.trim() == normalized) {
                    _state.update { it.copy(results = emptyList(), collections = emptyList(), searching = false, message = error.asUserMessage()) }
                }
            }
        }
    }
}

internal fun uniqueSearchResults(tracks: List<MusicTrack>): List<MusicTrack> = tracks.distinctBy(MusicTrack::id)
