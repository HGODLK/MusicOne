package com.musicone

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 本地音乐自动整理：按文件标签里的专辑、歌手新建本地歌单。
 * 只新建自己的歌单，不改动任何已有歌单；编号由分组键算出，重复整理能认出同一个歌单。
 */
internal enum class AutoPlaylistKind(val idPrefix: String, val label: String) {
    ALBUM("library-auto-album-", "专辑"),
    ARTIST("library-auto-artist-", "歌手"),
}

internal fun autoPlaylistKind(id: String): AutoPlaylistKind? =
    AutoPlaylistKind.entries.firstOrNull { id.startsWith(it.idPrefix) }

internal data class AutoPlaylistPlan(val id: String, val title: String, val kind: AutoPlaylistKind,
    val tracks: List<MusicTrack>)

/** create 为真表示本次新建；否则歌单已存在，只补入 tracks。 */
internal data class AutoPlaylistWrite(val plan: AutoPlaylistPlan, val create: Boolean, val tracks: List<MusicTrack>)

internal data class AutoOrganizeResult(val created: Int, val added: Int) {
    fun summary(): String = when {
        created == 0 && added == 0 -> "没有需要新建的歌单"
        added == 0 -> "已新建 $created 个歌单"
        created == 0 -> "已向自动歌单补充 $added 首歌曲"
        else -> "已新建 $created 个歌单，补充 $added 首歌曲"
    }
}

private const val UNKNOWN_LOCAL_ARTIST = "未知创作者"

// “&”不拆：不少组合名字里带这个符号。
private val LocalArtistSeparators =
    Regex("\\s*[、,，;；/／|｜]\\s*|[\\s(（\\[]+(?:feat\\.?|ft\\.?|featuring)\\s+", RegexOption.IGNORE_CASE)

internal fun splitLocalArtists(artists: String): List<String> =
    artists.split(LocalArtistSeparators)
        .map { it.trim().trim('(', ')', '（', '）', '[', ']').trim() }
        .filter { it.isNotBlank() && it != UNKNOWN_LOCAL_ARTIST }
        .distinctBy { it.lowercase() }

private fun autoPlaylistId(kind: AutoPlaylistKind, key: String): String =
    kind.idPrefix + MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).take(8)
        .joinToString("") { "%02x".format(it) }

private class AutoAlbumGroup(val album: String, val artist: String) {
    val tracks = mutableListOf<MusicTrack>()
}

/** 只整理本地文件；少于 minimum 首的专辑或歌手不建歌单。专辑按“专辑名＋主歌手”分组，避免不同歌手的同名专辑混在一起。 */
internal fun planAutoPlaylists(tracks: List<MusicTrack>, kind: AutoPlaylistKind, minimum: Int = 2): List<AutoPlaylistPlan> {
    val local = tracks.filter { it.source == MusicSource.LOCAL }
    if (kind == AutoPlaylistKind.ARTIST) {
        val groups = linkedMapOf<String, Pair<String, MutableList<MusicTrack>>>()
        local.forEach { track ->
            splitLocalArtists(track.artists).forEach { name ->
                groups.getOrPut(name.lowercase()) { name to mutableListOf() }.second += track
            }
        }
        return groups.filterValues { it.second.size >= minimum }
            .map { (key, group) -> AutoPlaylistPlan(autoPlaylistId(kind, key), group.first, kind, group.second) }
    }
    val groups = linkedMapOf<String, AutoAlbumGroup>()
    local.forEach { track ->
        val album = track.album.trim()
        if (album.isBlank()) return@forEach
        val artist = splitLocalArtists(track.artists).firstOrNull().orEmpty()
        groups.getOrPut(album.lowercase() + "\n" + artist.lowercase()) { AutoAlbumGroup(album, artist) }.tracks += track
    }
    val kept = groups.filterValues { it.tracks.size >= minimum }
    val repeated = kept.values.groupingBy { it.album.lowercase() }.eachCount()
    return kept.map { (key, group) ->
        // 同名专辑出现在多个歌手名下时，标题带上歌手以便区分。
        val title = if ((repeated[group.album.lowercase()] ?: 0) > 1 && group.artist.isNotBlank())
            "${group.album} · ${group.artist}" else group.album
        AutoPlaylistPlan(autoPlaylistId(kind, key), title, kind, group.tracks)
    }
}

