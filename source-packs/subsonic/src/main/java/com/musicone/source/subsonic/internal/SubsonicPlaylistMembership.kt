package com.musicone.source.subsonic.internal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * “这首歌在不在歌单里”的本地索引，每台服务器一个文件。
 * 版本取 getPlaylists 的 `changed:songCount`：没变直接用索引，变了才整单重拉；
 * 本机写入后直接改索引并标记待定，下一次核对接受服务器的新版本。服务器不返回 changed 时每次都整单读取。
 */
internal class SubsonicPlaylistMembership(
    private val directory: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val versionTtlMs: Long = 30_000L,
) {
    data class Entry(val version: String, val ids: Set<String>)

    private val lock = Any()
    // 一次“添加到歌单”会为每个歌单各核对一次，版本列表在短时间内共用。
    private var versions: Triple<String, Long, Map<String, String>>? = null

    /** 上次联网得到的可写歌单，只读本地；没有返回 null。 */
    fun targets(serverKey: String): List<SubsonicPlaylist>? = synchronized(lock) {
        read(serverKey)?.optJSONArray("targets")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.playlist() }
        }
    }

    /** 保存可写歌单，并用同一份完整列表刷新版本、清掉已不存在歌单的索引。 */
    fun saveTargets(serverKey: String, writable: List<SubsonicPlaylist>, all: List<SubsonicPlaylist>) = synchronized(lock) {
        rememberVersions(serverKey, all)
        val root = read(serverKey) ?: JSONObject()
        root.put("targets", JSONArray(writable.map { it.toJson() }))
        val alive = all.mapTo(hashSetOf()) { it.id }
        root.optJSONObject("lists")?.let { lists -> lists.keys().asSequence().toList().filterNot(alive::contains).forEach(lists::remove) }
        write(serverKey, root)
    }

    fun cached(serverKey: String, playlistId: String): Set<String>? = synchronized(lock) { entry(serverKey, playlistId)?.ids }

    fun refresh(serverKey: String, playlistId: String, loadPlaylists: () -> List<SubsonicPlaylist>,
        loadIds: () -> Set<String>): Set<String> {
        val version = versionsFor(serverKey, loadPlaylists)[playlistId]
        synchronized(lock) {
            val entry = entry(serverKey, playlistId)
            if (version != null && entry != null) {
                if (entry.version == version) return entry.ids
                if (entry.version == PENDING_VERSION) {
                    saveEntry(serverKey, playlistId, entry.copy(version = version))
                    return entry.ids
                }
            }
        }
        val ids = loadIds()
        // 版本未知时记空版本：本地仍可先显示，下次核对仍会整单重拉。
        synchronized(lock) { saveEntry(serverKey, playlistId, Entry(version.orEmpty(), ids)) }
        return ids
    }

    /** 本机写入成功后同步索引；[add] 为 null 表示歌曲不变（改序、改名），只标记待定。 */
    fun applyWrite(serverKey: String, playlistId: String, ids: Set<String>, add: Boolean?) = synchronized(lock) {
        val entry = entry(serverKey, playlistId) ?: return@synchronized
        val updated = when (add) { true -> entry.ids + ids; false -> entry.ids - ids; null -> entry.ids }
        saveEntry(serverKey, playlistId, Entry(PENDING_VERSION, updated))
        versions = null
    }

    fun invalidate(serverKey: String, playlistId: String) = synchronized(lock) {
        val root = read(serverKey) ?: return@synchronized
        root.optJSONObject("lists")?.remove(playlistId)
        write(serverKey, root)
    }

    private fun versionsFor(serverKey: String, load: () -> List<SubsonicPlaylist>): Map<String, String> = synchronized(lock) {
        versions?.takeIf { it.first == serverKey && nowMs() - it.second < versionTtlMs }?.let { return it.third }
        // 在锁内请求：并发核对合并成一次 getPlaylists。
        rememberVersions(serverKey, load())
    }

    private fun rememberVersions(serverKey: String, all: List<SubsonicPlaylist>): Map<String, String> {
        val map = all.mapNotNull { playlist -> playlist.version()?.let { playlist.id to it } }.toMap()
        versions = Triple(serverKey, nowMs(), map)
        return map
    }

    private fun entry(serverKey: String, playlistId: String): Entry? {
        val value = read(serverKey)?.optJSONObject("lists")?.optJSONObject(playlistId) ?: return null
        val ids = value.optJSONArray("ids") ?: return null
        return Entry(value.optString("version"), (0 until ids.length()).mapTo(hashSetOf()) { ids.getString(it) })
    }

    private fun saveEntry(serverKey: String, playlistId: String, entry: Entry) {
        val root = read(serverKey) ?: JSONObject()
        val lists = root.optJSONObject("lists") ?: JSONObject().also { root.put("lists", it) }
        lists.put(playlistId, JSONObject().put("version", entry.version).put("ids", JSONArray(entry.ids.toList())))
        write(serverKey, root)
    }

    private fun file(serverKey: String): File {
        // 文件名不直接带服务器地址和用户名。
        val digest = MessageDigest.getInstance("SHA-256").digest(serverKey.toByteArray())
        return File(directory, "server-" + digest.take(12).joinToString("") { "%02x".format(it) } + ".json")
    }

    private fun read(serverKey: String): JSONObject? {
        val file = file(serverKey)
        if (!file.exists()) return null
        return runCatching { JSONObject(file.readText()) }.getOrNull()?.takeIf { it.optString("server") == serverKey }
    }

    private fun write(serverKey: String, root: JSONObject) {
        directory.mkdirs()
        val target = file(serverKey)
        val temporary = File(directory, target.name + ".tmp")
        temporary.writeText(root.put("server", serverKey).toString())
        if (!temporary.renameTo(target)) { target.delete(); check(temporary.renameTo(target)) { "歌单索引写入失败" } }
    }

    companion object { const val PENDING_VERSION = "pending" }
}

private fun SubsonicPlaylist.version(): String? = changed.takeIf(String::isNotBlank)?.let { "$it:${songCount ?: -1}" }
