package com.musicone.source.subsonic

import com.musicone.source.sdk.*
import com.musicone.source.subsonic.internal.*
import java.util.concurrent.ConcurrentHashMap

/**
 * “我的”资料库：我喜欢（星标歌曲）、自建歌单、收藏的专辑（星标专辑），
 * 以及收藏、歌单编辑与远端改序。歌单整份读取后在包内分页，保证翻页时内容一致。
 */
internal class SubsonicLibraryService(
    private val requests: SubsonicRequests,
    private val client: SubsonicClient,
    private val urls: SubsonicArtworkUrls,
) : SourceCollections, SourceFavorites, SourceCollectionFavoritePolicy, SourceCollectionEditing,
    SourceCollectionDetailsEditing, SourceCollectionRemoteOrder {

    override val librarySections = setOf(SourceLibrarySection.FAVORITE_TRACKS, SourceLibrarySection.CREATED_COLLECTIONS,
        SourceLibrarySection.SAVED_COLLECTIONS)
    override val supportsCollections = true
    override val supportedOperations = setOf(SourceCollectionOperation.CREATE, SourceCollectionOperation.EDIT,
        SourceCollectionOperation.DELETE, SourceCollectionOperation.CHANGE_TRACKS, SourceCollectionOperation.REORDER)
    override val editableFields = setOf(SourceCollectionEditField.DESCRIPTION)

    /** 翻页快照：首页按策略重读，后续页沿用同一份，避免分页之间内容错位。 */
    private val snapshots = ConcurrentHashMap<String, List<SubsonicSong>>()

    fun clear() = snapshots.clear()

    override suspend fun library(section: SourceLibrarySection, request: SourceCursorRequest): SourcePage<SourceCollection> =
        requests.current { server ->
            val values = when (section) {
                SourceLibrarySection.FAVORITE_TRACKS -> {
                    val songs = starredSongs(server)
                    listOf(starredCollection(server, songs.size, songs.firstOrNull()?.let { urls.cover(server, it.coverArt) }))
                }
                SourceLibrarySection.CREATED_COLLECTIONS -> playlists(server)
                    .filter { it.owner.isBlank() || it.owner == server.username }.map { it.collection(server, urls) }
                SourceLibrarySection.SAVED_COLLECTIONS -> client.get(server, "getStarred2").optJSONObject("starred2")
                    ?.objects("album").orEmpty().mapNotNull { it.album() }.map { it.collection(server, urls) }
            }
            pageOf(values, request)
        }

    override suspend fun detail(collection: SourceCollection): SourceCollection {
        val ref = collection.subsonicRef()
        return requests.forServer(ref.serverKey) { server ->
            when (ref.kind) {
                SubsonicCollectionKind.STARRED -> {
                    val songs = starredSongs(server)
                    starredCollection(server, songs.size, songs.firstOrNull()?.let { urls.cover(server, it.coverArt) })
                }
                SubsonicCollectionKind.ALBUM -> (client.get(server, "getAlbum", listOf("id" to ref.id)).optJSONObject("album")?.album()
                    ?: throw SubsonicApiException(70, "服务器上找不到该专辑")).collection(server, urls)
                SubsonicCollectionKind.PLAYLIST -> playlistDetail(server, ref.id).first.collection(server, urls)
            }
        }
    }

    override suspend fun tracks(collection: SourceCollection, request: SourceCursorRequest): SourcePage<SourceTrackDetails> {
        val ref = collection.subsonicRef()
        return requests.forServer(ref.serverKey) { server ->
            val key = "${server.key}|${ref.kind}|${ref.id}"
            val songs = snapshots[key]?.takeIf { request.cursor != null } ?: load(server, ref).also { snapshots[key] = it }
            val offset = request.cursor?.toIntOrNull() ?: 0
            val selected = songs.drop(offset).take(request.limit.coerceAtLeast(1))
            SourcePage(selected.map { it.details(server, urls) }, (offset + selected.size).takeIf { it < songs.size }?.toString())
        }
    }

    private fun load(server: SubsonicServer, ref: SubsonicCollectionRef): List<SubsonicSong> = when (ref.kind) {
        SubsonicCollectionKind.STARRED -> starredSongs(server)
        SubsonicCollectionKind.ALBUM -> client.get(server, "getAlbum", listOf("id" to ref.id)).optJSONObject("album")
            ?.objects("song").orEmpty().mapNotNull { it.song() }.inAlbumOrder()
        SubsonicCollectionKind.PLAYLIST -> playlistDetail(server, ref.id).second
    }

    private fun starredSongs(server: SubsonicServer): List<SubsonicSong> =
        client.get(server, "getStarred2").optJSONObject("starred2")?.objects("song").orEmpty().mapNotNull { it.song() }

    private fun playlists(server: SubsonicServer): List<SubsonicPlaylist> =
        client.get(server, "getPlaylists").optJSONObject("playlists")?.objects("playlist").orEmpty().mapNotNull { it.playlist() }

    private fun playlistDetail(server: SubsonicServer, id: String): Pair<SubsonicPlaylist, List<SubsonicSong>> {
        val value = client.get(server, "getPlaylist", listOf("id" to id)).optJSONObject("playlist")
            ?: throw SubsonicApiException(70, "服务器上找不到该歌单")
        val playlist = value.playlist() ?: throw SubsonicApiException(70, "服务器上找不到该歌单")
        return playlist to value.objects("entry").mapNotNull { it.song() }
    }

    private fun <T> pageOf(values: List<T>, request: SourceCursorRequest): SourcePage<T> {
        val offset = request.cursor?.toIntOrNull() ?: 0
        val selected = values.drop(offset).take(request.limit.coerceAtLeast(1))
        return SourcePage(selected, (offset + selected.size).takeIf { it < values.size }?.toString())
    }

    // ---- 收藏 ----

    override suspend fun favoriteTrackIds(): Set<String> = requests.current { server ->
        starredSongs(server).mapTo(hashSetOf()) { trackStableId(server.key, it.id) }
    }

    override suspend fun isTrackFavorite(track: SourceTrackDetails): Boolean = track.stableId in favoriteTrackIds()

    override suspend fun setTrackFavorite(track: SourceTrackDetails, favorite: Boolean) = setTracksFavorite(listOf(track), favorite)

    /** 按歌曲所属服务器分组，一台服务器只发一次请求。 */
    override suspend fun setTracksFavorite(tracks: List<SourceTrackDetails>, favorite: Boolean) {
        tracks.map { it.subsonicRef() }.groupBy { it.serverKey }.forEach { (serverKey, refs) ->
            requests.forServer(serverKey) { server ->
                refs.map { it.id }.distinct().chunked(WRITE_BATCH).forEach { ids ->
                    client.post(server, if (favorite) "star" else "unstar", ids.map { "id" to it })
                }
                snapshots.keys.removeAll { it.startsWith("${server.key}|${SubsonicCollectionKind.STARRED}") }
            }
        }
    }

    override fun canFavorite(collection: SourceCollection): Boolean =
        runCatching { collection.subsonicRef().kind == SubsonicCollectionKind.ALBUM }.getOrDefault(false)

    override suspend fun setCollectionFavorite(collection: SourceCollection, favorite: Boolean) {
        val ref = collection.subsonicRef()
        if (ref.kind != SubsonicCollectionKind.ALBUM) throw UnsupportedOperationException("只有专辑可以收藏")
        requests.forServer(ref.serverKey) { server ->
            client.post(server, if (favorite) "star" else "unstar", listOf("albumId" to ref.id)); Unit
        }
    }

    // ---- 歌单编辑 ----

    override suspend fun writableCollections(): List<SourceCollection> = requests.current { server ->
        playlists(server).filter { (it.owner.isBlank() || it.owner == server.username) && !it.readonly }.map { it.collection(server, urls) }
    }

    override suspend fun create(edit: SourceCollectionEdit): SourceCollection =
        createDetails(SourceCollectionDetailsEdit(edit.title, edit.description.ifBlank { null }, edit.artworkUri))

    override suspend fun createDetails(edit: SourceCollectionDetailsEdit): SourceCollection = requests.current { server ->
        val title = edit.title.trim()
        require(title.isNotEmpty()) { "请输入歌单名称" }
        require(edit.artworkUri == null) { "Subsonic 协议不支持上传歌单封面" }
        val before = playlists(server).mapTo(hashSetOf()) { it.id }
        // 1.14 起返回新歌单；旧服务器不返回时按名称和新增编号找回。
        val created = client.get(server, "createPlaylist", listOf("name" to title)).optJSONObject("playlist")?.playlist()
            ?: playlists(server).firstOrNull { it.id !in before && it.name == title }
            ?: throw SourceOperationException("COLLECTION_CREATE_UNCONFIRMED", "歌单可能已创建，请刷新列表确认")
        val description = edit.description?.trim().orEmpty()
        if (description.isNotEmpty()) {
            try { client.get(server, "updatePlaylist", listOf("playlistId" to created.id, "comment" to description)) }
            catch (_: Exception) { throw SourceOperationException("COLLECTION_CREATE_UNCONFIRMED", "歌单已创建，但简介没有保存，请重新编辑") }
        }
        created.copy(comment = description.ifEmpty { created.comment }).collection(server, urls)
    }

    override suspend fun edit(collection: SourceCollection, edit: SourceCollectionEdit): SourceCollection =
        editDetails(collection, SourceCollectionDetailsEdit(edit.title, edit.description, edit.artworkUri))

    override suspend fun editDetails(collection: SourceCollection, edit: SourceCollectionDetailsEdit): SourceCollection {
        val ref = collection.ownedPlaylist()
        return requests.forServer(ref.serverKey) { server ->
            val title = edit.title.trim()
            require(title.isNotEmpty()) { "请输入歌单名称" }
            require(edit.artworkUri == null) { "Subsonic 协议不支持上传歌单封面" }
            val (playlist, _) = playlistDetail(server, ref.id)
            requireOwned(server, playlist)
            val parameters = buildList {
                add("playlistId" to ref.id); add("name" to title)
                edit.description?.let { add("comment" to it.trim()) }
            }
            client.get(server, "updatePlaylist", parameters)
            playlist.copy(name = title, comment = edit.description?.trim() ?: playlist.comment).collection(server, urls)
        }
    }

    override suspend fun delete(collection: SourceCollection) {
        val ref = collection.ownedPlaylist()
        requests.forServer(ref.serverKey) { server ->
            requireOwned(server, playlistDetail(server, ref.id).first)
            client.get(server, "deletePlaylist", listOf("id" to ref.id))
            snapshots.remove("${server.key}|${ref.kind}|${ref.id}"); Unit
        }
    }

    /** 删除按服务器上的最新序号，同一首歌出现多次时全部移除。 */
    override suspend fun changeTracks(collection: SourceCollection, tracks: List<SourceTrackDetails>, add: Boolean) {
        val ref = collection.ownedPlaylist()
        val ids = tracks.map { it.subsonicRef() }.onEach {
            require(it.serverKey == null || it.serverKey == ref.serverKey) { "只能添加同一台服务器上的歌曲" }
        }.map { it.id }.distinct()
        if (ids.isEmpty()) return
        requests.forServer(ref.serverKey) { server ->
            val (playlist, songs) = playlistDetail(server, ref.id)
            requireOwned(server, playlist)
            if (add) ids.chunked(WRITE_BATCH).forEach { batch ->
                client.post(server, "updatePlaylist", listOf("playlistId" to ref.id) + batch.map { "songIdToAdd" to it })
            } else {
                val wanted = ids.toSet()
                val indexes = songs.withIndex().filter { it.value.id in wanted }.map { it.index }
                if (indexes.isNotEmpty()) client.post(server, "updatePlaylist",
                    listOf("playlistId" to ref.id) + indexes.map { "songIndexToRemove" to it.toString() })
            }
            snapshots.remove("${server.key}|${ref.kind}|${ref.id}"); Unit
        }
    }

    override fun supportsRemoteOrder(collection: SourceCollection): Boolean =
        runCatching { collection.subsonicRef().kind == SubsonicCollectionKind.PLAYLIST && collection.owned }.getOrDefault(false)

    override suspend fun orderedTracks(collection: SourceCollection, request: SourceCursorRequest): SourcePage<SourceTrackDetails> {
        val ref = collection.ownedPlaylist()
        return requests.forServer(ref.serverKey) { server ->
            val key = "order|${server.key}|${ref.id}"
            val songs = snapshots[key]?.takeIf { request.cursor != null } ?: playlistDetail(server, ref.id).second.also { snapshots[key] = it }
            val page = pageOf(songs, request)
            SourcePage(page.items.map { it.details(server, urls) }, page.nextCursor)
        }
    }

    /**
     * Subsonic 没有单独的改序接口，用 createPlaylist 带 playlistId 整体替换歌曲列表。
     * 写入前核对服务器上的歌曲与要保存的顺序完全一致，期间有人改过就拒绝。
     */
    override suspend fun reorder(collection: SourceCollection, orderedTrackIds: List<String>) {
        val ref = collection.ownedPlaylist()
        requests.forServer(ref.serverKey) { server ->
            val (playlist, songs) = playlistDetail(server, ref.id)
            requireOwned(server, playlist)
            val current = songs.map { trackStableId(server.key, it.id) }
            if (current.sorted() != orderedTrackIds.sorted())
                throw SourceOperationException("SOURCE_REQUEST_FAILED", "歌单在别处被修改过，请刷新后重新排序")
            val byStableId = songs.associateBy { trackStableId(server.key, it.id) }
            val ordered = orderedTrackIds.map { byStableId.getValue(it).id }
            client.post(server, "createPlaylist", listOf("playlistId" to ref.id) + ordered.map { "songId" to it })
            snapshots.remove("${server.key}|${ref.kind}|${ref.id}"); snapshots.remove("order|${server.key}|${ref.id}"); Unit
        }
    }

    private fun SourceCollection.ownedPlaylist(): SubsonicCollectionRef = subsonicRef().also {
        if (it.kind != SubsonicCollectionKind.PLAYLIST) throw UnsupportedOperationException("只能修改自建歌单")
    }

    private fun requireOwned(server: SubsonicServer, playlist: SubsonicPlaylist) {
        if (playlist.owner.isNotBlank() && playlist.owner != server.username) throw SourceOperationException("REQUEST_REJECTED", "只能修改自己创建的歌单")
        if (playlist.readonly) throw SourceOperationException("REQUEST_REJECTED", "智能歌单不能手动修改")
    }

    private companion object { const val WRITE_BATCH = 200 }
}
