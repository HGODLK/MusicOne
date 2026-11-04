package com.musicone.source.kugou

import com.musicone.source.sdk.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class KugouPageState(val page: MusicOnePage = MusicOnePage.HOME, val playlist: MusicPlaylist? = null,
    val playlistLoading: Boolean = false, val playlistError: String? = null, val query: String = "",
    val searchKind: SourceSearchKind = SourceSearchKind.TRACK, val searchTracks: List<MusicTrack> = emptyList(),
    val searchCollections: List<MusicPlaylist> = emptyList(), val searching: Boolean = false, val searchError: String? = null,
    val menuTrack: MusicTrack? = null, val menuSelection: List<MusicTrack> = emptyList(), val destinations: List<SourceLocalCollection>? = null,
    val onlineTargets: List<SourceCollection>? = null, val message: String? = null)

internal class KugouPageController(val owner: KugouPackageState, private val catalogService: KugouSourceCatalog,
    private val playbackService: KugouSourcePlayback) {
    val page = MutableStateFlow(KugouPageState())
    val sourcePage = page.map { value -> SourcePageState(value.page.name.lowercase(),
        value.playlist?.title.takeIf { value.page == MusicOnePage.PLAYLIST },
        value.page in setOf(MusicOnePage.HOME, MusicOnePage.MY)) }
        .stateIn(owner.scope, SharingStarted.Eagerly, SourcePageState())
    val catalog = MutableStateFlow(MusicCatalogUiState())
    val library = MutableStateFlow(KugouLibraryUiState(signedIn = owner.account() != null))
    val favorites = MutableStateFlow(KugouFavoriteUiState())
    private val jobs = mutableMapOf<String, Job>()
    private val requests = KugouRequestIdentity()
    private val history = mutableListOf<MusicOnePage>()
    private val favoriteMutex = Mutex()
    private val favoriteArtwork = KugouFavoriteArtwork()
    private val favoriteChanges = linkedMapOf<String, Pair<MusicTrack, Boolean>>()
    private var recommendationPool: Deferred<List<MusicPlaylist>>? = null
    private var recommendationAccount = -1L
    var rootPage by androidx.compose.runtime.mutableStateOf(MusicOnePage.HOME)
        private set
    private var libraryLoaded = false
    private var recommendationPage = 0
    private var navigationGeneration = 0L
    private fun run(key: String, action: suspend (current: () -> Boolean) -> Unit) {
        val token = requests.begin(key)
        jobs.remove(key)?.cancel()
        val account = owner.revision.get()
        jobs[key] = owner.scope.launch {
            fun current() = isActive && requests.current(key, token) && owner.revision.get() == account
            try { action(::current) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current()) page.update { it.copy(message = failure.asUserMessage()) } }
            finally { if (requests.finish(key, token)) finishLoading(key) }
        }
    }
    private fun finishLoading(key: String) {
        when (key) {
            "recommendations" -> catalog.update { it.copy(loadingRecommendations = false) }
            "tracks" -> catalog.update { it.copy(loadingRecommendedTracks = false) }
            "library" -> library.update { it.copy(loading = false) }
            "search" -> page.update { it.copy(searching = false) }
            "playlist" -> page.update { it.copy(playlistLoading = false) }
        }
    }
    private fun cancelRequest(key: String) {
        if (requests.cancel(key)) finishLoading(key)
        jobs.remove(key)?.cancel()
    }
    fun start() { refreshHome(); refreshLibrary() }
    private fun recommendationPool(refresh: Boolean = false): Deferred<List<MusicPlaylist>> {
        val account = owner.revision.get()
        if (refresh || recommendationPool == null || recommendationAccount != account || recommendationPool?.isCancelled == true) {
            recommendationAccount = account
            recommendationPool = owner.scope.async { owner.request { owner.api.recommendedPlaylistPool(it.cookie) } }
        }
        return requireNotNull(recommendationPool)
    }
    fun refreshHome() {
        val request = owner.capture()
        cancelRequest("recommendations")
        cancelRequest("tracks")
        recommendationPool?.cancel()
        val pool = recommendationPool(refresh = true)
        run("recommendations") { current ->
            catalog.update { it.copy(loadingRecommendations = true, recommendationMessage = null) }
            try {
                val result = recommendationWindow(pool.await(), 8, recommendationPage)
                if (current()) catalog.update { it.copy(recommendations = result.map { catalogService.remember(it, request) }, loadingRecommendations = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current()) catalog.update { it.copy(loadingRecommendations = false, recommendationMessage = failure.asUserMessage()) } }
        }
        refreshTracks(false)
    }
    fun refreshTracks(force: Boolean) {
        if (!force && (catalog.value.loadingRecommendedTracks || catalog.value.recommendedTracks.isNotEmpty())) return
        if (force) recommendationPage++
        run("tracks") { current ->
            catalog.update { it.copy(loadingRecommendedTracks = true, recommendedTracksMessage = null) }
            try {
                val pool = recommendationPool().await()
                val tracks = owner.request { owner.api.recommendedTracks(it.cookie, recommendationPage, pool) }
                if (current()) catalog.update { it.copy(recommendedTracks = tracks, loadingRecommendedTracks = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current()) catalog.update { it.copy(loadingRecommendedTracks = false, recommendedTracksMessage = failure.asUserMessage()) } }
        }
    }
    fun refreshLibrary() {
        val request = owner.capture()
        if (owner.account() == null) { library.value = KugouLibraryUiState(); favorites.value = KugouFavoriteUiState(); return }
        run("library") { current ->
            library.update { it.copy(signedIn = true, loading = true, message = null) }
            try {
                val result = owner.request { owner.api.library(it.cookie) }
                val ids = owner.request { owner.api.likedTrackIds(it.cookie) }
                if (current()) {
                    libraryLoaded = true
                    favoriteArtwork.record(result.favorite); favorites.value = KugouFavoriteUiState(ids)
                    library.value = KugouLibraryUiState(true, favoriteArtwork.present(favoriteChanges), result.created.map { catalogService.remember(it, request) }, result.collected.map { catalogService.remember(it, request) })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current()) library.update { it.copy(loading = false, message = failure.asUserMessage()) } }
        }
    }
    fun navigate(target: MusicOnePage) {
        if (page.value.page == target) return
        history.add(page.value.page); navigationGeneration++
        page.update { it.copy(page = target, message = null, menuTrack = null) }
        if (target == MusicOnePage.MY && !libraryLoaded && !library.value.loading) refreshLibrary()
    }
    fun tab(target: MusicOnePage) {
        history.clear()
        rootPage = target
        if (page.value.page != target) { navigationGeneration++; page.update { it.copy(page = target, message = null) } }
        if (target == MusicOnePage.MY && !libraryLoaded && !library.value.loading) refreshLibrary()
    }
    fun back(): Boolean {
        if (page.value.menuTrack != null) { page.update { it.copy(menuTrack = null, destinations = null, onlineTargets = null) }; return true }
        val previous = history.removeLastOrNull() ?: return false
        navigationGeneration++; cancelRequest("search"); cancelRequest("playlist")
        page.update { it.copy(page = previous, message = null) }
        return true
    }
    fun open(playlist: MusicPlaylist, loaded: Boolean = false) {
        val request = owner.capture()
        owner.environment.host.contentUi?.let { shared ->
            catalogService.remember(playlist, request)
            shared.openCollection(KugouSourceMapping.collection(playlist, owner.account()?.userId)) { refreshLibrary() }
            return
        }
        catalogService.remember(playlist, request); navigate(MusicOnePage.PLAYLIST)
        page.update { it.copy(playlist = playlist, playlistLoading = !loaded, playlistError = null) }
        if (loaded) return
        val generation = navigationGeneration
        run("playlist") { current ->
            try {
                val result = owner.request { owner.api.playlistDetail(playlist, it.cookie) }
                if (current() && generation == navigationGeneration) page.update { it.copy(playlist = catalogService.remember(result, request), playlistLoading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current() && generation == navigationGeneration) page.update { it.copy(playlistLoading = false, playlistError = failure.asUserMessage()) } }
        }
    }
    fun search(query: String = page.value.query, kind: SourceSearchKind = page.value.searchKind) {
        val request = owner.capture()
        page.update { it.copy(query = query, searchKind = kind, searching = true, searchError = null) }
        val generation = navigationGeneration
        run("search") { current ->
            try {
                val result = owner.request { if (kind == SourceSearchKind.TRACK) owner.api.search(query, it.cookie) to emptyList()
                    else emptyList<MusicTrack>() to owner.api.searchCollections(query, it.cookie).filter { list -> list.remoteId().startsWith("album:") == (kind == SourceSearchKind.ALBUM) } }
                if (current() && generation == navigationGeneration) page.update { it.copy(searchTracks = result.first, searchCollections = result.second.map { catalogService.remember(it, request) }, searching = false) }
                if (query.isNotBlank()) owner.preferences.edit().putString("kugou_search_history", (listOf(query) + searchHistory()).distinct().take(30).joinToString("\n")).apply()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current() && generation == navigationGeneration) page.update { it.copy(searching = false, searchError = failure.asUserMessage()) } }
        }
    }
    fun searchHistory(): List<String> = owner.preferences.getString("kugou_search_history", "").orEmpty().lineSequence().filter(String::isNotBlank).toList()
    fun clearSearchHistory() { owner.preferences.edit().remove("kugou_search_history").apply() }
    fun play(track: MusicTrack) { owner.environment.host.playDetailed(listOf(KugouSourceMapping.track(track, owner.account()?.userId))) }
    fun playTracks(tracks: List<MusicTrack>) { owner.environment.host.playDetailed(tracks.map { KugouSourceMapping.track(it, owner.account()?.userId) }) }
    fun play(playlist: MusicPlaylist) {
        if (playlist.tracks.isNotEmpty()) owner.environment.host.playDetailed(playlist.tracks.map { KugouSourceMapping.track(it, owner.account()?.userId) })
        else run("play") { current -> val loaded = owner.request { owner.api.playlistDetail(playlist, it.cookie) }; if (current()) play(loaded) }
    }
    fun favorite(track: MusicTrack) = setFavorite(track, track.id !in favorites.value.ids)
    fun setFavorite(track: MusicTrack, liked: Boolean) {
        if (owner.account() == null) { navigate(MusicOnePage.LOGIN); return }
        val previous = favoriteChanges[track.id]
        favoriteChanges[track.id] = track to liked
        favorites.update { it.copy(ids = if (liked) it.ids + track.id else it.ids - track.id, message = null) }
        library.update { it.copy(favoritePlaylist = favoriteArtwork.present(favoriteChanges)) }
        owner.scope.launch {
            favoriteMutex.withLock {
                if (favoriteChanges[track.id] != track to liked) return@withLock
                try { owner.request { owner.api.setTrackLiked(it.cookie, track, liked) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (favoriteChanges[track.id] == track to liked) {
                        if (previous == null) favoriteChanges.remove(track.id) else favoriteChanges[track.id] = previous
                        favorites.update { it.copy(ids = if (liked) it.ids - track.id else it.ids + track.id, message = failure.asUserMessage()) }
                        library.update { it.copy(favoritePlaylist = favoriteArtwork.present(favoriteChanges)) }
                    }
                }
            }
        }
    }
    fun showMenu(track: MusicTrack) { page.update { it.copy(menuTrack = track, menuSelection = listOf(track)) } }
    fun showSelection(tracks: List<MusicTrack>) { if (tracks.isNotEmpty()) page.update { it.copy(menuTrack = tracks.first(), menuSelection = tracks) } }
    fun dismissMenu() { page.update { it.copy(menuTrack = null, menuSelection = emptyList(), destinations = null, onlineTargets = null) } }
    fun chooseLocalTargets() = run("targets") { current ->
        val targets = owner.environment.host.library?.destinations() ?: error("宿主未提供资料库导入能力")
        if (current()) page.update { it.copy(destinations = targets.filter(SourceLocalCollection::acceptsManualTracks)) }
    }
    fun chooseOnlineTargets() = run("targets") { current -> val targets = catalogService.writableCollections(); if (current()) page.update { it.copy(onlineTargets = targets) } }
    fun importLocal(target: SourceLocalCollection) { val track = page.value.menuTrack ?: return
        val selection = page.value.menuSelection.ifEmpty { listOf(track) }
        run("import") { _ -> owner.environment.host.library?.importTracks(selection.map { KugouSourceMapping.track(it, owner.account()?.userId) }, target.id)
            ?: error("宿主未提供资料库导入能力"); dismissMenu(); page.update { it.copy(message = "已添加到${target.title}") } } }
    fun importOnline(target: SourceCollection) { val track = page.value.menuTrack ?: return
        val selection = page.value.menuSelection.ifEmpty { listOf(track) }
        run("import") { _ -> catalogService.changeTracks(target, selection.map { KugouSourceMapping.track(it, owner.account()?.userId) }, true); dismissMenu(); page.update { it.copy(message = "已添加到${target.title}") } } }
    fun removeOnline(tracks: List<MusicTrack>, playlist: MusicPlaylist) = run("edit") { current ->
        catalogService.changeTracks(KugouSourceMapping.collection(playlist, owner.account()?.userId), tracks.map { KugouSourceMapping.track(it, owner.account()?.userId) }, false)
        if (current()) open(playlist)
    }
    fun cache(tracks: List<MusicTrack>) = run("cache") { _ ->
        for (track in tracks) {
            currentCoroutineContext().ensureActive()
            val value = KugouSourceMapping.track(track, owner.account()?.userId)
            val resource = playbackService.resolve(SourcePlaybackRequest(value, automatic = true))
            owner.environment.host.cache?.download(value, resource) ?: error("宿主未提供缓存能力")
        }
        dismissMenu(); page.update { it.copy(message = "已加入缓存任务") }
    }
    fun accountChanged() {
        jobs.keys.toList().forEach(::cancelRequest)
        libraryLoaded = false
        recommendationPage = 0
        recommendationPool?.cancel()
        catalog.value = MusicCatalogUiState()
        library.value = KugouLibraryUiState(signedIn = owner.account() != null)
        favorites.value = KugouFavoriteUiState()
        favoriteChanges.clear(); favoriteArtwork.clear()
        refreshLibrary(); refreshHome()
    }
}