/**
 * record 记着每个自动歌单曾经加入过的歌曲：
 * 建过但现在不存在＝用户删掉了，不再重建；加入过但现在不在歌单里＝用户移走了，不再加回。
 */
internal fun resolveAutoPlaylistWrites(plans: List<AutoPlaylistPlan>, existingIds: Set<String>,
    record: Map<String, Set<String>>): List<AutoPlaylistWrite> = plans.mapNotNull { plan ->
    val known = record[plan.id]
    val exists = plan.id in existingIds
    if (known != null && !exists) return@mapNotNull null
    val pending = plan.tracks.filter { known == null || it.id !in known }
    if (pending.isEmpty()) null else AutoPlaylistWrite(plan, !exists, pending)
}

private class AutoPlaylistRecord(private val file: File) {
    fun read(): Map<String, Set<String>> = runCatching {
        val json = JSONObject(file.readText())
        json.keys().asSequence().associateWith { key ->
            val values = json.getJSONArray(key)
            (0 until values.length()).map { values.getString(it) }.toSet()
        }
    }.getOrDefault(emptyMap())

    fun save(value: Map<String, Set<String>>) {
        runCatching {
            file.parentFile?.mkdirs()
            val json = JSONObject()
            value.forEach { (key, ids) -> json.put(key, JSONArray(ids.toList())) }
            file.writeText(json.toString())
        }
    }
}

private suspend fun MusicLibraryStore.applyAutoPlaylists(writes: List<AutoPlaylistWrite>) =
    aiTransaction(publishChanges = true) { db ->
        val now = System.currentTimeMillis()
        writes.forEachIndexed { index, write ->
            val id = write.plan.id
            if (write.create) db.insertWithOnConflict("playlists", null, ContentValues().apply {
                put("id", id); put("title", write.plan.title); put("position", now + index)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            var position = db.nextPosition(id)
            val addedAt = db.rawQuery("SELECT MAX(added_at) FROM members WHERE playlist_id=?", arrayOf(id)).use {
                it.moveToFirst()
                maxOf(now, it.getLong(0) + 1)
            }
            write.tracks.forEach { track ->
                // 整理期间歌曲被移出资料库时跳过，外键不满足会让整个事务失败。
                if (DatabaseUtils.queryNumEntries(db, "songs", "id=?", arrayOf(track.id)) == 0L) return@forEach
                db.insertWithOnConflict("members", null, ContentValues().apply {
                    put("playlist_id", id); put("song_id", track.id); put("position", position++)
                    put("added_at", addedAt)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

internal suspend fun MusicLibraryStore.organizeLocalLibrary(kinds: List<AutoPlaylistKind>): AutoOrganizeResult =
    withContext(Dispatchers.IO) {
        val snapshot = state.value
        val record = AutoPlaylistRecord(File(assets, "auto_playlists.json"))
        val known = record.read()
        val writes = resolveAutoPlaylistWrites(kinds.flatMap { planAutoPlaylists(snapshot.tracks, it) },
            snapshot.playlists.map { it.id }.toSet(), known)
        if (writes.isEmpty()) return@withContext AutoOrganizeResult(0, 0)
        applyAutoPlaylists(writes)
        record.save(known + writes.associate { write ->
            write.plan.id to (known[write.plan.id].orEmpty() + write.tracks.map { it.id })
        })
        AutoOrganizeResult(writes.count { it.create }, writes.filterNot { it.create }.sumOf { it.tracks.size })
    }

/** “导入后自动整理”开关，默认关闭。 */
internal class LibraryAutoOrganizePreference(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("music_library_settings", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = preferences.getBoolean("auto_organize", false)
        set(value) { preferences.edit().putBoolean("auto_organize", value).apply() }
}
