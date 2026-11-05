package com.musicone

import android.content.Context
import com.musicone.source.sdk.SourceKind
import com.musicone.source.sdk.SourceSessionStatus
import com.musicone.sourceintegration.SourcePackageController
import kotlinx.coroutines.CancellationException

internal fun cachePlaylistKey(playlist: MusicPlaylist) = "${playlist.source.name}|${playlist.id}"

internal data class CachePlaylistCleanupPlan(val playlists: List<MusicPlaylist>,
    val unresolvedSources: Set<MusicSource> = emptySet())

internal class CacheDirectoryUnconfirmedException(sources: Set<MusicSource>) : IllegalStateException(
    "无法确认${sources.joinToString("、") { it.label }}的完整歌单目录，已保留缓存，请启用音源并联网后重试")

/** 删除只能使用已经确认完整的目录；缺包、缺权限及失败都不是空歌单。 */
internal suspend fun clearOutsideConfirmedCachePlaylists(plan: CachePlaylistCleanupPlan,
    clear: suspend (Set<String>) -> Unit) {
    if (plan.unresolvedSources.isNotEmpty()) throw CacheDirectoryUnconfirmedException(plan.unresolvedSources)
    clear(plan.playlists.flatMap { it.tracks }.mapTo(hashSetOf()) { it.cacheIdentity() })
}

/** 目录按账号保存；缺包仍保留公开缓存资料，网络请求只交给正在运行的包。 */
internal class CachePlaylistDirectory(private val context: Context) {
    private val preferences = PlatformPreferences(context)
    private val snapshots = CachedPlaylistStore(context)
    private val catalog = PlatformCatalogRepository(context)
    private val library = MusicLibraryStore.get(context)
    private val supplemental = SupplementalCachePlaylistDirectory(context)
    private val controller = SourcePackageController.get(context)

    private fun storedDirectories() = snapshots.namespaces().map { it to snapshots.read(it) }
    private fun namespace(source: MusicSource): String {
        val entry = controller.running(source.id)?.entry
        return if (entry?.descriptor?.kind == SourceKind.SUPPLEMENTAL)
            "source:${source.id}:account:${entry.authentication?.currentAccount()?.account?.id.orEmpty()}"
        else preferences.readSession(source).cacheNamespace()
    }
    private fun canRefresh(source: MusicSource): Boolean {
        val entry = controller.running(source.id)?.entry ?: return false
        if (entry.descriptor.requiresLogin && entry.authentication?.currentAccount()?.status != SourceSessionStatus.AUTHENTICATED) return false
        return if (entry.descriptor.kind == SourceKind.SUPPLEMENTAL) entry.remoteCollections != null
            else entry.collections?.librarySections?.isNotEmpty() == true
    }
    fun networkSources(): List<MusicSource> = (controller.available(SourceKind.MAIN) + controller.available(SourceKind.SUPPLEMENTAL))
        .map { MusicSource.fromId(it.entry.descriptor.id) }.filter(::canRefresh)

    fun signature(): List<Pair<String, String>> = (controller.installed.value.map { MusicSource.fromId(it.id) } +
        storedDirectories().flatMap { it.second.map(MusicPlaylist::source) }).distinct().sortedBy { it.id }.map { source ->
        val entry = controller.running(source.id)?.entry
        val account = entry?.authentication?.currentAccount()
        source.id to if (entry == null) "unavailable" else
            "${entry.descriptor.versionCode}:${account?.status}:${account?.account?.id.orEmpty()}:${account?.revision ?: 0}"
    }

