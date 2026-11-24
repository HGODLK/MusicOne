package com.musicone.source.webdav.internal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 包私有持久化存储：全部写入 environment.dataDirectory，按稳定 sourceId 隔离。
 * 保存多服务器配置、书签文件夹、音频标签缓存和本地封面文件，格式向后兼容。
 */
internal class WebDavStore(
    private val dataDirectory: File,
) {
    private val lock = Any()
    private val serversFile = File(dataDirectory, "servers.json")
    private val bookmarksFile = File(dataDirectory, "bookmarks.json")
    private val metadataFile = File(dataDirectory, "metadata-cache.json")
    val coversDir = File(dataDirectory, "covers")

    private var serversLoaded = false
    private var servers: MutableList<WebDavServer> = mutableListOf()
    private var activeServerKey: String? = null

    private var bookmarksLoaded = false
    private var bookmarks: MutableList<WebDavBookmark> = mutableListOf()

    private var metadataLoaded = false
    private val metadataMap = LinkedHashMap<String, WebDavTrackMeta>(256, 0.75f, true)
    private var metadataDirtyCount = 0

    // --- 服务器管理 ---

    fun allServers(): List<WebDavServer> = synchronized(lock) {
        ensureServersLoaded()
        servers.toList()
    }

    fun activeServer(): WebDavServer? = synchronized(lock) {
        ensureServersLoaded()
        val key = activeServerKey ?: return null
        servers.firstOrNull { it.key == key }
    }

    fun findServer(serverKey: String): WebDavServer? = synchronized(lock) {
        ensureServersLoaded()
        servers.firstOrNull { it.key == serverKey }
    }

    fun upsertAndSelectServer(server: WebDavServer) = synchronized(lock) {
        ensureServersLoaded()
        val idx = servers.indexOfFirst { it.key == server.key }
        if (idx >= 0) servers[idx] = server else servers.add(0, server)
        activeServerKey = server.key
        saveServersLocked()
    }

    fun selectServer(serverKey: String): WebDavServer? = synchronized(lock) {
        ensureServersLoaded()
        val found = servers.firstOrNull { it.key == serverKey } ?: return null
        activeServerKey = found.key
        saveServersLocked()
        found
    }

    fun markExpired(serverKey: String, expired: Boolean) = synchronized(lock) {
        ensureServersLoaded()
        val idx = servers.indexOfFirst { it.key == serverKey }
        if (idx >= 0 && servers[idx].expired != expired) {
            servers[idx] = servers[idx].copy(expired = expired)
            saveServersLocked()
        }
    }

    fun clearActiveServer() = synchronized(lock) {
        ensureServersLoaded()
        activeServerKey = null
        saveServersLocked()
    }

    fun removeServer(serverKey: String) = synchronized(lock) {
        ensureServersLoaded()
        servers.removeAll { it.key == serverKey }
        if (activeServerKey == serverKey) {
            activeServerKey = servers.firstOrNull()?.key
        }
        saveServersLocked()
    }

    fun exportServersJson(): String = synchronized(lock) {
        ensureServersLoaded()
        serializeServersLocked().toString()
    }

    fun importServersJsonIfEmpty(raw: String): Boolean = synchronized(lock) {
        ensureServersLoaded()
        if (servers.isNotEmpty() || raw.isBlank()) return false
        val parsed = runCatching { parseServersJson(JSONObject(raw)) }.getOrNull() ?: return false
        if (parsed.first.isEmpty()) return false
        servers.clear()
        servers.addAll(parsed.first)
        activeServerKey = parsed.second
        saveServersLocked()
        true
    }

    // --- 书签管理 ---

    fun bookmarksFor(serverKey: String): List<WebDavBookmark> = synchronized(lock) {
        ensureBookmarksLoaded()
        bookmarks.filter { it.serverKey == serverKey }
    }

    fun findBookmark(bookmarkId: String): WebDavBookmark? = synchronized(lock) {
        ensureBookmarksLoaded()
        bookmarks.firstOrNull { it.id == bookmarkId }
    }

    fun isBookmarked(serverKey: String, path: String): Boolean = synchronized(lock) {
        ensureBookmarksLoaded()
        val normalized = WebDavPaths.normalizeRelativePath(path)
        bookmarks.any { it.serverKey == serverKey && it.path == normalized }
    }

    fun upsertBookmark(bookmark: WebDavBookmark) = synchronized(lock) {
        ensureBookmarksLoaded()
        val normalized = bookmark.copy(path = WebDavPaths.normalizeRelativePath(bookmark.path))
        val idx = bookmarks.indexOfFirst { it.id == normalized.id || (it.serverKey == normalized.serverKey && it.path == normalized.path) }
        if (idx >= 0) bookmarks[idx] = normalized else bookmarks.add(0, normalized)
        saveBookmarksLocked()
    }

    fun updateBookmarkSummary(bookmarkId: String, trackCount: Int?, coverFileUri: String?) = synchronized(lock) {
        ensureBookmarksLoaded()
        val idx = bookmarks.indexOfFirst { it.id == bookmarkId }
        if (idx >= 0) {
            val old = bookmarks[idx]
            val updated = old.copy(
                trackCount = trackCount ?: old.trackCount,
                coverFileUri = coverFileUri ?: old.coverFileUri,
            )
            if (updated != old) {
                bookmarks[idx] = updated
                saveBookmarksLocked()
            }
        }
    }

    fun removeBookmarkByPath(serverKey: String, path: String): Boolean = synchronized(lock) {
        ensureBookmarksLoaded()
        val normalized = WebDavPaths.normalizeRelativePath(path)
        val removed = bookmarks.removeAll { it.serverKey == serverKey && it.path == normalized }
        if (removed) saveBookmarksLocked()
        removed
    }

    fun removeBookmarkById(bookmarkId: String): Boolean = synchronized(lock) {
        ensureBookmarksLoaded()
        val removed = bookmarks.removeAll { it.id == bookmarkId }
        if (removed) saveBookmarksLocked()
        removed
    }

    // --- 标签缓存管理（按 serverKey + path + etag + size 命中） ---

    fun getMetadata(serverKey: String, entry: WebDavEntry): WebDavTrackMeta? = synchronized(lock) {
        ensureMetadataLoaded()
        val key = metaKey(serverKey, entry.path)
        val cached = metadataMap[key] ?: return null
        val etagMatch = entry.etag.isNotEmpty() && cached.etag.isNotEmpty() && cached.etag == entry.etag
        val sizeAndTimeMatch = entry.sizeBytes >= 0 && cached.sizeBytes == entry.sizeBytes &&
            (entry.lastModified.isEmpty() || cached.lastModified == entry.lastModified)
        if (!etagMatch && !sizeAndTimeMatch) return null
        // 确认缓存引用的本地封面文件依然存在
        val validArtwork = cached.artworkFileUri?.takeIf { uri ->
            runCatching { File(java.net.URI(uri)).isFile }.getOrDefault(false)
        }
        if (validArtwork != cached.artworkFileUri) {
            val fixed = cached.copy(artworkFileUri = validArtwork)
            metadataMap[key] = fixed
            return fixed
        }
        cached
    }

    fun putMetadata(serverKey: String, path: String, meta: WebDavTrackMeta, flushImmediately: Boolean = false) = synchronized(lock) {
        ensureMetadataLoaded()
        metadataMap[metaKey(serverKey, path)] = meta
        while (metadataMap.size > MAX_METADATA_ENTRIES) {
            val oldest = metadataMap.keys.firstOrNull() ?: break
            metadataMap.remove(oldest)
        }
        metadataDirtyCount++
        if (flushImmediately || metadataDirtyCount >= 8) {
            saveMetadataLocked()
            metadataDirtyCount = 0
        }
    }

    fun flushMetadata() = synchronized(lock) {
        if (metadataLoaded && metadataDirtyCount > 0) {
            saveMetadataLocked()
            metadataDirtyCount = 0
        }
    }

    fun coverFileForEmbedded(serverKey: String, path: String, etag: String, sizeBytes: Long): File {
        val hash = WebDavIdentity.sha256Hex("emb|$serverKey|$path|$etag|$sizeBytes").take(32)
        return File(coversDir, "emb_$hash.jpg")
    }

    fun coverFileForDirectory(serverKey: String, coverPath: String, etag: String, sizeBytes: Long): File {
        val hash = WebDavIdentity.sha256Hex("dir|$serverKey|$coverPath|$etag|$sizeBytes").take(32)
        val ext = WebDavPaths.extensionOf(coverPath).ifBlank { "jpg" }
        return File(coversDir, "dir_$hash.$ext")
    }

    // --- 内部序列化 ---

    private fun ensureServersLoaded() {
        if (serversLoaded) return
        serversLoaded = true
        if (!serversFile.isFile) return
        runCatching {
            val parsed = parseServersJson(JSONObject(serversFile.readText(Charsets.UTF_8)))
            servers.clear()
            servers.addAll(parsed.first)
            activeServerKey = parsed.second
        }
    }

    private fun parseServersJson(json: JSONObject): Pair<List<WebDavServer>, String?> {
        val list = mutableListOf<WebDavServer>()
        val arr = json.optJSONArray("servers") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val key = obj.optString("key")
            val baseUrl = obj.optString("baseUrl")
            if (key.isBlank() || baseUrl.isBlank()) continue
            list += WebDavServer(
                key = key,
                displayName = obj.optString("displayName").ifBlank { baseUrl },
                baseUrl = baseUrl,
                rootPath = WebDavPaths.normalizeRelativePath(obj.optString("rootPath")),
                username = obj.optString("username"),
                password = obj.optString("password"),
                expired = obj.optBoolean("expired", false),
            )
        }
        val active = json.optString("activeKey").takeIf { it.isNotBlank() }
        return list to active
    }

    private fun serializeServersLocked(): JSONObject {
        val arr = JSONArray()
        servers.forEach { s ->
            arr.put(JSONObject().apply {
                put("key", s.key)
                put("displayName", s.displayName)
                put("baseUrl", s.baseUrl)
                put("rootPath", s.rootPath)
                put("username", s.username)
                put("password", s.password)
                put("expired", s.expired)
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("activeKey", activeServerKey ?: "")
            put("servers", arr)
        }
    }

    private fun saveServersLocked() {
        writeAtomic(serversFile, serializeServersLocked().toString())
    }

    private fun ensureBookmarksLoaded() {
        if (bookmarksLoaded) return
        bookmarksLoaded = true
        if (!bookmarksFile.isFile) return
        runCatching {
            val arr = JSONObject(bookmarksFile.readText(Charsets.UTF_8)).optJSONArray("bookmarks") ?: return
            bookmarks.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val serverKey = obj.optString("serverKey")
                if (id.isBlank() || serverKey.isBlank()) continue
                bookmarks += WebDavBookmark(
                    id = id,
                    serverKey = serverKey,
                    path = WebDavPaths.normalizeRelativePath(obj.optString("path")),
                    title = obj.optString("title").ifBlank { "WebDAV 文件夹" },
                    includeSubfolders = obj.optBoolean("includeSubfolders", true),
                    trackCount = if (obj.has("trackCount") && !obj.isNull("trackCount")) obj.optInt("trackCount") else null,
                    coverFileUri = obj.optString("coverFileUri").takeIf { it.isNotBlank() },
                )
            }
        }
    }

    private fun saveBookmarksLocked() {
        val arr = JSONArray()
        bookmarks.forEach { b ->
            arr.put(JSONObject().apply {
                put("id", b.id)
                put("serverKey", b.serverKey)
                put("path", b.path)
                put("title", b.title)
                put("includeSubfolders", b.includeSubfolders)
                if (b.trackCount != null) put("trackCount", b.trackCount)
                if (!b.coverFileUri.isNullOrBlank()) put("coverFileUri", b.coverFileUri)
            })
        }
        val root = JSONObject().apply {
            put("version", 1)
            put("bookmarks", arr)
        }
        writeAtomic(bookmarksFile, root.toString())
    }

    private fun ensureMetadataLoaded() {
        if (metadataLoaded) return
        metadataLoaded = true
        if (!metadataFile.isFile) return
        runCatching {
            val root = JSONObject(metadataFile.readText(Charsets.UTF_8))
            val entries = root.optJSONObject("entries") ?: return
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val obj = entries.optJSONObject(key) ?: continue
                val title = obj.optString("title")
                if (title.isBlank()) continue
                metadataMap[key] = WebDavTrackMeta(
                    title = title,
                    artist = obj.optString("artist"),
                    album = obj.optString("album"),
                    durationMs = obj.optLong("durationMs", 0L),
                    artworkFileUri = obj.optString("artworkFileUri").takeIf { it.isNotBlank() },
                    detectedCodecMime = obj.optString("detectedCodecMime").takeIf { it.isNotBlank() },
                    etag = obj.optString("etag"),
                    sizeBytes = obj.optLong("sizeBytes", -1L),
                    lastModified = obj.optString("lastModified"),
                )
            }
        }
    }

    private fun saveMetadataLocked() {
        val entries = JSONObject()
        metadataMap.forEach { (k, v) ->
            entries.put(k, JSONObject().apply {
                put("title", v.title)
                put("artist", v.artist)
                put("album", v.album)
                put("durationMs", v.durationMs)
                if (!v.artworkFileUri.isNullOrBlank()) put("artworkFileUri", v.artworkFileUri)
                if (!v.detectedCodecMime.isNullOrBlank()) put("detectedCodecMime", v.detectedCodecMime)
                if (v.etag.isNotEmpty()) put("etag", v.etag)
                if (v.sizeBytes >= 0) put("sizeBytes", v.sizeBytes)
                if (v.lastModified.isNotEmpty()) put("lastModified", v.lastModified)
            })
        }
        val root = JSONObject().apply {
            put("version", 1)
            put("entries", entries)
        }
        writeAtomic(metadataFile, root.toString())
    }

    private fun metaKey(serverKey: String, path: String): String =
        "$serverKey|${WebDavPaths.normalizeRelativePath(path)}"

    private fun writeAtomic(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        private const val MAX_METADATA_ENTRIES = 5000
    }
}
