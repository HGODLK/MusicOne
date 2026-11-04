package com.musicone.source.kugou

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal data class KugouManagedCollection(val raw: JSONObject, val playlist: MusicPlaylist, val totalVersion: Long,
    val accountOwned: Boolean = playlist.isOwned) {
    val listId: String get() = raw.opt("listid").toString()
    val writable: Boolean get() = playlist.isOwned && !playlist.isPlatformFavorites && raw.optInt("is_def") == 0
    val created: Boolean get() = accountOwned && !playlist.isPlatformFavorites
    val savedPlaylist: Boolean get() = !accountOwned && !playlist.isPlatformFavorites &&
        raw.optInt("is_def") == 0 && raw.optJSONArray("authors") == null
}

/** 创建者优先于副本类型；带 authors 的账号收藏是专辑，不冒充收藏歌单。 */
internal fun kugouManagedCollection(item: JSONObject, version: Long, userId: String): KugouManagedCollection? {
    val id = item.optString("listid").takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true } ?: return null
    val owner = item.opt("list_create_userid").takeUnless { it == null || it == JSONObject.NULL }
        ?.toString().orEmpty().takeUnless { it == "0" }.orEmpty()
    val owned = if (owner.isNotBlank()) owner == userId else item.optInt("type", -1) == 0
    val title = item.optString("name").ifBlank { item.optString("specialname") }
    return KugouManagedCollection(item, MusicPlaylist("kugou-cloudlist:$id", title = title,
        subtitle = if (owned) "我创建的歌单" else "已收藏歌单", description = item.optString("intro"),
        count = item.optInt("count"), artworkMark = title.take(1),
        artworkUrl = item.optString("pic").takeIf(String::isNotBlank)?.replace("{size}", "500")
            ?.let { if (it.startsWith("//")) "https:$it" else it },
        isOwned = owned && item.optInt("is_def") != 1,
        isPlatformFavorites = owned && item.optInt("is_def") == 2), version, owned)
}

