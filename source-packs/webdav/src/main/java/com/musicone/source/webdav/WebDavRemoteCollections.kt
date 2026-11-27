package com.musicone.source.webdav

import com.musicone.source.sdk.*
import com.musicone.source.webdav.internal.*
import org.json.JSONObject

/**
 * WebDAV 书签（远端集合）、导入与订阅服务：
 * - 书签文件夹映射为 SourceRemoteCollection，宿主订阅刷新通过 collections() 重新定位；
 * - contents() 列出书签文件夹音频并支持关键词过滤，返回带 playableTrack 的 SourceContent；
 * - subscriptions.page() 完整扫描目录后按路径顺序返回，访问失败时直接抛异常保留旧列表，不返回空列表。
 */
internal class WebDavRemoteCollections(
    private val auth: WebDavAuthentication,
    private val store: WebDavStore,
    private val client: WebDavClient,
    private val metadataReader: WebDavMetadataReader,
) : SourceRemoteCollections, SourceImporting, SourceSubscriptions {

    fun bookmarksOfCurrentServer(): List<WebDavBookmark> {
        val server = auth.currentServer() ?: return emptyList()
        return store.bookmarksFor(server.key)
    }

    fun removeBookmark(bookmark: WebDavBookmark) {
        store.removeBookmarkById(bookmark.id)
    }

    fun toggleBookmark(
        path: String,
        title: String,
        includeSubfolders: Boolean,
        trackCount: Int? = null,
        coverFileUri: String? = null,
    ): Boolean {
        val server = auth.currentServer() ?: throw SourceOperationException("AUTH_REQUIRED", "请先连接 WebDAV 服务器")
        val normalized = WebDavPaths.normalizeRelativePath(path)
        if (store.isBookmarked(server.key, normalized)) {
            store.removeBookmarkByPath(server.key, normalized)
            return false
        }
        val id = WebDavIdentity.bookmarkId(server, normalized, includeSubfolders)
        store.upsertBookmark(
            WebDavBookmark(
                id = id,
                serverKey = server.key,
                path = normalized,
                title = title.ifBlank { WebDavPaths.fileNameOf(normalized).ifBlank { server.displayName } },
                includeSubfolders = includeSubfolders,
                trackCount = trackCount,
                coverFileUri = coverFileUri,
            ),
        )
        return true
    }

    fun bookmarkAsRemoteCollection(bookmark: WebDavBookmark): SourceRemoteCollection {
        val payloadJson = JSONObject().apply {
            put("bookmarkId", bookmark.id)
            put("serverKey", bookmark.serverKey)
            put("path", bookmark.path)
            put("includeSubfolders", bookmark.includeSubfolders)
        }.toString()
        return SourceRemoteCollection(
            identity = TrackIdentity(WEBDAV_SOURCE_ID, bookmark.serverKey, bookmark.id),
            title = bookmark.title,
            count = bookmark.trackCount,
            artwork = webDavArtwork(bookmark.coverFileUri, bookmark.title),
            payload = SourcePayload(WEBDAV_COLLECTION_PAYLOAD_FORMAT, 1, payloadJson),
        )
    }

    override suspend fun collections(request: SourceCursorRequest): SourcePage<SourceRemoteCollection> {
        val server = auth.currentServer()
            ?: throw SourceOperationException("AUTH_REQUIRED", "请先连接 WebDAV 服务器")
        if (server.expired) {
            throw SourceOperationException("AUTH_EXPIRED", "WebDAV 服务器凭据已失效，请重新连接")
        }
        val items = store.bookmarksFor(server.key).map { bookmarkAsRemoteCollection(it) }
        return SourcePage(items = items, nextCursor = null)
    }

    override suspend fun contents(
        collection: SourceRemoteCollection,
        query: String?,
        request: SourceCursorRequest,
    ): SourcePage<SourceContent> {
        val (server, bookmark) = resolveCollectionTarget(collection)
        val scanned = scanBookmarkTracks(server, bookmark)
        val keyword = query?.trim().orEmpty()
        val filtered = if (keyword.isEmpty()) {
            scanned
        } else {
            scanned.filter { track ->
                track.track.title.contains(keyword, ignoreCase = true) ||
                    track.artistsText.contains(keyword, ignoreCase = true) ||
                    track.album.contains(keyword, ignoreCase = true)
            }
        }
        val contents = filtered.map { track ->
            SourceContent(
                identity = track.track.identity,
                title = track.track.title,
                description = track.album,
                author = track.artistsText.takeIf { it.isNotBlank() }?.let {
                    SourceEntityRef(id = it, name = it, kind = "artist")
                },
                artwork = track.artwork,
                durationMs = track.durationMs,
                access = track.access,
                payload = track.payload,
                playableTrack = track,
            )
        }
        return SourcePage(items = contents, nextCursor = null)
    }

    override suspend fun tracks(
        content: SourceContent,
        selectedChildren: List<SourceChildItem>,
    ): List<SourceTrackDetails> {
        content.playableTrack?.let { return listOf(it) }
        val payload = WebDavTrackPayload.parse(content.payload)
            ?: throw SourceOperationException("TRACK_UNAVAILABLE", "内容缺少 WebDAV 文件信息")
        val server = auth.findServer(payload.serverKey)
            ?: auth.currentServer()
            ?: throw SourceOperationException("AUTH_REQUIRED", "请先连接 WebDAV 服务器")
        val entry = WebDavEntry(
            path = payload.path,
            name = payload.fileName,
            isDirectory = false,
            sizeBytes = payload.sizeBytes,
            etag = payload.etag,
            lastModified = payload.lastModified,
            contentType = payload.contentType,
        )
        val lyric = payload.lyricPath?.let { WebDavEntry(it, WebDavPaths.fileNameOf(it), false) }
        val cover = payload.coverPath?.let { WebDavEntry(it, WebDavPaths.fileNameOf(it), false) }
        val (quick, _) = metadataReader.buildQuickTrack(server, entry, lyric, cover)
        return listOf(quick)
    }

    override suspend fun page(
        collection: SourceRemoteCollection,
        request: SourceCursorRequest,
    ): SourcePage<SourceSubscriptionItem> {
        val (server, bookmark) = resolveCollectionTarget(collection)
        val tracks = scanBookmarkTracks(server, bookmark)
        val items = tracks.mapIndexed { index, track ->
            SourceSubscriptionItem(
                remoteMemberId = track.stableId,
                tracks = listOf(track),
                position = index.toLong(),
            )
        }
        return SourcePage(items = items, nextCursor = null)
    }

    private suspend fun scanBookmarkTracks(
        server: WebDavServer,
        bookmark: WebDavBookmark,
    ): List<SourceTrackDetails> {
        val scanned = try {
            client.scanAudio(server, bookmark.path, bookmark.includeSubfolders)
        } catch (error: SourceOperationException) {
            if (error.code == "AUTH_EXPIRED") auth.markExpired(server.key)
            throw error
        }

        val sorted = scanned.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.entry.path })
        var firstCoverUri: String? = null
        val dirCoverCache = HashMap<String, String?>()

        val tracks = sorted.map { item ->
            val (quick, hitCache) = metadataReader.buildQuickTrack(server, item.entry, item.lyricEntry, item.coverEntry)
            val resolved = if (hitCache) {
                quick
            } else {
                val coverKey = item.coverEntry?.path
                val folderCover = if (coverKey != null) {
                    dirCoverCache.getOrPut(coverKey) { metadataReader.ensureFolderCover(server, item.coverEntry) }
                } else {
                    null
                }
                quick.withCover(folderCover)
            }
            if (firstCoverUri == null && !resolved.artwork.url.isNullOrBlank()) {
                firstCoverUri = resolved.artwork.url
            }
            resolved
        }

        store.updateBookmarkSummary(bookmark.id, tracks.size, firstCoverUri)
        return tracks
    }

    private fun resolveCollectionTarget(collection: SourceRemoteCollection): Pair<WebDavServer, WebDavBookmark> {
        val active = auth.currentServer()
            ?: throw SourceOperationException("AUTH_REQUIRED", "请先连接 WebDAV 服务器")
        if (active.expired) {
            throw SourceOperationException("AUTH_EXPIRED", "WebDAV 服务器凭据已失效，请重新连接")
        }
        val stored = store.findBookmark(collection.identity.remoteId)
        if (stored != null && stored.serverKey == active.key) {
            return active to stored
        }
        val payload = collection.payload
        if (payload.format == WEBDAV_COLLECTION_PAYLOAD_FORMAT && payload.encoded.isNotBlank()) {
            val json = runCatching { JSONObject(payload.encoded) }.getOrNull()
            val path = json?.optString("path")
            val serverKey = json?.optString("serverKey")
            if (!path.isNullOrBlank() && serverKey == active.key) {
                val fallback = WebDavBookmark(
                    id = collection.identity.remoteId,
                    serverKey = active.key,
                    path = WebDavPaths.normalizeRelativePath(path),
                    title = collection.title,
                    includeSubfolders = json.optBoolean("includeSubfolders", true),
                )
                return active to fallback
            }
        }
        throw SourceOperationException("NOT_FOUND", "书签文件夹不存在或不属于当前服务器")
    }
}
