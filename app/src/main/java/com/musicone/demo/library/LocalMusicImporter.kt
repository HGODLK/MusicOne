package com.musicone.demo

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class LocalImportResult(val added: Int = 0, val duplicates: Int = 0, val failed: List<String> = emptyList())
internal data class LocalDocument(val uri: Uri, val name: String, val size: Long, val relativePath: String, val mimeType: String? = null)

private val NON_AUDIO_NAMES = setOf(
    ".nomedia",
    ".database_uuid",
    ".msco",
    ".ds_store"
)

private val NON_AUDIO_EXTENSIONS = setOf(
    "lrc", "txt", "nomedia", "database_uuid", "msco",
    "jpg", "jpeg", "png", "webp", "gif", "bmp",
    "cue", "log", "nfo", "ini", "xml", "json", "db", "md",
    "zip", "rar", "7z", "tar", "gz"
)

private val SUPPORTED_AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "wav", "aac", "m4a", "ogg", "oga", "opus",
    "ape", "wv", "dsf", "dff", "alac", "aiff", "aif", "mp4"
)

internal fun isLocalAudioCandidate(name: String, mimeType: String? = null): Boolean {
    val lower = name.lowercase().trim()
    if (lower in NON_AUDIO_NAMES) return false
    val ext = lower.substringAfterLast('.', "")
    if (ext in NON_AUDIO_EXTENSIONS) return false
    if (ext in SUPPORTED_AUDIO_EXTENSIONS) return true
    val mime = mimeType?.lowercase()?.trim()
    if (mime != null && (mime.startsWith("audio/") || mime == "application/ogg")) return true
    return false
}

