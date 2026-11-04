package com.musicone

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import com.musicone.source.sdk.SourceCollectionSortOption

/** 歌曲、专辑和简介独立请求；一个区域失败不阻断其他区域。 */
internal class ArtistPageState(private val singer: MainSourceArtist, private val repository: MainSourceArtistRepository,
    private val scope: CoroutineScope) {
    var profile by mutableStateOf(MainSourceArtistProfile(singer.name, singer.artwork, "")); private set
    var songs by mutableStateOf(emptyList<MusicTrack>()); private set
    var albums by mutableStateOf(emptyList<MusicPlaylist>()); private set
    var songTotal by mutableIntStateOf(0); private set
    var albumTotal by mutableIntStateOf(0); private set
    var songNext by mutableStateOf<Int?>(0); private set
    var albumNext by mutableStateOf<Int?>(0); private set
    var songLoading by mutableStateOf(false); private set
    var albumLoading by mutableStateOf(false); private set
    var songError by mutableStateOf<String?>(null); private set
    val songSortOptions = repository.sortOptions
    val supportsSearch get() = repository.supportsSearch
    var songSort by mutableStateOf<SourceCollectionSortOption?>(songSortOptions.firstOrNull()); private set
    private var songJob: Job? = null
    private var songGeneration = 0
    var albumError by mutableStateOf<String?>(null); private set

    var searchSongs by mutableStateOf(emptyList<MusicTrack>()); private set
    var searchResultsQuery by mutableStateOf(""); private set
    var searchNext by mutableStateOf<Int?>(null); private set
    var searchLoading by mutableStateOf(false); private set
    var searchError by mutableStateOf<String?>(null); private set
    private var searchQuery = ""
    private var searchCustomInfo: JSONObject? = null
    private var searchJob: Job? = null
    private var searchGeneration = 0

    var playAllLoading by mutableStateOf(false); private set
    var playAllError by mutableStateOf<String?>(null); private set

    /** 播放全部失败提示由页面浮层消费；不占布局，也不会留到下一次。 */
    fun consumePlayAllError() { playAllError = null }

    fun playAll(play: (List<MusicTrack>, MusicTrack?) -> Unit) {
        if (playAllLoading) return
        playAllLoading = true; playAllError = null
        val sort = songSort
        scope.launch {
            try {
                val queue = withContext(Dispatchers.IO) {
                    val result = linkedMapOf<String, MusicTrack>()
                    var next: Int? = 0
                    while (next != null) {
                        ensureActive()
                        val begin = next
                        val page = repository.songs(singer.id, begin, sort)
                        page.items.forEach { track ->
                            if (track.id !in result) result[track.id] = track
                        }
                        next = page.next?.takeIf { it > begin }
                    }
                    result.values.toList()
                }
                if (queue.isNotEmpty()) play(queue, null)
                else playAllError = "暂无可播放歌曲"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { playAllError = error.asUserMessage() }
            finally { playAllLoading = false }
        }
    }

    fun start() {
        loadSongs(); loadAlbums()
        scope.launch {
            try {
                val header = withContext(Dispatchers.IO) { repository.header(singer) }
                profile = header.copy(introduction = profile.introduction)
                header.trackCount?.let { songTotal = maxOf(it, songs.size) }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 写真缺失时保留纯色背景，不能用头像替代。 */ }
        }
        scope.launch {
            try {
                val introduction = withContext(Dispatchers.IO) { repository.introduction(singer) }
                profile = profile.copy(introduction = introduction)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 简介缺失时隐藏入口，不编造资料。 */ }
        }
    }
    fun loadSongs() {
        val begin = songNext ?: return
        if (songLoading) return
        songLoading = true; songError = null
        val generation = songGeneration
        val sort = songSort
        songJob = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { repository.songs(singer.id, begin, sort) }
                if (generation != songGeneration) return@launch
                songs = ((if (begin == 0) emptyList() else songs) + page.items).distinctBy { it.id }
                // 详情可能先于或晚于歌曲返回，已知总数不能被旧页的已加载数量覆盖。
                songTotal = maxOf(songTotal, page.total); songNext = page.next
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (generation == songGeneration) songError = error.asUserMessage() }
            finally { if (generation == songGeneration) songLoading = false }
        }
    }
    fun toggleSongSort() {
        songGeneration++
        songJob?.cancel()
        if (songSortOptions.isEmpty()) return
        songSort = songSortOptions[(songSortOptions.indexOf(songSort) + 1) % songSortOptions.size]
        songNext = 0; songLoading = false
        loadSongs()
    }

    fun updateSearch(query: String) {
        val normalized = query.trim()
        searchGeneration++
        searchJob?.cancel()
        searchQuery = normalized
        searchResultsQuery = normalized
        searchSongs = emptyList()
        searchNext = null
        searchCustomInfo = null
        searchLoading = false
        searchError = null
        if (normalized.isBlank()) return
        if (!supportsSearch) return
        searchNext = 0
        searchLoading = true
        val generation = searchGeneration
        searchJob = scope.launch {
            delay(260)
            requestSearchPage(0, generation)
        }
    }

    fun loadSearchSongs() {
        val offset = searchNext ?: return
        if (searchLoading || searchQuery.isBlank()) return
        val generation = searchGeneration
        searchJob = scope.launch { requestSearchPage(offset, generation) }
    }

    private suspend fun requestSearchPage(offset: Int, generation: Int) {
        if (generation != searchGeneration) return
        searchLoading = true
        searchError = null
        try {
            val localMatches = songs.matchingPlaylistQuery(searchQuery)
            val page = withContext(Dispatchers.IO) {
                repository.searchSongs(
                    singer = singer,
                    profile = profile,
                    query = searchQuery,
                    offset = offset,
                    songTotal = songTotal,
                    sort = songSort,
                    excludedSongIds = localMatches.map { it.catalogId },
                    customInfo = searchCustomInfo,
                )
            }
            if (generation != searchGeneration) return
            searchSongs = ((if (offset == 0) emptyList() else searchSongs) + page.items)
                .distinctBy { it.id }
            searchCustomInfo = page.customInfo
            searchNext = page.nextOffset?.takeIf { it > offset }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (generation == searchGeneration) searchError = error.asUserMessage()
        } finally {
            if (generation == searchGeneration) searchLoading = false
        }
    }

    fun loadAlbums() {
        val begin = albumNext ?: return
        if (albumLoading) return
        albumLoading = true; albumError = null
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { repository.albums(singer.id, begin) }
                albums = (albums + page.items).distinctBy { it.id }; albumTotal = page.total; albumNext = page.next
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { albumError = error.asUserMessage() }
            finally { albumLoading = false }
        }
    }
}
