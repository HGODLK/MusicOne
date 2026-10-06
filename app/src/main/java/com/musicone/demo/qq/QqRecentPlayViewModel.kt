package com.musicone.demo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal data class QqRecentPlayUiState(
    val snapshot: QqRecentPlaySnapshot = QqRecentPlaySnapshot(),
    val loading: Boolean = false,
    val message: String? = null,
)

internal class QqRecentPlayViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PlatformPreferences(application)
    private val repository = QqRecentPlayRepository(application)
    private val _state = MutableStateFlow(QqRecentPlayUiState())
    val state: StateFlow<QqRecentPlayUiState> = _state.asStateFlow()
    private var accountKey = ""
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            QqRecentPlayChanges.revision.drop(1).collect { ensureLoaded(force = true) }
        }
    }

    fun ensureLoaded(force: Boolean = false) {
        val session = preferences.readSession(MusicSource.QQ)
        val key = session.account?.userId.orEmpty()
        if (key.isBlank()) {
            accountKey = ""
            loadJob?.cancel()
            _state.value = QqRecentPlayUiState(message = "登录后同步最近播放")
            return
        }
        if (!force && key == accountKey && (_state.value.loading || _state.value.snapshot.songs.isNotEmpty())) return
        accountKey = key
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            try {
                val snapshot = repository.load()
                if (key == accountKey) _state.value = QqRecentPlayUiState(snapshot = snapshot)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (key == accountKey) _state.update {
                    it.copy(loading = false, message = error.asUserMessage())
                }
            }
        }
    }
}