/** 选择器授权只作用于原文件；复制导入的音频由音乐库独立管理。 */
internal class LocalMusicImporter(private val context: Context, private val store: MusicLibraryStore) {
    fun persistAccess(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    suspend fun files(uris: List<Uri>, copy: Boolean, progress: (String) -> Unit): LocalImportResult = withContext(Dispatchers.IO) {
        if (!copy) uris.forEach(::persistAccess)
        import(uris.map(::document), null, copy, progress)
    }

    suspend fun directory(uri: Uri, copy: Boolean, progress: (String) -> Unit, relink: LibraryFolder? = null): LocalImportResult = withContext(Dispatchers.IO) {
        persistAccess(uri)
        val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val folder = LibraryFolder(relink?.id ?: "folder-${UUID.randomUUID()}", uri.toString(), document(root).name, copy)
        val documents = mutableListOf<LocalDocument>()
        enumerate(uri, DocumentsContract.getTreeDocumentId(uri), "", documents, progress)
        store.saveFolder(folder)
        import(documents, folder, copy, progress)
    }

    suspend fun refresh(folder: LibraryFolder, progress: (String) -> Unit) = directory(Uri.parse(folder.uri), folder.copyFiles, progress, folder)

    private suspend fun enumerate(tree: Uri, parent: String, relative: String, result: MutableList<LocalDocument>, progress: (String) -> Unit) {
        currentCoroutineContext().ensureActive()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id = cursor.getString(0)
                val name = cursor.getString(1).orEmpty()
                val mime = cursor.getString(2)
                val path = if (relative.isEmpty()) name else "$relative/$name"
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) enumerate(tree, id, path, result, progress)
                else result += LocalDocument(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, cursor.getLong(3), path, mime)
                progress("正在扫描：$path")
            }
        } ?: error("目录访问已失效，请重新关联")
    }

    private suspend fun import(documents: List<LocalDocument>, folder: LibraryFolder?, copy: Boolean, progress: (String) -> Unit): LocalImportResult {
        var added = 0
        var duplicates = 0
        val failures = mutableListOf<String>()
        val previous = folder?.let { store.locations(it.id) }.orEmpty()
        val found = hashSetOf<String>()
        val audio = File(store.assets, "audio").apply { mkdirs() }
        val artwork = File(store.assets, "artwork").apply { mkdirs() }
        val lyrics = File(store.assets, "lyrics").apply { mkdirs() }
        val sidecars = documents.filter { it.name.endsWith(".lrc", true) }.associateBy { it.relativePath.substringBeforeLast('.') }
        for (item in documents.filter { isLocalAudioCandidate(it.name, it.mimeType) }) {
            currentCoroutineContext().ensureActive()
            progress("正在读取：${item.name}")
            var copied: File? = null
            var extractedCover: File? = null
            try {
                val directId = store.locationId(item.uri.toString())
                if (directId != null && previous.isEmpty()) { duplicates++; continue }
                val matches = previous.filter { it.second == item.relativePath && it.third == item.size }.distinctBy { it.first }
                if (matches.size > 1) { failures += "${item.name}：存在多个关联候选，请单独选择文件"; continue }
                val id = directId ?: matches.singleOrNull()?.first ?: "local-${UUID.randomUUID()}"
                val metadata = LocalAudioMetadataExtractor.extract(context, item.uri, item.name)
                val existing = store.database().rawQuery("SELECT data FROM songs WHERE id=?", arrayOf(id)).use {
                    if (it.moveToFirst()) org.json.JSONObject(it.getString(0)).toQqStoredTrack() else null
                }
                val location = if (copy) {
                    val destination = File(audio, "$id-${UUID.randomUUID()}.${item.name.substringAfterLast('.', "audio")}")
                    val temporary = File(audio, "$id.importing")
                    try {
                        context.contentResolver.openInputStream(item.uri)?.use { input ->
                            temporary.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    output.write(buffer, 0, count)
                                }
                                Unit
                            }
                            Unit
                        } ?: error("无法读取文件")
                        check(temporary.renameTo(destination)) { "无法保存导入副本" }
                        copied = destination
                    } finally { temporary.delete() }
                    Uri.fromFile(destination).toString()
                } else item.uri.toString()
                val cover = metadata.coverBytes?.let { bytes ->
                    File(artwork, "$id-${UUID.randomUUID()}.embedded").also { extractedCover = it; it.writeBytes(bytes) }.toURI().toString()
                }
                val track = MusicTrack(id, MusicSource.LOCAL,
                    metadata.title,
                    metadata.artist,
                    metadata.album, metadata.durationMs,
                    0xFFB5A1D4, 0xFF675785, "本", "", artworkUrl = cover ?: existing?.artworkUrl,
                    localUri = location, mediaId = metadata.formatDescription)
                store.saveTrack(track, item.uri.toString(), folder?.id, item.relativePath, item.size, onCommitted = {
                    copied = null
                    extractedCover = null
                })
                found += id
                if (directId != null || matches.isNotEmpty()) duplicates++ else added++
                var lyricFile: File? = null
                try {
                    val sidecar = sidecars[item.relativePath.substringBeforeLast('.')]
                    val embedded = if (sidecar == null) readEmbeddedLocalLyrics(context, item.uri) else null
                    if (sidecar != null || !embedded.isNullOrBlank()) {
                        val destination = File(lyrics, "$id-${UUID.randomUUID()}.default.lrc")
                        lyricFile = destination
                        if (sidecar != null) context.contentResolver.openInputStream(sidecar.uri)?.use { input -> destination.outputStream().use { input.copyTo(it) } }
                            ?: error("无法读取同名歌词")
                        else destination.writeText(embedded.orEmpty())
                        require(parseLibraryLyrics(destination.readText()).isNotEmpty()) { "未找到有效歌词时间轴" }
                        store.setDefaultLyrics(id, destination.absolutePath, onCommitted = { lyricFile = null })
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failures += "${item.name}：音频已导入，歌词未更新（${error.message ?: "读取失败"}）" }
                finally { lyricFile?.delete() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failures += "${item.name}：${error.message ?: "导入失败"}" }
            finally { copied?.delete(); extractedCover?.delete() }
        }
        if (folder != null && !copy) store.unavailable(previous.map { it.first }.filterNot { it in found })
        return LocalImportResult(added, duplicates, failures)
    }

    suspend fun relink(track: MusicTrack, uri: Uri) = withContext(Dispatchers.IO) {
        persistAccess(uri)
        val item = document(uri)
        val metadata = LocalAudioMetadataExtractor.extract(context, uri, item.name)
        require(metadata.durationMs > 0) { "未识别到有效音频" }
        val original = store.database().rawQuery("SELECT data FROM songs WHERE id=?", arrayOf(track.id)).use {
            if (it.moveToFirst()) org.json.JSONObject(it.getString(0)).toQqStoredTrack() else null
        } ?: track
        store.saveTrack(original.copy(localUri = uri.toString(), durationMs = metadata.durationMs, mediaId = metadata.formatDescription, playable = true, unavailableReason = null),
            uri.toString(), relativePath = item.relativePath, size = item.size)
    }

    private fun document(uri: Uri): LocalDocument {
        val mime = context.contentResolver.getType(uri)
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) LocalDocument(uri, it.getString(0).orEmpty().ifBlank { "音频" }, it.getLong(1), it.getString(0).orEmpty(), mime) else null
        } ?: LocalDocument(uri, uri.lastPathSegment.orEmpty(), 0, uri.lastPathSegment.orEmpty(), mime)
    }
}
