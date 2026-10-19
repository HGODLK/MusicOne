package com.musicone.demo

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class BiliFolderDirectoryState(
    val folders: List<BiliFavFolder> = emptyList(), val loading: Boolean = false, val error: String? = null,
    val coverRevision: Int = 0,
)

/** 目录和封面独立于夹内分页，刷新目录不再取消正在浏览的内容。 */
internal class BiliFolderDirectory(private val cache: BiliFolderCacheStore, private val scope: CoroutineScope,
    private val fetchFolders: suspend (BiliAccountSession) -> List<BiliFavFolder>,
    private val fetchCover: suspend (BiliAccountSession, Long) -> String) {
    private val mutable = MutableStateFlow(BiliFolderDirectoryState())
    val state = mutable.asStateFlow()
    private var account: BiliAccountSession? = null
    private var generation = 0
    private var revision = 0
    private var directoryJob: Job? = null
    private val covers = mutableMapOf<Long, Job>()
    private val knownCovers = mutableMapOf<Long, String>()
    private val resolvedCovers = mutableSetOf<Long>()
    private val writes = Mutex()

    fun attach(session: BiliAccountSession?) {
        if (session?.mid != account?.mid) { mutable.value = BiliFolderDirectoryState(); knownCovers.clear() }
        cancel()
        account = session
        if (session != null) refresh()
    }
    fun cancel() {
        generation++
        directoryJob?.cancel(); directoryJob = null
        covers.values.forEach(Job::cancel); covers.clear()
        resolvedCovers.clear()
        mutable.update { it.copy(loading = false) }
    }
    fun refresh() {
        val session = account ?: return
        directoryJob?.cancel()
        val expected = generation
        val request = ++revision
        resolvedCovers.clear()
        fun current() = generation == expected && revision == request
        mutable.update { it.copy(loading = true, error = null, coverRevision = request) }
        directoryJob = scope.launch {
            try {
                if (mutable.value.folders.isEmpty()) {
                    val cached = withContext(Dispatchers.IO) { cache.folders(session.mid) }
                    if (!current()) return@launch
                    mutable.update { it.copy(folders = cached) }
                }
                val incoming = withContext(Dispatchers.IO) { fetchFolders(session) }
                if (!current()) return@launch
                val known = mutable.value.folders.associateBy { it.id }
                mutable.update { it.copy(folders = incoming.map { folder ->
                    folder.copy(cover = knownCovers[folder.id] ?: folder.cover.ifBlank { known[folder.id]?.cover.orEmpty() })
                }) }
                persist(session)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (current()) mutable.update { it.copy(error = failure.asUserMessage()) } }
            finally { if (current()) mutable.update { it.copy(loading = false) } }
        }
    }
    fun cover(folder: BiliFavFolder) {
        val session = account ?: return
        if (folder.id in resolvedCovers || covers[folder.id]?.isActive == true) return
        val expectedAccount = session.mid
        val expected = generation
        covers[folder.id] = scope.launch {
            try {
                val cover = withContext(Dispatchers.IO) { fetchCover(session, folder.id) }
                if (generation != expected || account?.mid != expectedAccount || cover.isBlank()) return@launch
                resolvedCovers += folder.id
                knownCovers[folder.id] = cover
                mutable.update { current -> current.copy(folders = current.folders.map {
                    if (it.id == folder.id) it.copy(cover = cover) else it
                }) }
                persist(session)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 封面失败保留原图，不阻断目录与列表。 */ }
        }
    }

    fun updateCover(owner: String, folder: Long, cover: String?) {
        val session = account?.takeIf { it.mid == owner } ?: return
        if (cover.isNullOrBlank()) return
        knownCovers[folder] = cover
        resolvedCovers += folder
        mutable.update { current -> current.copy(folders = current.folders.map {
            if (it.id == folder) it.copy(cover = cover) else it
        }) }
        scope.launch { if (account == session) persist(session) }
    }
    private suspend fun persist(session: BiliAccountSession) = writes.withLock {
        val folders = mutable.value.folders
        withContext(Dispatchers.IO) {
            try { cache.saveFolders(session.mid, folders) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }
}
