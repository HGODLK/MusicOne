package com.musicone.source.webdav

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.musicone.source.sdk.*
import com.musicone.source.webdav.internal.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件浏览状态，由音源入口持有：进出书签、服务器等页面后仍停在原目录、原滚动位置。
 * 动画由页面负责，这里只提供不可变快照。
 */
@Stable
internal class WebDavBrowserController(
    private val host: SourceHost,
    private val auth: WebDavAuthentication,
    private val client: WebDavClient,
    private val metadataReader: WebDavMetadataReader,
    private val remoteCollections: WebDavRemoteCollections,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var view by mutableStateOf(WebDavDirectoryView())
        private set
    var showBookmarks by mutableStateOf(false)
        private set
    var includeSubfolders by mutableStateOf(false)
    var sort by mutableStateOf(WebDavTrackSort.FILE)
    var busyMessage by mutableStateOf<String?>(null)
        private set
    var feedback by mutableStateOf<WebDavFeedback?>(null)
        private set
    var bookmarks by mutableStateOf<List<WebDavBookmark>>(emptyList())
        private set

    /** 已浏览目录的数量与子目录，供文件夹行副标题和平板目录树使用。 */
    val summaries = mutableStateMapOf<String, DirectorySummary>()
    val treeChildren = mutableStateMapOf<String, List<WebDavEntry>>()
    val expandedPaths = mutableStateMapOf<String, Boolean>()
    val treeLoading = mutableStateMapOf<String, Boolean>()

    private val cache = object : LinkedHashMap<String, WebDavDirectoryView>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WebDavDirectoryView>): Boolean =
            size > MAX_CACHED_DIRECTORIES
    }
    private val scrollPositions = HashMap<String, WebDavScrollPosition>()
    private var listing: WebDavDirectoryListing? = null
    private var loadJob: Job? = null
    private var metadataJob: Job? = null
    private var scanJob: Job? = null
    private var generation = 0L
    private var feedbackId = 0L
    private var busyId = 0L
    private var visiblePages = 0

    val currentBookmarked: Boolean get() = bookmarks.any { it.path == view.path }
    val canNavigateUp: Boolean get() = view.path.isNotEmpty()

    /** 当前目录没有歌曲时，播放和导入自然作用于子文件夹。 */
    val effectiveIncludeSubfolders: Boolean
        get() = view.directories.isNotEmpty() && (includeSubfolders || view.tracks.isEmpty())

    fun scrollPosition(path: String): WebDavScrollPosition = scrollPositions[path] ?: WebDavScrollPosition()

    fun saveScrollPosition(serverKey: String, path: String, position: WebDavScrollPosition) {
        if (view.serverKey == serverKey) scrollPositions[path] = position
    }

    /** 按当前账号校准：换服务器清空全部目录状态，首次进入时读取根目录。 */
    fun sync() {
        val server = auth.currentServer()
        if (server == null || server.key != view.serverKey) reset(server?.key.orEmpty())
        bookmarks = remoteCollections.bookmarksOfCurrentServer()
        if (server != null && !server.expired && !view.loading && (!view.loaded || view.error != null)) {
            val bookmarksShown = showBookmarks
            open(view.path)
            showBookmarks = bookmarksShown
        }
    }

    fun onShown() {
        visiblePages++
        if (visiblePages == 1 && metadataJob?.isActive != true) startEnrich()
    }

    /** 页面不可见时停止读标签和“扫描后播放”，避免后台占流量或突然换歌；导入不受影响。 */
    fun onHidden() {
        visiblePages--
        if (visiblePages > 0) return
        metadataJob?.cancel()
        scanJob?.cancel()
        scope.launch(Dispatchers.IO) { metadataReader.flushCache() }
    }

    fun openBookmarks() {
        bookmarks = remoteCollections.bookmarksOfCurrentServer()
        showBookmarks = true
    }

    fun closeBookmarks() {
        showBookmarks = false
    }

    fun open(path: String) {
        val server = auth.currentServer() ?: return
        val target = WebDavPaths.normalizeRelativePath(path)
        showBookmarks = false
        val gen = ++generation
        loadJob?.cancel()
        metadataJob?.cancel()
        if (view.loaded && view.serverKey == server.key) cache[view.path] = view.copy(loading = false, error = null)
        listing = null
        // 看过的目录先显示缓存再静默刷新，返回上级不闪骨架。
        view = (cache[target] ?: WebDavDirectoryView(server.key, target)).copy(loading = true, error = null)
        webDavAncestors(target).forEach { expandedPaths[it] = true }

        loadJob = scope.launch {
            try {
                val loaded = client.listDirectory(server, target)
                val cover = view.coverUri.takeIf { loaded.coverFile != null }
                val tracks = withContext(Dispatchers.IO) { quickTracks(server, loaded, cover) }
                if (gen != generation) return@launch
                listing = loaded
                treeChildren[target] = loaded.directories
                summaries[target] = DirectorySummary(loaded.directories.size, loaded.audioFiles.size)
                view = view.copy(
                    directories = loaded.directories,
                    tracks = tracks,
                    coverUri = cover,
                    loaded = true,
                    loading = false,
                    error = null,
                )
                if (visiblePages > 0) startEnrich()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (gen != generation) return@launch
                view = view.copy(loading = false, error = error.message ?: "读取文件夹失败")
                if (error is SourceOperationException && error.code == "AUTH_EXPIRED") auth.markExpired(server.key)
            }
        }
    }

    fun refresh() = open(view.path)

    fun navigateUp(): Boolean {
        if (!canNavigateUp) return false
        open(WebDavPaths.parentPath(view.path))
        return true
    }

    fun toggleTree(path: String) {
        val opening = expandedPaths[path] != true
        expandedPaths[path] = opening
        if (!opening || treeChildren.containsKey(path) || treeLoading[path] == true) return
        val server = auth.currentServer() ?: return
        treeLoading[path] = true
        scope.launch {
            try {
                val loaded = client.listDirectory(server, path)
                if (view.serverKey != server.key) return@launch
                treeChildren[path] = loaded.directories
                summaries[path] = DirectorySummary(loaded.directories.size, loaded.audioFiles.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (view.serverKey != server.key) return@launch
                expandedPaths.remove(path)
                notify(error.message ?: "展开文件夹失败", isError = true)
            } finally {
                treeLoading.remove(path)
            }
        }
    }

    fun toggleBookmark() {
        val server = auth.currentServer() ?: return
        val current = view
        val added = remoteCollections.toggleBookmark(
            path = current.path,
            title = WebDavPaths.fileNameOf(current.path).ifBlank { server.displayName },
            includeSubfolders = true,
            coverFileUri = current.coverUri
                ?: current.tracks.firstOrNull { !it.artwork.url.isNullOrBlank() }?.artwork?.url,
        )
        bookmarks = remoteCollections.bookmarksOfCurrentServer()
        notify(if (added) "已加入书签，可在书签页订阅为歌单" else "已取消书签")
    }

    fun removeBookmark(bookmark: WebDavBookmark) {
        remoteCollections.removeBookmark(bookmark)
        bookmarks = remoteCollections.bookmarksOfCurrentServer()
        notify("已移除「${bookmark.title}」")
    }

    fun collectionOf(bookmark: WebDavBookmark): SourceRemoteCollection =
        remoteCollections.bookmarkAsRemoteCollection(bookmark)

    fun playTrack(ordered: List<SourceTrackDetails>, stableId: String) {
        val index = ordered.indexOfFirst { it.stableId == stableId }
        if (index >= 0) host.playDetailed(ordered, index)
    }

    fun playFolder(ordered: List<SourceTrackDetails>, shuffle: Boolean) {
        if (!effectiveIncludeSubfolders) {
            if (ordered.isNotEmpty()) host.playDetailed(if (shuffle) ordered.shuffled() else ordered, 0)
            return
        }
        val server = auth.currentServer() ?: return
        val path = view.path
        scanJob?.cancel()
        scanJob = runBusy("正在扫描子文件夹…", "扫描文件夹失败") {
            val scanned = collectTracks(server, path)
            if (scanned.isEmpty()) notify("子文件夹里没有音频文件")
            else host.playDetailed(if (shuffle) scanned.shuffled() else scanned, 0)
        }
    }

    suspend fun importDestinations(): List<SourceLocalCollection> =
        host.library?.destinations().orEmpty().filter { it.acceptsManualTracks }

    /** destination 为空时按 newTitle 新建歌单；扫描不到歌曲时不会留下空歌单。 */
    fun importFolder(ordered: List<SourceTrackDetails>, destination: SourceLocalCollection?, newTitle: String) {
        val server = auth.currentServer() ?: return
        val library = host.library
        if (library == null) {
            notify("当前宿主未提供歌单导入", isError = true)
            return
        }
        val path = view.path
        val include = effectiveIncludeSubfolders
        runBusy(if (include) "正在扫描子文件夹并导入…" else "正在导入…", "导入歌单失败") {
            val tracks = if (include) collectTracks(server, path) else ordered
            if (tracks.isEmpty()) {
                notify("没有可导入的音频文件")
            } else {
                val target = destination ?: library.createCollection(newTitle.trim())
                library.importTracks(tracks, target.id)
                notify("已将 ${tracks.size} 首歌曲加入「${target.title}」")
            }
        }
    }

    fun dismissFeedback(id: Long) {
        if (feedback?.id == id) feedback = null
    }

    override fun close() {
        scope.cancel()
    }

    private fun reset(serverKey: String) {
        generation++
        loadJob?.cancel()
        metadataJob?.cancel()
        scanJob?.cancel()
        cache.clear()
        scrollPositions.clear()
        summaries.clear()
        treeChildren.clear()
        treeLoading.clear()
        expandedPaths.clear()
        expandedPaths[""] = true
        listing = null
        showBookmarks = false
        feedback = null
        view = WebDavDirectoryView(serverKey)
    }

    private fun notify(message: String, isError: Boolean = false) {
        feedback = WebDavFeedback(++feedbackId, message, isError)
    }

    private fun runBusy(message: String, failure: String, block: suspend () -> Unit): Job {
        val id = ++busyId
        busyMessage = message
        return scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notify(error.message ?: failure, isError = true)
            } finally {
                if (busyId == id) busyMessage = null
            }
        }
    }

    private fun quickTracks(
        server: WebDavServer,
        loaded: WebDavDirectoryListing,
        coverUri: String?,
    ): List<SourceTrackDetails> = loaded.audioFiles.map { audio ->
        metadataReader.buildQuickTrack(server, audio, loaded.lyricOf(audio), loaded.coverFile).first.withCover(coverUri)
    }

    /** 后台补目录封面和标签，按批次写回，避免每读完一首就重建整张列表。 */
    private fun startEnrich() {
        val server = auth.currentServer() ?: return
        val loaded = listing ?: return
        val gen = generation
        metadataJob?.cancel()
        metadataJob = scope.launch {
            val cover = metadataReader.ensureFolderCover(server, loaded.coverFile)
            if (gen != generation) return@launch
            if (!cover.isNullOrBlank() && cover != view.coverUri) {
                view = view.copy(coverUri = cover, tracks = view.tracks.map { it.withCover(cover) })
            }
            val pending = withContext(Dispatchers.IO) {
                loaded.audioFiles.filterNot {
                    metadataReader.buildQuickTrack(server, it, loaded.lyricOf(it), loaded.coverFile).second
                }
            }
            if (pending.isEmpty()) return@launch
            val results = ConcurrentHashMap<String, SourceTrackDetails>()
            fun flush() {
                if (gen != generation || results.isEmpty()) return
                val batch = HashMap(results)
                batch.keys.forEach { results.remove(it) }
                view = view.copy(tracks = view.tracks.map { batch[it.stableId] ?: it })
            }
            coroutineScope {
                val flusher = launch {
                    while (isActive) {
                        delay(METADATA_BATCH_MILLIS)
                        flush()
                    }
                }
                pending.map { audio ->
                    async(Dispatchers.IO) {
                        try {
                            val track = metadataReader.enrichEntry(
                                server, audio, loaded.lyricOf(audio), loaded.coverFile, cover,
                            )
                            results[track.stableId] = track
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // 单首读取失败保留文件名信息，不影响其余歌曲。
                        }
                    }
                }.awaitAll()
                flusher.cancel()
            }
            flush()
            withContext(Dispatchers.IO) { metadataReader.flushCache() }
        }
    }

    private suspend fun collectTracks(server: WebDavServer, path: String): List<SourceTrackDetails> {
        val scanned = client.scanAudio(server, path, includeSubfolders = true)
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.entry.path })
        val covers = HashMap<String, String?>()
        return withContext(Dispatchers.IO) {
            scanned.map { item ->
                val cover = item.coverEntry?.let { entry ->
                    if (entry.path !in covers) covers[entry.path] = metadataReader.ensureFolderCover(server, entry)
                    covers[entry.path]
                }
                metadataReader.buildQuickTrack(server, item.entry, item.lyricEntry, item.coverEntry).first.withCover(cover)
            }
        }
    }

    private fun WebDavDirectoryListing.lyricOf(audio: WebDavEntry): WebDavEntry? =
        lyricFilesByStem[WebDavPaths.stemOf(audio.name).lowercase()]

    private companion object {
        const val MAX_CACHED_DIRECTORIES = 40
        const val METADATA_BATCH_MILLIS = 300L
    }
}