/** 协议参考 MakcRe/KuGouMusicApi 的 user_playlist、playlist_add/update/del/tracks_sort。 */
internal class KugouCollectionClient(
    private val transport: KugouCollectionTransport = KugouCloudCollectionTransport(),
    private val deleteTransport: KugouCollectionDeleteTransport = KugouCollectionDeleteTransport { request, cookie ->
        PlatformHttp.postJson(request.url, request.body, cookie, request.headers)
    },
    private val deleteKey: () -> String = {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
    },
) {
    fun library(cookie: String, checkAccount: () -> Unit = {}): List<KugouManagedCollection> {
        val session = cookie.requiredKugouSession()
        val rows = collectKugouCollectionPages(100, { it: Pair<JSONObject, Long> -> it.first.optString("listid") }) { page, size ->
            checkAccount()
            val response = post(session, "/v7/get_all_list", JSONObject().put("userid", session.userId)
                .put("token", session.token).put("total_ver", 979).put("type", 2)
                .put("page", page).put("pagesize", size), mapOf("plat" to "1"))
            val data = response.optJSONObject("data") ?: error("酷狗未返回歌单目录")
            checkAccount()
            data.requiredInfo().map { it to data.optLong("total_ver") }
        }
        return rows.mapNotNull { (item, version) -> kugouManagedCollection(item, version, session.userId) }
    }

    fun create(cookie: String, title: String, beforeWrite: () -> Unit = {}): String? {
        val session = cookie.requiredKugouSession()
        val body = JSONObject().put("userid", session.userId).put("token", session.token)
            .put("total_ver", 0).put("name", title).put("type", 0).put("source", 1).put("is_pri", 0)
            .put("list_create_gid", "").put("from_shupinmv", 0)
        val result = post(session, "/cloudlist.service/v5/add_list", body, mapOf("last_time" to now(), "last_area" to "gztx"),
            beforeWrite = beforeWrite)
        return result.optJSONObject("data")?.optString("listid")
            ?.takeIf { it.toLongOrNull()?.let { n -> n > 0 } == true }
    }

    fun edit(cookie: String, target: KugouManagedCollection, title: String, description: String, beforeWrite: () -> Unit = {}) {
        val body = kugouCollectionEditBody(target, title, description)
        post(cookie.requiredKugouSession(), "/v1/modify_list", body, encrypted = true, beforeWrite = beforeWrite)
    }

    fun delete(cookie: String, target: KugouManagedCollection, beforeWrite: () -> Unit = {}) {
        val key = deleteKey()
        val request = kugouCollectionDeleteRequest(cookie.requiredKugouSession(), target.listId, now().toLong(), key)
        beforeWrite()
        val response = deleteTransport.post(request, cookie)
        if (response.status !in 200..299) throw PlatformApiException("酷狗删除歌单请求失败（HTTP ${response.status}）")
        kugouCollectionDeleteResponse(response.bytes, key).requireKugouCollectionSuccess()
    }

    fun collect(cookie: String, title: String, creator: String, creatorList: String, gid: String, beforeWrite: () -> Unit = {}) {
        require(creator.toLongOrNull()?.let { it > 0 } == true && creatorList.toLongOrNull()?.let { it > 0 } == true) {
            "酷狗未提供此歌单的创建者和原始编号，无法收藏"
        }
        val session = cookie.requiredKugouSession()
        post(session, "/cloudlist.service/v5/add_list", JSONObject().put("userid", session.userId)
            .put("token", session.token).put("total_ver", 0).put("name", title).put("type", 1)
            .put("source", 1).put("is_pri", 0).put("list_create_userid", creator.toLong())
            .put("list_create_listid", creatorList.toLong()).put("list_create_gid", gid).put("from_shupinmv", 0),
            beforeWrite = beforeWrite)
    }

    fun origin(cookie: String, gid: String): JSONObject {
        require(gid.isNotBlank()) { "酷狗未提供公开歌单编号" }
        val session = cookie.requiredKugouSession()
        val response = transport.post(session, "/v3/get_list_info", JSONObject()
            .put("userid", session.userId).put("token", session.token)
            .put("data", JSONArray().put(JSONObject().put("global_collection_id", gid))), router = "pubsongs.kugou.com")
        response.requireKugouCollectionSuccess()
        return response.kugouPublicInfo(gid)
    }

    fun entries(cookie: String, target: KugouManagedCollection, checkAccount: () -> Unit = {}): List<JSONObject> =
        buildList { visitEntries(cookie, target, checkAccount) { add(it) } }

    fun visitEntries(cookie: String, target: KugouManagedCollection, checkAccount: () -> Unit = {},
        accept: (JSONObject) -> Unit) {
        val session = cookie.requiredKugouSession()
        visitKugouCollectionPages(300, { value: JSONObject -> value.optString("ID", value.optString("fileid")) }, { page, size ->
            checkAccount()
            val response = post(session, "/v4/get_list_all_file", JSONObject().put("listid", target.listId)
                .put("userid", session.userId).put("token", session.token).put("area_code", 1)
                .put("show_relate_goods", 1).put("pagesize", size).put("allplatform", 1)
                .put("show_cover", 1).put("type", 0).put("page", page))
            checkAccount()
            val data = response.optJSONObject("data") ?: error("酷狗未返回歌单歌曲")
            if (target.hasEmptySavedCopy(data, page)) throw KugouEmptySavedCollection()
            data.requiredInfo()
        }, { row -> checkAccount(); accept(row) })
    }

    /** 目录只需要封面时逐页找最新记录，不建立整单曲目快照或歌曲对象列表。 */
    fun latestArtwork(cookie: String, target: KugouManagedCollection, checkAccount: () -> Unit = {}): String? {
        var newest = Long.MIN_VALUE
        var artwork: String? = null
        visitEntries(cookie, target, checkAccount) { row ->
            val id = row.optLong("ID", row.optLong("fileid"))
            if (id > newest) { newest = id; artwork = row.toKugouTrack(false).artworkUrl }
        }
        return artwork
    }

    fun changeTracks(cookie: String, target: KugouManagedCollection, tracks: List<MusicTrack>, add: Boolean,
        beforeWrite: () -> Unit = {}) {
        if (tracks.isEmpty()) return
        val session = cookie.requiredKugouSession()
        val body = JSONObject().put("listid", target.listId).put("userid", session.userId)
            .put("token", session.token).put("list_ver", 0).put("type", 0)
        if (add) {
            val items = tracks.map { track ->
                val hash = track.kugouHashFor(AudioQuality.STANDARD)
                    ?: throw PlatformApiException("酷狗歌曲缺少标准档位编号，无法加入歌单")
                JSONObject().put("number", 1).put("name", "${track.artists} - ${track.title}")
                    .put("hash", hash).put("size", 0).put("sort", 0).put("timelen", track.durationMs)
                    .put("bitrate", 0).put("album_id", track.mediaId.toLongOrNull() ?: 0L)
                    .put("mixsongid", track.catalogId.toLongOrNull() ?: 0L)
            }
            post(session, "/cloudlist.service/v6/add_song", body.put("slow_upload", 1)
                .put("scene", "false;null").put("data", JSONArray(items)), mapOf("last_time" to now(), "last_area" to "gztx"),
                beforeWrite = beforeWrite)
        } else {
            val hashes = tracks.flatMap { it.qualityIds.values + it.remoteId() }.map(String::lowercase).toSet()
            // 删除编号必须来自目标歌单，不能使用其他歌单或我喜欢中的收藏项编号。
            val items = entries(cookie, target).filter { item ->
                listOf("FileHash", "hash", "Hash").any { item.optString(it).lowercase() in hashes }
            }.map { item ->
                val id = item.optString("ID").ifBlank { item.optString("fileid") }.toLongOrNull()
                    ?: throw PlatformApiException("酷狗未提供歌单歌曲编号，无法移除")
                JSONObject().put("fileid", id)
            }
            if (items.isNotEmpty()) post(session, "/v4/delete_songs", body.put("data", JSONArray(items)), beforeWrite = beforeWrite)
        }
    }

    fun reorder(cookie: String, target: KugouManagedCollection, orderedIds: List<String>, beforeWrite: () -> Unit = {}) {
        val session = cookie.requiredKugouSession()
        var version = 0L
        val entries = collectKugouCollectionPages(300, { value: JSONObject -> value.optString("ID", value.optString("fileid")) }) { page, size ->
            beforeWrite()
            val response = post(session, "/v4/get_list_all_file", JSONObject().put("listid", target.listId)
                .put("userid", session.userId).put("token", session.token).put("area_code", 1)
                .put("show_relate_goods", 1).put("pagesize", size).put("allplatform", 1)
                .put("show_cover", 1).put("type", 0).put("page", page))
            val data = response.optJSONObject("data") ?: error("酷狗未返回歌单歌曲")
            val pageVersion = data.optLong("list_ver")
            if (page > 1 && pageVersion != version) error("酷狗歌单已变化，请刷新后重新排序")
            version = pageVersion
            beforeWrite()
            // 排序只保留写入所需编号，不再额外常驻整单的完整歌曲 JSON 树。
            data.requiredInfo().map { row -> JSONObject()
                .put("ID", row.optString("ID").ifBlank { row.optString("fileid") })
                .put("hash", row.optString("FileHash").ifBlank { row.optString("hash") }.ifBlank { row.optString("Hash") }) }
        }
        val resources = kugouCollectionOrderData(entries, orderedIds)
        post(session, "/v1/modify_song_sort", JSONObject().put("listid", target.listId.toLong())
            .put("list_ver", version).put("type", 0).put("data", resources), encrypted = true, beforeWrite = beforeWrite)
    }

    private fun post(session: KugouSession, path: String, body: JSONObject,
        extra: Map<String, String> = emptyMap(), encrypted: Boolean = false, beforeWrite: (() -> Unit)? = null): JSONObject {
        // 同步读取结束后、实际写请求发送前，再校验取消状态和账号归属。
        beforeWrite?.invoke()
        return transport.post(session, path, body, extra, encrypted).also { it.requireKugouCollectionSuccess() }
    }

    private fun now() = (System.currentTimeMillis() / 1000).toString()
}

