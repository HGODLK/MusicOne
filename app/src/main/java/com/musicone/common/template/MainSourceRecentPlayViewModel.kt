package com.musicone

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
import com.musicone.sourceintegration.*

internal data class MainSourceRecentPlayUiState(
    val snapshot: MainSourceRecentSnapshot = MainSourceRecentSnapshot(),
    val loading: Boolean = false,
    val message: String? = null,
    // 音源没有最近播放服务，“我的”页整块隐藏。
    val unsupported: Boolean = false,
)

internal class MainSourceRecentPlayViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = PlatformPreferences(application)
    private var configuredSource = preferences.selectedSource()
    fun configure(source: MusicSource) { if (configuredSource != source) { configuredSource = source; accountKey = ""; loadJob?.cancel(); refreshGate.invalidate(); _state.value = MainSourceRecentPlayUiState(unsupported = !recentSupported(source)) } }
    // 以音源声明的 SourceDiscovery.supportsRecent 为准，未声明就不请求也不显示。
    private fun recentSupported(source: MusicSource) =
        SourcePackageController.get(getApplication()).running(source.id)?.entry?.discovery?.supportsRecent == true
    private val _state = MutableStateFlow(MainSourceRecentPlayUiState(unsupported = !recentSupported(configuredSource)))
    val state: StateFlow<MainSourceRecentPlayUiState> = _state.asStateFlow()
    private var accountKey = ""
    private var loadJob: Job? = null
    private val refreshGate = MainSourceRecentPlayRefreshGate()

    init {
        viewModelScope.launch {
            SourceRecentPlayChanges.revision.drop(1).collect { ensureLoaded(force = true) }
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
        if (!recentSupported(configuredSource)) {
            loadJob?.cancel()
            _state.value = MainSourceRecentPlayUiState(unsupported = true)
            return
        }
        // 启动期包尚未就绪时可能先判为不支持，就绪后恢复正常读取。
        if (_state.value.unsupported) _state.value = MainSourceRecentPlayUiState()
        val session = preferences.readSession(configuredSource)
        val key = session.account?.userId ?: if (!configuredSource.requiresAccount) "anonymous:${configuredSource.id}" else ""
        if (key.isBlank()) {
            accountKey = ""
            loadJob?.cancel()
            _state.value = MainSourceRecentPlayUiState(message = "登录后同步最近播放")
            return
        }
        if (!refreshGate.consumePending() && key == accountKey &&
            (_state.value.loading || _state.value.snapshot.songs.isNotEmpty())) return
        accountKey = key
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            try {
                val service = requireNotNull(SourcePackageController.get(getApplication()).running(configuredSource.id)?.entry?.discovery)
                val remote = service.recentSnapshot()
                val snapshot = MainSourceRecentSnapshot(remote.tracks.map { it.toMusicTrack() }, remote.totalTracks,
                    (remote.collections + remote.albums + remote.radios).map { it.toMusicPlaylist() }, configuredSource)
                if (key == accountKey) _state.value = MainSourceRecentPlayUiState(snapshot = snapshot)
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
