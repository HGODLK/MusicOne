package com.musicone.demo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class MusicArchivePreview(val folder: File, val data: JSONObject, val containsAudio: Boolean) {
    val songs get() = data.optJSONArray("songs")?.length() ?: 0
    val playlists get() = data.optJSONArray("playlists")?.length() ?: 0
}

/** 归档只携带音乐库资料和用户选择的本地文件，不打包登录态与播放缓存。 */
internal class MusicLibraryArchive(private val context: Context, private val store: MusicLibraryStore) {
    suspend fun export(uri: Uri, includeAudio: Boolean) = withContext(Dispatchers.IO) {
        val staged = File(context.cacheDir, "msco-export-${UUID.randomUUID()}.zip")
        try {
            store.refresh()
            val localTracks = store.state.value.tracks.filter { it.source == MusicSource.LOCAL }
            val data = JSONObject()
            val db = store.database()
            db.inTransaction {
                listOf("songs", "playlists", "members", "folders", "locations").forEach { table ->
                    val rows = JSONArray()
                    db.rawQuery("SELECT * FROM $table", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val row = JSONObject()
                            cursor.columnNames.forEachIndexed { index, name ->
                                val value: Any = if (cursor.isNull(index)) JSONObject.NULL else when (cursor.getType(index)) {
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                    else -> cursor.getString(index)
                                }
                                row.put(name, value)
                            }
                            rows.put(row)
                        }
                    }
                    data.put(table, rows)
                }
            }
            val songs = data.getJSONArray("songs")
            for (i in 0 until songs.length()) {
                val row = songs.getJSONObject(i)
                val track = JSONObject(row.getString("data"))
                if (includeAudio && track.optString("source") == MusicSource.LOCAL.name) track.put("localUri", "msco:audio/${row.getString("id")}.audio")
                track.optString("artworkUrl").takeIf { it.contains(store.assets.absolutePath) }?.let { track.put("artworkUrl", archiveAsset(it)) }
                row.put("data", track.toString())
                for (column in listOf("artwork_override", "lyric_path", "default_lyric_path")) row.optString(column).takeIf { !row.isNull(column) && it.isNotBlank() }
                    ?.let { row.put(column, archiveAsset(it)) }
            }
            ZipOutputStream(staged.outputStream().buffered()).use { zip ->
                zip.entry("manifest.json", JSONObject().put("format", "msco").put("version", 1).put("audio", includeAudio).toString().byteInputStream())
                zip.entry("library.json", data.toString().byteInputStream())
                for (directory in listOf("artwork", "lyrics")) File(store.assets, directory).walkTopDown().filter(File::isFile).forEach { file ->
                    currentCoroutineContext().ensureActive()
                    file.inputStream().use { zip.entry("$directory/${file.relativeTo(File(store.assets, directory)).invariantSeparatorsPath}", it) }
                }
                if (includeAudio) for (track in localTracks) {
                    currentCoroutineContext().ensureActive()
                    context.contentResolver.openInputStream(Uri.parse(track.localUri))?.use { zip.entry("audio/${track.id}.audio", it) }
                        ?: error("无法读取「${track.title}」，导出未完成")
                }
            }
            context.contentResolver.openOutputStream(uri, "wt")?.use { output -> staged.inputStream().use { copy(it, output) } } ?: error("无法写入导出文件")
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw error
        } finally { staged.delete() }
    }

    suspend fun preview(uri: Uri): MusicArchivePreview = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "msco-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> ZipInputStream(input.buffered()).use { zip ->
                val names = hashSetOf<String>()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    require(names.add(entry.name)) { "归档中存在重复条目" }
                    val target = File(folder, entry.name).canonicalFile
                    require(target.path.startsWith(folder.canonicalPath + File.separator)) { "归档路径无效" }
                    if (!entry.isDirectory) { target.parentFile?.mkdirs(); target.outputStream().use { copy(zip, it) } }
                    zip.closeEntry()
                }
            } } ?: error("无法读取归档")
            val manifest = JSONObject(File(folder, "manifest.json").readText())
            require(manifest.optString("format") == "msco" && manifest.optInt("version") == 1) { "不支持此归档版本" }
            MusicArchivePreview(folder, JSONObject(File(folder, "library.json").readText()), manifest.optBoolean("audio"))
        } catch (error: Exception) { folder.deleteRecursively(); throw error }
    }

    suspend fun merge(preview: MusicArchivePreview, audioDirectory: Uri? = null) = withContext(Dispatchers.IO) {
        val imported = mutableListOf<File>()
        val external = mutableListOf<Uri>()
        val assets = mutableMapOf<String, String>()
        var committed = false
        val operation = currentCoroutineContext()
        try {
            for (directory in listOf("artwork", "lyrics", "audio")) File(preview.folder, directory).walkTopDown().filter(File::isFile).forEach { file ->
                currentCoroutineContext().ensureActive()
                val relative = "$directory/${file.relativeTo(File(preview.folder, directory)).invariantSeparatorsPath}"
                if (directory == "audio" && audioDirectory != null) {
                    val parent = DocumentsContract.buildDocumentUriUsingTree(audioDirectory, DocumentsContract.getTreeDocumentId(audioDirectory))
                    val document = DocumentsContract.createDocument(context.contentResolver, parent, "application/octet-stream", file.name) ?: error("无法创建音频文件")
                    external += document
                    context.contentResolver.openOutputStream(document)?.use { output -> file.inputStream().use { copy(it, output) } } ?: error("无法写入音频文件")
                    assets["msco:$relative"] = document.toString()
                } else {
                    val target = File(store.assets, "$directory/${UUID.randomUUID()}-${file.name}")
                    imported += target
                    target.parentFile?.mkdirs(); file.inputStream().use { input -> target.outputStream().use { copy(input, it) } }
                    assets["msco:$relative"] = if (directory == "lyrics") target.absolutePath else Uri.fromFile(target).toString()
                }
            }
            store.importTransaction(onCommitted = { committed = true }) { db ->
                val songs = preview.data.optJSONArray("songs") ?: JSONArray()
                for (i in 0 until songs.length()) {
                    operation.ensureActive()
                    val row = songs.getJSONObject(i)
                    val data = JSONObject(row.getString("data"))
                    for (key in listOf("localUri", "artworkUrl")) assets[data.optString(key)]?.let { data.put(key, it) }
                    val incoming = data.toQqStoredTrack() ?: error("归档包含无效歌曲身份")
                    val existing = db.song(incoming.id)
                    val track = when {
                        incoming.source == MusicSource.LOCAL && preview.containsAudio -> incoming.copy(
                            localUri = assets["msco:audio/${incoming.id}.audio"] ?: error("归档缺少「${incoming.title}」的音频文件"),
                            playable = true, unavailableReason = null,
                        )
                        incoming.source == MusicSource.LOCAL && existing != null && !preview.containsAudio ->
                            incoming.copy(localUri = existing.localUri, playable = existing.playable, unavailableReason = existing.unavailableReason)
                        incoming.source == MusicSource.LOCAL && existing == null && !preview.containsAudio ->
                            incoming.copy(localUri = "", playable = false, unavailableReason = "尚未在此设备关联音频文件")
                        else -> incoming
                    }
                    db.upsertSong(track)
                    if (existing == null) {
                        db.update("songs", ContentValues().apply {
                            for (column in listOf("title_override", "artist_override", "artwork_override", "lyric_path", "default_lyric_path")) {
                                if (!row.isNull(column)) put(column, assets[row.optString(column)] ?: row.optString(column))
                            }
                            put("lyric_offset", row.optLong("lyric_offset"))
                        }, "id=?", arrayOf(track.id))
                    } else {
                        assets[row.optString("default_lyric_path")]?.let { path ->
                            db.update("songs", ContentValues().apply { put("default_lyric_path", path) }, "id=?", arrayOf(track.id))
                        }
                    }
                }
                val lists = preview.data.optJSONArray("playlists") ?: JSONArray()
                for (i in 0 until lists.length()) {
                    operation.ensureActive()
                    val row = lists.getJSONObject(i)
                    require(row.getString("id").startsWith("library-")) { "歌单身份无效" }
                    db.insertWithOnConflict("playlists", null, row.values(), SQLiteDatabase.CONFLICT_IGNORE)
                }
                val members = preview.data.optJSONArray("members") ?: JSONArray()
                for (i in 0 until members.length()) {
                    operation.ensureActive()
                    val row = members.getJSONObject(i)
                    db.insertWithOnConflict("members", null, row.values().apply {
                        put("position", db.nextPosition(row.getString("playlist_id")))
                        if (!row.has("added_at")) put("added_at", i.toLong() + 1)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
                val folders = preview.data.optJSONArray("folders") ?: JSONArray()
                for (i in 0 until folders.length()) db.insertWithOnConflict("folders", null, folders.getJSONObject(i).values(), SQLiteDatabase.CONFLICT_IGNORE)
                val locations = preview.data.optJSONArray("locations") ?: JSONArray()
                for (i in 0 until locations.length()) {
                    operation.ensureActive()
                    val row = locations.getJSONObject(i)
                    val id = row.getString("song_id")
                    val track = db.song(id)
                    db.insertWithOnConflict("locations", null, row.values().apply {
                        if (preview.containsAudio && track?.localUri?.isNotBlank() == true) put("uri", track.localUri)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
        } catch (error: Exception) {
            if (!committed) {
                imported.forEach(File::delete)
                external.forEach { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it) } }
            }
            throw error
        } finally { preview.folder.deleteRecursively() }
    }

    private fun archiveAsset(value: String): String {
        val file = if (value.startsWith("file:")) File(Uri.parse(value).path.orEmpty()) else File(value)
        return if (file.canonicalPath.startsWith(store.assets.canonicalPath + File.separator)) "msco:${file.relativeTo(store.assets).invariantSeparatorsPath}" else value
    }
    private suspend fun ZipOutputStream.entry(name: String, input: InputStream) { putNextEntry(ZipEntry(name)); copy(input, this); closeEntry() }
    private suspend fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) { currentCoroutineContext().ensureActive(); val size = input.read(buffer); if (size < 0) break; output.write(buffer, 0, size) }
    }
}

private fun JSONObject.values() = ContentValues().also { values -> keys().forEach { key ->
    when (val value = get(key)) { JSONObject.NULL -> values.putNull(key); is Number -> values.put(key, value.toLong()); else -> values.put(key, value.toString()) }
} }