internal data class KugouCollectionDeleteRequest(val url: String, val body: String, val headers: Map<String, String>)
internal fun interface KugouCollectionDeleteTransport {
    fun post(request: KugouCollectionDeleteRequest, cookie: String): PlatformHttpResponse
}

/** 删除接口沿用旧 playlistAES：Base64 文本正文加 Android signature，不是 modify_list 的原始密文字节协议。 */
internal fun kugouCollectionDeleteRequest(session: KugouSession, listId: String, time: Long, aes: String): KugouCollectionDeleteRequest {
    val plain = JSONObject().put("listid", listId.toLong()).put("total_ver", 0).put("type", 1).toString()
    val digest = KugouProtocol.md5(aes)
    val hex = KugouLoginCrypto.encryptFixed(plain, digest.take(16), digest.takeLast(16))
    val encrypted = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    val body = kugouCollectionDeleteBase64(encrypted)
    val portrait = JSONObject().put("aes", aes).put("uid", session.userId).put("token", session.token)
    val modulus = BigInteger(
        "c40a2d0da76511f3bb1cc2bbd3afbd8bea83b4d6b05b6c13eb8920c53f1af767" +
            "9b32ba0d0edb843240ef1b836efed3ee240734c14c1399fd6594d16af22f52525" +
            "d14d72e0155c6dcc8638d4f7bb94f3a0b1f4c29f991972f2a160a25eb0a9e724" +
            "336be7f69bbd319ffab1c6dd8470b021dc434f3faba89f4a2a01b33bdbdd08b", 16)
    val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    rsa.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, BigInteger.valueOf(65537))))
    val p = rsa.doFinal(portrait.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02X".format(it.toInt() and 255) }
    val params = linkedMapOf("appid" to KugouProtocol.appId, "clientver" to KugouProtocol.clientVersion,
        "clienttime" to time.toString(), "mid" to session.mid, "dfid" to session.dfid, "uuid" to "-",
        "userid" to session.userId, "token" to session.token, "key" to KugouProtocol.loginKey(time),
        "last_area" to "gztx", "last_time" to time.toString(), "p" to p)
    return KugouCollectionDeleteRequest(KugouProtocol.signedAndroidUrl("https://gateway.kugou.com/v2/delete_list", params, body),
        body, kugouAndroidHeaders(params, KUGOU_CLOUD_LIST_ROUTER) + ("Content-Type" to "application/x-www-form-urlencoded"))
}

