package com.musicone.demo

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

internal data class QqSearchState(
    val opened: Boolean = false,
    val full: Boolean = false,
    val query: String = "",
    val submitted: String = "",
    val revision: Int = 0,
    val history: List<String> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val suggesting: Boolean = false,
    val suggestionError: String? = null,
    val pages: Map<QqSearchTab, QqSearchPage> = emptyMap(),
    val singer: QqSearchSinger? = null,
)

internal class QqSearchViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = QqSearchRepository(application)
    private val preferences = application.getSharedPreferences("qq_search", 0)
    private val _state = MutableStateFlow(QqSearchState(history = runCatching {
        val values = JSONArray(preferences.getString("history", "[]"))
        (0 until values.length()).map { values.getString(it) }.filter { it.isNotBlank() }.distinct().take(16)
    }.getOrDefault(emptyList())))
    val state = _state.asStateFlow()
    private var suggestionJob: Job? = null
    private val jobs = mutableMapOf<QqSearchTab, Job>()
    private var sessionRevision: Long? = null

    fun configure(revision: Long) {
        if (sessionRevision == revision) return
        sessionRevision = revision
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _state.update { it.copy(pages = emptyMap(), revision = it.revision + 1) }
        if (_state.value.full) load(QqSearchTab.ALL)
        else if (_state.value.opened) setQuery(_state.value.query)
    }

    fun deactivate() {
        suggestionJob?.cancel()
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _state.update { QqSearchState(history = it.history, revision = it.revision + 1) }
    }

    fun open() { _state.update { it.copy(opened = true) }; setQuery(_state.value.query) }
    fun close() {
        suggestionJob?.cancel()
        _state.update { it.withSearchMenuClosed() }
    }
    fun back() {
        when {
            _state.value.singer != null -> _state.update { it.copy(singer = null) }
            _state.value.full -> { _state.update { it.copy(full = false) }; setQuery(_state.value.query) }
            else -> close()
        }
    }
    fun singer(value: QqSearchSinger) { _state.update { it.copy(singer = value) } }
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
                val suggestions = repository.suggest(query)
                _state.update { if (it.query == query) it.copy(suggestions = suggestions, suggesting = false) else it }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                _state.update { if (it.query == query) it.copy(suggesting = false, suggestionError = error.asUserMessage()) else it }
            }
        }
    }
    fun submit(query: String = _state.value.query) {
        val word = query.trim()
        if (word.isEmpty()) return
        suggestionJob?.cancel()
        val history = updatedSearchHistory(_state.value.history, word)
        preferences.edit().putString("history", JSONArray(history).toString()).apply()
        val changed = word != _state.value.submitted
        if (changed) { jobs.values.forEach { it.cancel() }; jobs.clear() }
        _state.update { it.copy(query = word, submitted = word, full = true, opened = true,
            suggesting = false, history = history, pages = if (changed) emptyMap() else it.pages,
            revision = if (changed) it.revision + 1 else it.revision) }
        load(QqSearchTab.ALL)
    }
    fun load(tab: QqSearchTab, more: Boolean = false, retry: Boolean = false) {
        val snapshot = _state.value
        if (snapshot.submitted.isBlank() || jobs[tab]?.isActive == true) return
        val old = snapshot.pages[tab] ?: QqSearchPage()
        if (more && !old.hasMore || !more && !retry && old.page > 0) return
        val page = if (more) old.page + 1 else 1
        jobs[tab] = viewModelScope.launch {
            _state.update { it.copy(pages = it.pages + (tab to old.copy(loading = true, error = null))) }
            try {
                val result = repository.search(snapshot.submitted, tab, page)
                if (_state.value.revision == snapshot.revision) _state.update {
                    it.copy(pages = it.pages + (tab to if (more) old.append(result) else result))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (_state.value.revision == snapshot.revision) _state.update {
                    it.copy(pages = it.pages + (tab to old.copy(loading = false, error = error.asUserMessage())))
                }
            }
        }
    }
}
