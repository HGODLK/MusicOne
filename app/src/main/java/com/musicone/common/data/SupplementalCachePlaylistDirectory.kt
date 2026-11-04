package com.musicone

import android.content.Context
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.SourcePackageController
import com.musicone.sourceintegration.toMusicTrack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 缓存目录只保存公开身份与不透明载荷，平台缓存和请求由音源自行管理。 */
internal class SupplementalCachePlaylistDirectory(context: Context) {
    private val controller = SourcePackageController.get(context)
    private val snapshots = CachedPlaylistStore(context)
    private fun namespace(source: MusicSource, account: String?) = "source:${source.id}:account:${account.orEmpty()}"
    private fun account(source: MusicSource) = controller.running(source.id)?.entry?.authentication?.currentAccount()
    fun signature(): List<Pair<String, String>> = controller.available(SourceKind.SUPPLEMENTAL).map {
        val account = it.entry.authentication?.currentAccount()
        it.entry.descriptor.id to "${account?.account?.id.orEmpty()}:${account?.revision ?: 0}"
    }
    fun signedIn(source: MusicSource): Boolean = controller.running(source.id)?.let {
        !it.entry.descriptor.requiresLogin || account(source)?.status == SourceSessionStatus.AUTHENTICATED
    } ?: false
    fun cached(): List<MusicPlaylist> = controller.available(SourceKind.SUPPLEMENTAL).flatMap {
        val source = MusicSource.fromId(it.entry.descriptor.id)
        if (signedIn(source)) snapshots.read(namespace(source, account(source)?.account?.id)) else emptyList()
    }
    suspend fun remote(source: MusicSource): List<MusicPlaylist> {
        val instance = controller.running(source.id) ?: return emptyList()
        val service = instance.entry.remoteCollections ?: return emptyList()
        if (!signedIn(source)) return emptyList()
        val captured = account(source)
        val folders = collectPages { cursor -> service.collections(SourceCursorRequest(cursor, policy = SourceFetchPolicy.REFRESH)) }
        // 先展示完整目录，首屏以外的收藏夹不提前请求内容。
        val result = folders.map(::sourceCacheCollectionDirectorySnapshot)
        currentCoroutineContext().ensureActive(); check(account(source) == captured) { "账号已变化，请重新打开缓存管理" }
        snapshots.save(namespace(source, captured?.account?.id), result)
        return result
    }
    suspend fun preview(playlist: MusicPlaylist): MusicPlaylist = read(playlist, complete = false)
    suspend fun load(playlist: MusicPlaylist): MusicPlaylist = read(playlist, complete = true)
    private suspend fun read(playlist: MusicPlaylist, complete: Boolean): MusicPlaylist {
        val source = playlist.source
        val captured = account(source)
        val instance = controller.running(source.id) ?: error("音源未激活，请重新导入后重启")
        val entry = instance.entry
        val service = entry.remoteCollections ?: error("音源未激活，请重新导入后重启")
        check(signedIn(source)) { "请先登录${source.label}" }
        val data = playlist.providerData
        val collection = SourceRemoteCollection(TrackIdentity(source.id,
            playlist.sourceAccountId ?: data["sourceAccountId"]?.takeIf(String::isNotBlank),
            playlist.remoteKey.ifBlank { data["sourceRemoteCollectionId"].orEmpty() }), playlist.title,
            playlist.count, SourceArtwork(playlist.artworkUrl, playlist.artworkStart, playlist.artworkEnd, playlist.artworkMark),
            SourcePayload(data["sourcePayloadFormat"].orEmpty(), data["sourcePayloadVersion"]?.toIntOrNull() ?: 1, data["sourcePayload"].orEmpty()))
        require(collection.identity.remoteId.isNotBlank()) { "请先刷新收藏夹目录" }
        if (!complete && service is SourceRemoteCollectionArtwork) {
            val artwork = service.artwork(collection, SourceFetchPolicy.REFRESH)
            currentCoroutineContext().ensureActive(); check(account(source) == captured) { "账号已变化，请重新打开缓存管理" }
            return playlist.copy(artworkUrl = artwork.url?.takeIf(String::isNotBlank) ?: playlist.artworkUrl,
                artworkStart = artwork.startArgb, artworkEnd = artwork.endArgb, artworkMark = artwork.mark)
        }
        val page = if (complete) SourcePage(collectPages { cursor -> service.contents(collection, null, SourceCursorRequest(cursor)) }, null)
            else service.contents(collection, null, SourceCursorRequest())
        currentCoroutineContext().ensureActive(); check(account(source) == captured) { "账号已变化，请重新打开缓存管理" }
        val value = if (complete && entry.supplementalPageProviderOrNull() != null) {
            val tracks = page.items.flatMap { supplementalDefaultTracks(entry, it) }.distinctBy { it.stableId }.map { it.toMusicTrack() }
            currentCoroutineContext().ensureActive()
            check(controller.running(source.id) === instance && account(source) == captured) { "音源或账号已变化，请重新打开缓存管理" }
            sourceCacheCollectionSnapshot(collection, page).copy(tracks = tracks, count = tracks.size)
        } else sourceCacheCollectionSnapshot(collection, page)
        if (!complete) return value
        val namespace = namespace(source, captured?.account?.id)
        val old = snapshots.read(namespace)
        snapshots.save(namespace, old.filterNot { it.id == value.id } + value)
        return value
    }
}

/** 缓存读取器根据公开分页信息判断完整性，封面跟随最新可用内容。 */
internal fun sourceCacheCollectionSnapshot(collection: SourceRemoteCollection, page: SourcePage<SourceContent>): MusicPlaylist {
    val tracks = page.items.mapNotNull { it.playableTrack?.toMusicTrack() }
    return collection.asSupplementalPlaylist(tracks).copy(
        count = if (page.nextCursor == null) tracks.size else maxOf(collection.count ?: 0, tracks.size + 1),
        artworkUrl = page.items.firstOrNull { it.access.playable && it.artwork.url?.isNotBlank() == true }?.artwork?.url ?: collection.artwork.url)
}

/** 未知数量保留待加载标记，不能把尚未读内容的收藏夹当作空歌单。 */
internal fun sourceCacheCollectionDirectorySnapshot(collection: SourceRemoteCollection): MusicPlaylist =
    collection.asSupplementalPlaylist().copy(count = collection.count ?: 1)
