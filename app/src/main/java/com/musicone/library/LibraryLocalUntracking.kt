package com.musicone

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.json.JSONObject
import java.io.File

/** 受影响的已跟踪目录：移除其中歌曲后降级为逐首跟踪，remaining 为保留下来的歌曲数。 */
internal data class LocalRemovalFolder(val id: String, val title: String, val remaining: Int)

/** 确认前的移除预览；只读数据库，不改动任何内容。 */
internal data class LocalRemovalPlan(
    val songIds: List<String>,
    val copied: Int,
    val folders: List<LocalRemovalFolder>,
    val all: Boolean,
) {
    val needsConfirmation get() = all || copied > 0 || folders.isNotEmpty()
}

/** 确认框正文：整单删除给总数；单首或多选按复制副本与目录降级分别说明。 */
internal fun LocalRemovalPlan.confirmTitle(): String = when {
    all -> "删除手机存储？"
    songIds.size == 1 -> "从本地音乐移除这首歌？"
    else -> "从本地音乐移除 ${songIds.size} 首歌？"
}

internal fun LocalRemovalPlan.confirmDetail(): String = buildList {
    if (all) {
        add("将移除全部 ${songIds.size} 首本地歌曲，所有歌单里的这些歌曲一并移除。")
        if (copied > 0) add("其中 $copied 首是复制到应用内导入的，应用内副本会被永久删除，无法恢复。")
        add("引用的原文件保留，所有目录关联一并解除。")
    } else {
        if (copied > 0) add((if (songIds.size == 1) "这首歌" else "其中 $copied 首") + "是复制到应用内导入的，移除后应用内副本会被永久删除，无法恢复。")
        folders.forEach { add("来自已跟踪目录「${it.title}」：移除后该目录不再自动扫描，目录里其余 ${it.remaining} 首改为逐首跟踪。") }
    }
}.joinToString("\n\n")

/** 已提交的移除结果：应用私有文件与授权须在事务提交后由调用方清理。 */
internal data class LocalUntrackResult(
    val removed: List<MusicTrack>,
    val privateFiles: List<File>,
    val releasedUris: List<String>,
)

/** 复制导入的音频位于应用音乐库目录内，删除即永久删除。 */
internal fun isLibraryPrivateFile(value: String?, assets: File): Boolean = libraryPrivateFile(value, assets) != null

internal fun libraryPrivateFile(value: String?, assets: File): File? {
    if (value.isNullOrBlank()) return null
    val path = when {
        value.startsWith("file:") -> Uri.parse(value).path
        value.startsWith("/") -> value
        else -> null
    } ?: return null
    val file = File(path)
    val root = assets.canonicalFile
    return file.canonicalFile.takeIf { it.path.startsWith(root.path + File.separator) }
}

private fun SQLiteDatabase.localSongIds(ids: Collection<String>, all: Boolean): List<String> =
    rawQuery("SELECT id FROM songs WHERE source=?", arrayOf(MusicSource.LOCAL.name)).use { rows ->
        buildList { while (rows.moveToNext()) add(rows.getString(0)) }
    }.let { local -> if (all) local else local.toHashSet().let { known -> ids.distinct().filter { it in known } } }

private fun SQLiteDatabase.localUriOf(id: String): String? =
    rawQuery("SELECT data FROM songs WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)).optString("localUri").ifBlank { null } else null
    }

/** 统计复制副本与受影响目录，供确认框展示。 */
internal fun SQLiteDatabase.localRemovalPlan(ids: Collection<String>, all: Boolean, assets: File): LocalRemovalPlan {
    val targets = localSongIds(ids, all)
    val targetSet = targets.toHashSet()
    val copied = targets.count { isLibraryPrivateFile(localUriOf(it), assets) }
    val folders = if (all) emptyList() else {
        val folderIds = targets.flatMap { id ->
            rawQuery("SELECT folder_id FROM locations WHERE song_id=? AND folder_id IS NOT NULL", arrayOf(id)).use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            }
        }.distinct()
        folderIds.mapNotNull { folderId ->
            val title = rawQuery("SELECT title FROM folders WHERE id=?", arrayOf(folderId)).use { if (it.moveToFirst()) it.getString(0) else null }
                ?: return@mapNotNull null
            val members = rawQuery("SELECT DISTINCT song_id FROM locations WHERE folder_id=?", arrayOf(folderId)).use { rows ->
                buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            }
            LocalRemovalFolder(folderId, title, members.count { it !in targetSet })
        }
    }
    return LocalRemovalPlan(targets, copied, folders, all)
}

/**
 * 不再跟踪本地歌曲：删除记录与所有歌单成员；受影响目录降级为逐首跟踪。
 * all 为真时清空全部本地歌曲与目录关联，并移除手机存储歌单。必须在事务内调用。
 */
internal fun SQLiteDatabase.untrackLocalSongs(ids: Collection<String>, all: Boolean, assets: File): LocalUntrackResult {
    val targets = localSongIds(ids, all)
    val removed = mutableListOf<MusicTrack>()
    val files = mutableListOf<File>()
    val uris = mutableListOf<String>()
    val folderIds = linkedSetOf<String>()
    for (id in targets) {
        song(id)?.let(removed::add)
        rawQuery("SELECT data,artwork_override,lyric_path,default_lyric_path FROM songs WHERE id=?", arrayOf(id)).use { row ->
            if (!row.moveToFirst()) return@use
            val data = JSONObject(row.getString(0))
            listOf(data.optString("localUri"), data.optString("artworkUrl"), row.getString(1), row.getString(2), row.getString(3))
                .mapNotNullTo(files) { libraryPrivateFile(it, assets) }
        }
        rawQuery("SELECT uri,folder_id FROM locations WHERE song_id=?", arrayOf(id)).use { rows ->
            while (rows.moveToNext()) {
                val folder = rows.getString(1)
                // 目录内歌曲靠目录授权读取，单首没有独立授权可释放。
                if (folder == null) uris += rows.getString(0) else folderIds += folder
            }
        }
        delete("members", "song_id=?", arrayOf(id))
        delete("locations", "song_id=?", arrayOf(id))
        delete("songs", "id=?", arrayOf(id))
    }
    if (all) {
        rawQuery("SELECT uri FROM folders", null).use { rows -> while (rows.moveToNext()) uris += rows.getString(0) }
        delete("folders", null, null)
        execSQL("UPDATE locations SET folder_id=NULL")
    } else {
        // 降级：目录不再自动扫描，其余歌曲保留并改为逐首跟踪；目录授权继续保留供它们读取。
        folderIds.forEach { folder ->
            execSQL("UPDATE locations SET folder_id=NULL WHERE folder_id=?", arrayOf(folder))
            delete("folders", "id=?", arrayOf(folder))
        }
    }
    val hasLocal = rawQuery("SELECT 1 FROM songs WHERE source=? LIMIT 1", arrayOf(MusicSource.LOCAL.name)).use { it.moveToFirst() }
    if (!hasLocal) delete("playlists", "id=?", arrayOf(PHONE_STORAGE_PLAYLIST_ID))
    return LocalUntrackResult(removed, files.distinct(), uris.distinct())
}
