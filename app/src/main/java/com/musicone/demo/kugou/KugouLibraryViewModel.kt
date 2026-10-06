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

internal data class KugouLibraryUiState(
    val signedIn: Boolean = false,
    val favoritePlaylist: MusicPlaylist? = null,
    val createdPlaylists: List<MusicPlaylist> = emptyList(),
    val collectedPlaylists: List<MusicPlaylist> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
)

internal class KugouLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PlatformPreferences(application)
    private val client = KugouApiClient()
    private val _state = MutableStateFlow(KugouLibraryUiState())
    val state: StateFlow<KugouLibraryUiState> = _state.asStateFlow()
    private var configurationKey = ""
    private var loadJob: Job? = null

    fun configure(source: MusicSource, sessionRevision: Long) {
        val key = "$source\n$sessionRevision"
        if (key == configurationKey) return
        configurationKey = key
        loadJob?.cancel()
        val session = preferences.readSession(MusicSource.KUGOU)
        val signedIn = source == MusicSource.KUGOU && session.account != null && session.credential.isNotBlank()
        _state.value = KugouLibraryUiState(signedIn = signedIn)
        if (signedIn) refresh()
    }

    fun refresh() {
        if (!_state.value.signedIn || loadJob?.isActive == true) return
        val requestedKey = configurationKey
        val cookie = preferences.credential(MusicSource.KUGOU)
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            try {
                val library = withContext(Dispatchers.IO) { client.library(cookie) }
                if (configurationKey == requestedKey) _state.update {
                    it.copy(
                        favoritePlaylist = library.favorite,
                        createdPlaylists = library.created,
                        collectedPlaylists = library.collected,
                        loading = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (configurationKey == requestedKey) _state.update {
                    it.copy(loading = false, message = error.asUserMessage())
                }
            }
        }
    }
}