    fun cached(): List<MusicPlaylist> {
        val directories = storedDirectories()
        val activeNamespaces = directories.asSequence().flatMap { it.second.asSequence() }
            .map { it.source }.distinct().associateWith { source ->
                if (controller.running(source.id) == null) null else namespace(source)
            }
        val saved = directories.flatMap { (owner, playlists) -> playlists.filter { playlist ->
            activeNamespaces[playlist.source].let { it == null || owner == it }
        } }
        val savedByKey = buildMap<String, MusicPlaylist> { saved.forEach { playlist -> getOrPut(cachePlaylistKey(playlist)) { playlist } } }
        return canonicalCachePlaylists(library.state.value.playlists + saved)
            .distinctBy(::cachePlaylistKey).map { playlist ->
                val stored = savedByKey[cachePlaylistKey(playlist)]
                if (playlist.tracks.isEmpty() && stored != null) playlist.copy(tracks = stored.tracks) else playlist
            }
    }

    suspend fun remote(source: MusicSource): List<MusicPlaylist> {
        check(canRefresh(source)) { "${source.label}暂不可读取完整歌单目录，已保留缓存" }
        if (controller.running(source.id)?.entry?.descriptor?.kind == SourceKind.SUPPLEMENTAL) return supplemental.remote(source)
        val initial = signature()
        val playlists = catalog.userPlaylists(source)
        check(signature() == initial) { "账号或音源已变化，请重新打开缓存管理" }
        return canonicalCachePlaylists(playlists)
    }

    fun signedIn(source: MusicSource): Boolean = canRefresh(source)

    suspend fun preview(playlist: MusicPlaylist): MusicPlaylist? {
        if (!canRefresh(playlist.source) || controller.running(playlist.source.id)?.entry?.descriptor?.kind != SourceKind.SUPPLEMENTAL) return null
        return supplemental.preview(playlist)
    }

    suspend fun load(playlist: MusicPlaylist): MusicPlaylist {
        if (playlist.source == MusicSource.LOCAL) return library.state.value.playlists.firstOrNull { it.id == playlist.id } ?: playlist
        check(canRefresh(playlist.source)) { "${playlist.source.label}暂不可读取完整歌单目录，已保留缓存" }
        if (controller.running(playlist.source.id)?.entry?.descriptor?.kind == SourceKind.SUPPLEMENTAL) return supplemental.load(playlist)
        val detail = catalog.playlistDetail(playlist).copy(isOwned = playlist.isOwned, isPlatformFavorites = playlist.isPlatformFavorites)
        if (detail.isPrimaryCachePlaylist && detail.tracks.size >= detail.count) {
            MusicDiskCache.get(context).setOrderedFavorites(preferences.readSession(detail.source).cacheNamespace(), detail.tracks)
        }
        return detail
    }

    fun save(playlists: List<MusicPlaylist>) {
        networkSources().forEach { source ->
            snapshots.save(namespace(source), playlists.filter { it.source == source && isCacheLibraryPlaylist(it) })
        }
    }

    suspend fun cleanupPlaylists(recordedSources: Set<MusicSource>): CachePlaylistCleanupPlan {
        val initial = signature()
        val all = cached().associateBy(::cachePlaylistKey).toMutableMap()
        val sources = (all.values.map { it.source } + recordedSources + networkSources()).filter { it != MusicSource.LOCAL }.distinct()
        val unavailable = sources.filterNot(::canRefresh).toSet()
        if (unavailable.isNotEmpty()) return CachePlaylistCleanupPlan(all.values.toList(), unavailable)
        for (source in sources) {
            val fresh = try { remote(source) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return CachePlaylistCleanupPlan(all.values.toList(), setOf(source)) }
            all.entries.removeAll { it.value.source == source }
            fresh.forEach { all[cachePlaylistKey(it)] = it }
        }
        val complete = mutableListOf<MusicPlaylist>()
        for (playlist in all.values) {
            val detail = try { if (playlist.tracks.size < playlist.count) load(playlist) else playlist }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return CachePlaylistCleanupPlan(complete, setOf(playlist.source)) }
            if (detail.tracks.size < detail.count) return CachePlaylistCleanupPlan(complete, setOf(playlist.source))
            complete += detail
        }
        check(signature() == initial) { "账号或音源已变化，请重新清理" }
        return CachePlaylistCleanupPlan(complete)
    }
}
