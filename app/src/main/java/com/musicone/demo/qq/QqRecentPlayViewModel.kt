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
    private val refreshGate = QqRecentPlayRefreshGate()

    init {
        viewModelScope.launch {
            QqRecentPlayChanges.revision.drop(1).collect { ensureLoaded(force = true) }
        }
    }

    fun setVisible(owner: Any, visible: Boolean) {
        refreshGate.setVisible(owner, visible)
        if (refreshGate.visible) {
            ensureLoaded()
        } else if (loadJob?.isActive == true) {
            // 播放页遮住两个消费页面时取消读取，回到页面后仍补齐最新记录。
            refreshGate.invalidate()
            loadJob?.cancel()
            _state.update { it.copy(loading = false) }
        }
    }

    fun ensureLoaded(force: Boolean = false) {
        if (force) refreshGate.invalidate()
        if (!refreshGate.visible) return
        val session = preferences.readSession(MusicSource.QQ)
        val key = session.account?.userId.orEmpty()
        if (key.isBlank()) {
            accountKey = ""
            loadJob?.cancel()
            _state.value = QqRecentPlayUiState(message = "登录后同步最近播放")
            return
        }
        if (!refreshGate.consumePending() && key == accountKey &&
            (_state.value.loading || _state.value.snapshot.songs.isNotEmpty())) return
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
