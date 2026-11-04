package com.musicone.source.kugou

import com.musicone.source.sdk.SourceCursorRequest
import com.musicone.source.sdk.SourceCollection
import com.musicone.source.sdk.SourcePage
import com.musicone.source.sdk.SourceTrackDetails
import org.json.JSONObject
import java.security.MessageDigest

/** 完整快照只保留一份原始行；只转换请求页，不常驻整单 JSON 树和两套权益对象。 */
internal class KugouCollectionTrackSnapshot(
    private val account: String,
    private val listId: String,
    internal val rows: List<String>,
    private val order: IntArray,
    private val artwork: String?,
    fingerprint: String,
    private val mapper: (JSONObject, Boolean) -> MusicTrack,
) {
    val count: Int get() = rows.size
    private val scope = KugouProtocol.md5("$account:$listId:$fingerprint")

    fun latestArtwork(): String? = artwork

    fun withFavoriteArtwork(collection: SourceCollection): SourceCollection =
        if (collection.accountFavorites) collection.copy(artwork = collection.artwork.copy(url = artwork)) else collection

    fun favoriteTrackIds(check: () -> Unit = {}): Set<String> = buildSet {
        rows.forEach { encoded ->
            check()
            JSONObject(encoded).kugouFavoriteHash()?.let { add("kugou-" + it.lowercase()) }
        }
    }

    fun playlist(target: KugouManagedCollection, vip: Boolean): MusicPlaylist {
        require(target.listId == listId) { "酷狗歌曲快照不属于此歌单" }
        return target.playlist.copy(tracks = order.map { mapper(JSONObject(rows[it]), vip) }, count = count,
            artworkUrl = artwork ?: target.playlist.artworkUrl)
    }

    fun page(target: KugouManagedCollection, vip: Boolean, request: SourceCursorRequest,
        check: () -> Unit = {}): SourcePage<SourceTrackDetails> {
        require(target.listId == listId) { "酷狗歌曲快照不属于此歌单" }
        require(request.limit > 0) { "分页数量必须大于零" }
        val offset = request.cursor?.let { cursor ->
            val parts = cursor.split(':')
            require(parts.size == 3 && parts[0] == "kgct1" && parts[1] == scope) { "酷狗歌曲游标已失效，请刷新" }
            parts[2].toIntOrNull()?.takeIf { it in 0..count } ?: error("酷狗歌曲游标无效")
        } ?: 0
        val end = minOf(count.toLong(), offset.toLong() + request.limit).toInt()
        val tracks = (offset until end).map { index ->
            check()
            KugouSourceMapping.track(mapper(JSONObject(rows[order[index]]), vip), account)
        }
        check()
        return SourcePage(tracks, if (end < count) "kgct1:$scope:$end" else null)
    }
}

/** 分页接收时逐行校验及编码；摘要沿原输入格式增量计算，不拼接数兆字节的整单字符串。 */
internal class KugouCollectionSnapshotBuilder(
    private val account: String,
    private val listId: String,
    version: String?,
    private val mapper: (JSONObject, Boolean) -> MusicTrack,
    private val check: () -> Unit,
) {
    private val rows = ArrayList<String>()
    private val ids = ArrayList<Long>()
    private val digest = MessageDigest.getInstance("MD5").apply {
        update(version.orEmpty().toByteArray(Charsets.UTF_8))
        update('['.code.toByte())
    }
    private var newestId = Long.MIN_VALUE
    private var artwork: String? = null

    fun add(row: JSONObject) {
        check()
        val track = mapper(row, false)
        val id = row.optLong("ID", row.optLong("fileid"))
        if (id > newestId) { newestId = id; artwork = track.artworkUrl }
        val encoded = row.toString()
        if (rows.isNotEmpty()) digest.update(','.code.toByte())
        digest.update(JSONObject.quote(encoded).toByteArray(Charsets.UTF_8))
        rows.add(encoded)
        ids.add(id)
        check()
    }

    fun finish(): KugouCollectionTrackSnapshot {
        check()
        digest.update(']'.code.toByte())
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val order = rows.indices.sortedByDescending { ids[it] }.toIntArray()
        check()
        return KugouCollectionTrackSnapshot(account, listId, rows, order, artwork, fingerprint, mapper)
    }
}
