package com.musicone.demo

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class BiliFolderSnapshot(val videos: List<BiliVideoItem>, val page: Int, val hasMore: Boolean)

internal interface BiliFolderCacheStore {
    fun folders(account: String): List<BiliFavFolder>
    fun saveFolders(account: String, folders: List<BiliFavFolder>)
    fun content(account: String, folder: Long): BiliFolderSnapshot?
    fun saveContent(account: String, folder: Long, content: BiliFolderSnapshot)
}

/** 收藏夹按账号独立落盘，列表快照与分页游标一起保存，不缓存搜索结果。 */
internal class BiliFolderCache(context: Context) : BiliFolderCacheStore {
    private val directory = File(context.filesDir, "bili_folder_cache")
    private fun file(account: String, name: String) = AtomicFile(File(directory, "${account.filter(Char::isDigit)}-$name.json"))
    private fun read(account: String, name: String): JSONObject? = runCatching {
        JSONObject(file(account, name).openRead().bufferedReader().use { it.readText() })
    }.getOrNull()
    private fun write(account: String, name: String, json: JSONObject) {
        directory.mkdirs()
        val target = file(account, name)
        val stream = target.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(stream)
        } catch (failure: Exception) { target.failWrite(stream); throw failure }
    }
    @Synchronized override fun folders(account: String): List<BiliFavFolder> = runCatching { read(account, "folders")
        ?.optJSONArray("folders")?.searchObjects().orEmpty().map {
            BiliFavFolder(it.getLong("id"), it.optString("title"), it.optInt("count"), it.optString("cover"))
        } }.getOrDefault(emptyList())
    @Synchronized override fun saveFolders(account: String, folders: List<BiliFavFolder>) = write(account, "folders", JSONObject()
        .put("folders", JSONArray().apply { folders.forEach {
            put(JSONObject().put("id", it.id).put("title", it.title).put("count", it.mediaCount).put("cover", it.cover))
        } }))
    @Synchronized override fun content(account: String, folder: Long): BiliFolderSnapshot? = runCatching { read(account, "$folder")?.let { json ->
        BiliFolderSnapshot(json.optJSONArray("videos")?.searchObjects().orEmpty().map {
            BiliVideoItem(it.getLong("aid"), it.optString("bvid"), it.optLong("cid"), it.optString("title"),
                it.optString("cover"), it.optString("up"), it.optLong("duration"), it.optBoolean("invalid"))
        }, json.getInt("page"), json.getBoolean("more"))
    } }.getOrNull()
    @Synchronized override fun saveContent(account: String, folder: Long, content: BiliFolderSnapshot) = write(account, "$folder", JSONObject()
        .put("page", content.page).put("more", content.hasMore).put("videos", JSONArray().apply {
            content.videos.forEach { put(JSONObject().put("aid", it.aid).put("bvid", it.bvid).put("cid", it.cid)
                .put("title", it.title).put("cover", it.cover).put("up", it.upName).put("duration", it.durationSec)
                .put("invalid", it.isInvalid)) }
        }))
}
