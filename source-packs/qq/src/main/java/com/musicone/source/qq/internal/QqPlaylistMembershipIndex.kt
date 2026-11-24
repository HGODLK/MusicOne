package com.musicone.source.qq.internal

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 自建歌单的版本：更新时间加歌曲数，任一变化都说明歌单内容变了。 */
internal data class QqDirectoryVersion(val dirId: Long, val updateTime: Long, val songCount: Int) {
    val key: String get() = "$updateTime:$songCount"
}

/** 对照 L-1124/QQMusicApi 的 `PlaylistBaseRead/GetPlaylistByUin`，一次取回全部自建歌单的更新时间。 */
internal fun qqDirectoryVersionsRequest(cookie: String): JSONObject = JSONObject()
    .put("comm", qqPlaybackComm(cookie))
    .put("req", JSONObject().put("module", "music.musicasset.PlaylistBaseRead").put("method", "GetPlaylistByUin")
        .put("param", JSONObject().put("uin", qqCredentialAccountId(cookie))))

/** 解析失败或业务码非 0 返回 null，调用方按“版本未知”整单重拉，不能把空结果当成全部未变。 */
internal fun parseQqDirectoryVersions(response: JSONObject): Map<Long, QqDirectoryVersion>? {
    if (response.optInt("code", 0) != 0) return null
    val request = response.optJSONObject("req") ?: return null
    if (request.optInt("code", -1) != 0) return null
    val list = request.optJSONObject("data")?.optJSONArray("v_playlist") ?: return null
    return buildMap {
        for (index in 0 until list.length()) {
            val item = list.optJSONObject(index) ?: continue
            val dirId = item.optLong("dirId").takeIf { it > 0 } ?: item.optLong("dirid").takeIf { it > 0 } ?: continue
            val updated = item.optLong("updateTime")
            val songs = item.optInt("songNum", item.optInt("songnum", -1))
            if (updated <= 0 || songs < 0) continue
            put(dirId, QqDirectoryVersion(dirId, updated, songs))
        }
    }
}

/**
 * QQ 自建歌单的本地归属索引：每个账号一个文件，记录可写歌单列表和每个歌单的歌曲身份集合及版本。
 * 版本对得上就直接用本地集合；本机写入成功后先改集合并标记待确认，下次拿到服务端版本时直接接受，不再整单重拉。
 */
internal class QqPlaylistMembershipStore(private val directory: File) {
    internal data class Entry(val version: String, val ids: Set<String>)
    private class Account(val targets: List<MusicPlaylist>?, val lists: MutableMap<String, Entry>)

    private val lock = Any()
    private val loaded = HashMap<String, Account>()

    fun targets(owner: String): List<MusicPlaylist>? = synchronized(lock) { account(owner).targets }

    fun saveTargets(owner: String, targets: List<MusicPlaylist>) = synchronized(lock) {
        val current = account(owner)
        // 已不在可写列表里的歌单一并清掉索引。
        val alive = targets.mapTo(hashSetOf()) { it.id }
        current.lists.keys.retainAll(alive)
        loaded[owner] = Account(targets.map { it.copy(tracks = emptyList()) }, current.lists)
        persist(owner)
    }

    fun entry(owner: String, playlistId: String): Entry? = synchronized(lock) { account(owner).lists[playlistId] }

    fun saveEntry(owner: String, playlistId: String, entry: Entry) = synchronized(lock) {
        account(owner).lists[playlistId] = entry
        persist(owner)
    }

    /** 本机写入成功：直接增删身份，版本标为待确认。没有索引时不建，留给下次核对整单读取。 */
    fun applyWrite(owner: String, playlistId: String, identities: Set<String>, add: Boolean) = synchronized(lock) {
        val lists = account(owner).lists
        val old = lists[playlistId] ?: return@synchronized
        lists[playlistId] = Entry(PENDING_VERSION, if (add) old.ids + identities else old.ids - identities)
        persist(owner)
    }

    fun invalidate(owner: String, playlistId: String) = synchronized(lock) {
        if (account(owner).lists.remove(playlistId) != null) persist(owner)
    }

    private fun account(owner: String): Account = loaded.getOrPut(owner) { read(owner) }

    private fun file(owner: String): File {
        require(owner.isNotBlank() && owner.all(Char::isDigit)) { "QQ 账号编号无效" }
        return File(directory, "account-$owner.json")
    }

    private fun read(owner: String): Account = runCatching {
        val target = file(owner)
        if (!target.isFile) return@runCatching Account(null, hashMapOf())
        val root = JSONObject(target.readText())
        val targets = root.optJSONArray("targets")?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toStoredQqPlaylist() }
        }
        val lists = hashMapOf<String, Entry>()
        root.optJSONObject("lists")?.let { values ->
            values.keys().forEach { id ->
                val item = values.optJSONObject(id) ?: return@forEach
                val ids = item.optJSONArray("ids") ?: return@forEach
                lists[id] = Entry(item.optString("version"), (0 until ids.length()).mapTo(hashSetOf()) { ids.optString(it) })
            }
        }
        Account(targets, lists)
    }.getOrElse { Account(null, hashMapOf()) }

    private fun persist(owner: String) {
        val account = loaded[owner] ?: return
        runCatching {
            directory.mkdirs()
            val root = JSONObject()
            account.targets?.let { targets -> root.put("targets", JSONArray(targets.map { it.toStoredQqJson() })) }
            root.put("lists", JSONObject().apply {
                account.lists.forEach { (id, entry) ->
                    put(id, JSONObject().put("version", entry.version).put("ids", JSONArray(entry.ids.toList())))
                }
            })
            val target = file(owner)
            // 先写临时文件再改名，写到一半被杀也不会留下读不出来的索引。
            val temporary = File(directory, target.name + ".tmp")
            temporary.writeText(root.toString())
            if (!temporary.renameTo(target)) { target.delete(); temporary.renameTo(target) }
        }
    }

    companion object { const val PENDING_VERSION = "pending" }
}

private fun MusicPlaylist.toStoredQqJson(): JSONObject = JSONObject()
    .put("id", id).put("title", title).put("subtitle", subtitle).put("description", description).put("count", count)
    .put("artworkStart", artworkStart).put("artworkEnd", artworkEnd).put("artworkMark", artworkMark)
    .put("artworkUrl", artworkUrl ?: "").put("owned", isOwned).put("favorites", isPlatformFavorites)
    .put("providerData", JSONObject(providerData))

private fun JSONObject.toStoredQqPlaylist(): MusicPlaylist? {
    val id = optString("id").takeIf(String::isNotBlank) ?: return null
    val data = optJSONObject("providerData")?.let { json -> json.keys().asSequence().associateWith { json.optString(it) } }.orEmpty()
    return MusicPlaylist(id, MusicSource.QQ, optString("title"), optString("subtitle"), optString("description"), optInt("count"),
        optLong("artworkStart"), optLong("artworkEnd"), optString("artworkMark"), emptyList(),
        optString("artworkUrl").takeIf(String::isNotBlank), optBoolean("owned"), optBoolean("favorites"), data)
}
