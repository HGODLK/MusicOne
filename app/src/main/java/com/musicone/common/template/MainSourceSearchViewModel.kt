package com.musicone

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray

internal data class MainSourceSearchState(
    val opened: Boolean = false,
    val full: Boolean = false,
    val query: String = "",
    val submitted: String = "",
    val revision: Int = 0,
    val history: List<String> = emptyList(),
    val supportsHotSearch: Boolean = false,
    val hotSearches: List<String> = emptyList(),
    val hotLoading: Boolean = false,
    val hotError: String? = null,
    val suggestions: List<String> = emptyList(),
    val suggesting: Boolean = false,
    val suggestionError: String? = null,
    val pages: Map<MainSourceSearchTab, MainSourceSearchPage> = emptyMap(),
    val singer: MainSourceArtist? = null,
)

internal class MainSourceSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MainSourceSearchRepository(application)
    val source get() = repository.source
    val tabs get() = repository.tabs
    private val preferences get() = getApplication<Application>().getSharedPreferences("${repository.source.name.lowercase()}_search", 0)
    private val _state = MutableStateFlow(MainSourceSearchState(history = runCatching {
        val values = JSONArray(preferences.getString("history", "[]"))
        (0 until values.length()).map { values.getString(it) }.filter { it.isNotBlank() }.distinct().take(16)
    }.getOrDefault(emptyList())))
    val state = _state.asStateFlow()
    private var suggestionJob: Job? = null
    private var hotJob: Job? = null
    private val jobs = mutableMapOf<MainSourceSearchTab, Job>()
    val loadingGate = MainSourceSearchLoadingGate()
    private var sessionRevision: Long? = null

    fun configure(revision: Long, source: MusicSource = PlatformPreferences(getApplication()).selectedSource()) {
        if (sessionRevision == revision && repository.source == source) return
        repository.source = source
        sessionRevision = revision
        hotJob?.cancel()
        _state.update { it.copy(hotSearches = emptyList(), hotLoading = false, hotError = null, supportsHotSearch = repository.supportsHotSearch) }
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        val history = runCatching { val values = JSONArray(preferences.getString("history", "[]"));
            (0 until values.length()).map { values.getString(it) }.filter(String::isNotBlank).distinct().take(16) }.getOrDefault(emptyList())
        _state.update { it.copy(pages = emptyMap(), history = history, revision = it.revision + 1) }
        if (tabs.isEmpty()) { deactivate(); return }
        if (_state.value.full) load(MainSourceSearchTab.ALL)
        else if (_state.value.opened) { setQuery(_state.value.query); loadHotSearch() }
    }

    fun deactivate() {
        hotJob?.cancel()
        loadingGate.pause()
        suggestionJob?.cancel()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _state.update { MainSourceSearchState(history = it.history, revision = it.revision + 1) }
    }

    fun open() {
        if (tabs.isEmpty()) return
        if (!_state.value.opened) loadingGate.pause()
        _state.update { it.copy(opened = true) }
        setQuery(_state.value.query)
        if (_state.value.hotSearches.isEmpty()) loadHotSearch()
    }
    fun close() {
        loadingGate.pause()
        suggestionJob?.cancel()
        // 关闭后不保留等待门控的请求；再次搜索同词也能重新启动未完成的分类。
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _state.update { it.withSearchMenuClosed().copy(
            pages = it.pages.mapValues { (_, page) -> page.copy(loading = false) }) }
    }
    fun back() {
        when {
            _state.value.singer != null -> _state.update { it.copy(singer = null) }
            _state.value.full -> {
                loadingGate.pause()
                _state.update { it.copy(full = false) }
                setQuery(_state.value.query)
            }
            else -> close()
        }
    }
    fun singer(value: MainSourceArtist) { _state.update { it.copy(singer = value) } }
    fun removeHistory(value: String?) {
        val history = if (value == null) emptyList() else _state.value.history.filterNot { it == value }
        preferences.edit().putString("history", JSONArray(history).toString()).apply()
        _state.update { it.copy(history = history) }
    }
    fun setQuery(query: String) {
        suggestionJob?.cancel()
        _state.update { it.copy(query = query,
            // 同词返回菜单时保留原联想，后台刷新不能让收回中的菜单先塌缩再撑开。
            suggestions = if (query == it.query) it.suggestions else emptyList(),
            suggestionError = null, suggesting = query.isNotBlank()) }
        if (query.isBlank()) return
        suggestionJob = viewModelScope.launch {
            delay(250)
            try {
                loadingGate.awaitReady()
                if (!_state.value.opened || _state.value.full || _state.value.query != query) return@launch
                val suggestions = repository.suggest(query)
                loadingGate.awaitReady()
                _state.update { if (it.opened && !it.full && it.query == query)
                    it.copy(suggestions = suggestions, suggesting = false) else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                loadingGate.awaitReady()
                _state.update { if (it.query == query) it.copy(suggesting = false, suggestionError = error.asUserMessage()) else it }
            }
        }
    }
    fun loadHotSearch() {
        if (!repository.supportsHotSearch || hotJob?.isActive == true) return
        val revision = sessionRevision
        val requestedSource = source
        _state.update { it.copy(supportsHotSearch = true, hotLoading = true, hotError = null) }
        hotJob = viewModelScope.launch {
            try {
                val words = repository.hotSearches()
                if (revision == sessionRevision && requestedSource == source) _state.update { it.copy(hotSearches = words, hotLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (revision == sessionRevision && requestedSource == source) _state.update { it.copy(hotLoading = false, hotError = failure.asUserMessage()) }
            }
        }
    }
    fun submit(query: String = _state.value.query) {
        val word = query.trim()
        if (word.isEmpty() || tabs.isEmpty()) return
        if (!_state.value.full) loadingGate.pause()
        suggestionJob?.cancel()
        val history = updatedMainSourceSearchHistory(_state.value.history, word)
        preferences.edit().putString("history", JSONArray(history).toString()).apply()
        val changed = word != _state.value.submitted
        if (changed) { jobs.values.forEach { it.cancel() }; jobs.clear() }
        _state.update { it.copy(query = word, submitted = word, full = true, opened = true,
            suggesting = false, history = history, pages = if (changed) emptyMap() else it.pages,
            revision = if (changed) it.revision + 1 else it.revision) }
        load(MainSourceSearchTab.ALL)
    }
    fun load(tab: MainSourceSearchTab, more: Boolean = false, retry: Boolean = false) {
        val snapshot = _state.value
        if (tab !in tabs || snapshot.submitted.isBlank() || jobs[tab]?.isActive == true) return
        val old = snapshot.pages[tab] ?: MainSourceSearchPage()
        if (more && !old.hasMore || !more && !retry && old.page > 0) return
        val page = if (more) old.page + 1 else 1
        jobs[tab] = viewModelScope.launch {
            loadingGate.awaitReady()
            if (!_state.value.full || _state.value.revision != snapshot.revision) return@launch
            _state.update { it.copy(pages = it.pages + (tab to old.copy(loading = true, error = null))) }
            try {
                val result = repository.search(snapshot.submitted, tab, page)
                loadingGate.awaitReady()
                if (_state.value.revision == snapshot.revision) _state.update {
                    it.copy(pages = it.pages + (tab to if (more) old.append(result) else result))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                loadingGate.awaitReady()
                if (_state.value.revision == snapshot.revision) _state.update {
                    it.copy(pages = it.pages + (tab to old.copy(loading = false, error = error.asUserMessage())))
                }
            }
        }
    }
}