internal fun kugouCollectionDeleteResponse(raw: ByteArray, aes: String): JSONObject = runCatching {
    val digest = KugouProtocol.md5(aes)
    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(digest.take(16).toByteArray(), "AES"), IvParameterSpec(digest.takeLast(16).toByteArray()))
    JSONObject(cipher.doFinal(raw).toString(Charsets.UTF_8))
}.getOrElse { JSONObject(raw.toString(Charsets.UTF_8)) }

/** 保持 minSdk 23；纯字节编码也可在不依赖 Android stub 的单元测试中核对真实正文。 */
private fun kugouCollectionDeleteBase64(bytes: ByteArray): String = buildString {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    var offset = 0
    while (offset < bytes.size) {
        val remaining = bytes.size - offset
        val a = bytes[offset++].toInt() and 255
        val b = if (remaining > 1) bytes[offset++].toInt() and 255 else 0
        val c = if (remaining > 2) bytes[offset++].toInt() and 255 else 0
        append(chars[a shr 2]); append(chars[((a and 3) shl 4) or (b shr 4)])
        append(if (remaining > 1) chars[((b and 15) shl 2) or (c shr 6)] else '=')
        append(if (remaining > 2) chars[c and 63] else '=')
    }
}

internal fun kugouCollectionEditBody(target: KugouManagedCollection, title: String, description: String): JSONObject =
    JSONObject().put("listid", target.listId.toLong()).put("total_ver", target.totalVersion).put("type", 0)
        .put("name", title).put("sort", target.raw.optLong("sort")).put("tags", target.raw.optString("tags"))
        // 公共契约的默认空简介也用于仅改名，不能把旧简介无意清空。
        .put("intro", description.ifBlank { target.playlist.description })

internal fun kugouCollectionOrderData(entries: List<JSONObject>, orderedIds: List<String>): JSONArray {
    val indexed = entries.groupBy { item ->
        val hash = item.optString("FileHash").ifBlank { item.optString("hash") }.ifBlank { item.optString("Hash") }
        "kugou-${hash.lowercase()}"
    }
    require(indexed.values.none { it.size != 1 } && orderedIds.distinct().size == entries.size &&
        orderedIds.map(String::lowercase).toSet() == indexed.keys) { "排序必须包含当前歌单的全部歌曲，重复或已变化的歌单请刷新后重试" }
    return JSONArray(orderedIds.mapIndexed { index, id ->
        val entry = indexed.getValue(id.lowercase()).single()
        val fileId = entry.optString("ID").ifBlank { entry.optString("fileid") }.toLongOrNull()
            ?: throw PlatformApiException("酷狗未提供歌单歌曲编号，无法排序")
        JSONObject().put("fileid", fileId).put("sort", index + 1)
    })
}

private fun JSONObject.requiredInfo(): List<JSONObject> {
    val values = optJSONArray("info") ?: throw PlatformApiException("酷狗未返回完整歌单数据，请刷新重试")
    return (0 until values.length()).map { index -> values.optJSONObject(index)
        ?: throw PlatformApiException("酷狗歌单数据格式不完整，请刷新重试") }
}

/** 重复整页是远端分页异常，不能截断后当成完整目录交给删除、排序等写操作。 */
internal fun <T> collectKugouCollectionPages(size: Int, identity: (T) -> String,
    load: (Int, Int) -> List<T>): List<T> = buildList {
    visitKugouCollectionPages(size, identity, load) { add(it) }
}

internal fun <T> visitKugouCollectionPages(size: Int, identity: (T) -> String,
    load: (Int, Int) -> List<T>, accept: (T) -> Unit) {
    val seen = mutableSetOf<List<String>>()
    var page = 1
    while (true) {
        val items = load(page++, size)
        if (items.isEmpty()) return
        check(seen.add(items.map(identity).sorted())) { "酷狗歌单分页重复，请刷新后重试" }
        items.forEach(accept)
        if (items.size < size) return
    }
}
