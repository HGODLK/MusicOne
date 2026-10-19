package com.musicone.demo

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 写入串行且在事务提交后发布快照，页面始终可展示上一次完整内容。 */
internal class MusicLibraryStore private constructor(context: Context) {
    private val helper = MusicLibraryDatabase(context.applicationContext)
    val assets = File(context.filesDir, "music_library").apply { mkdirs() }
    private val changes = MutableStateFlow(LibrarySnapshot())
    val state = changes.asStateFlow()
    private val mutex = Mutex()
    private var lyricRevision = 0L

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            helper.writableDatabase.inTransaction { db -> db.ensurePhoneStoragePlaylist() }
            publish()
        }
    }

    private fun publish() {
        val db = helper.readableDatabase
        val songs = linkedMapOf<String, MusicTrack>()
        db.rawQuery("SELECT * FROM songs ORDER BY rowid", null).use { rows ->
            while (rows.moveToNext()) rows.song()?.let { songs[it.id] = it }
        }
        val lists = mutableListOf<MusicPlaylist>()
        val subscriptions = mutableListOf<LibrarySubscription>()
        db.rawQuery("SELECT * FROM playlists ORDER BY position,rowid", null).use { rows ->
            while (rows.moveToNext()) {
                val id = rows.text("id")
                val members = mutableListOf<MusicTrack>()
                val chronological = id != PHONE_STORAGE_PLAYLIST_ID && rows.isNull(rows.getColumnIndexOrThrow("remote_folder"))
                val order = if (chronological) "added_at DESC,position,rowid" else "position,rowid"
                db.rawQuery("SELECT song_id FROM members WHERE playlist_id=? AND hidden=0 ORDER BY $order", arrayOf(id)).use { items ->
                    while (items.moveToNext()) songs[items.getString(0)]?.let(members::add)
                }
                val tracks = if (id == PHONE_STORAGE_PLAYLIST_ID) songs.values.filter { it.source == MusicSource.LOCAL } else members
                lists += libraryPlaylist(id, rows.text("title"), tracks)
                if (!rows.isNull(rows.getColumnIndexOrThrow("remote_folder"))) subscriptions += LibrarySubscription(
                    id, rows.number("remote_folder"), rows.text("owner"), rows.number("manual_order") != 0L,
                )
            }
        }
        val folders = mutableListOf<LibraryFolder>()
        db.rawQuery("SELECT * FROM folders ORDER BY rowid", null).use { rows ->
            while (rows.moveToNext()) folders += LibraryFolder(rows.text("id"), rows.text("uri"), rows.text("title"), rows.number("copy_files") != 0L)
        }
        changes.value = LibrarySnapshot(songs.values.toList(), lists, folders, subscriptions, lyricRevision)
        MusicDiskCache.available()?.setLibraryPrimary(primaryCacheMembers())
    }

    internal fun primaryCacheMembers(): List<Pair<String, Long>> = helper.readableDatabase.rawQuery(
        "SELECT songs.data,members.added_at FROM members JOIN songs ON songs.id=members.song_id " +
            "WHERE playlist_id=? AND hidden=0 ORDER BY added_at DESC,position,members.rowid",
        arrayOf(UNIFIED_PLAYLIST_ID),
    ).use { rows ->
        buildList {
            var timestamp = -1L
            var withinBatch = 0L
            while (rows.moveToNext()) {
                val added = rows.getLong(1)
                if (timestamp != added) { timestamp = added; withinBatch = 0 }
                val track = runCatching { JSONObject(rows.getString(0)).toQqStoredTrack() }.getOrNull()
                if (track != null) add(track.cacheIdentity() to (added * 1_000_000 - withinBatch++))
            }
        }
    }

    private suspend fun <T> write(onCommitted: () -> Unit = {}, block: (SQLiteDatabase) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            val db = helper.writableDatabase
            val result = db.inTransaction { block(db).also { db.ensurePhoneStoragePlaylist() } }
            // 返回调度器前先移交资产所有权，取消不能删除已提交事务引用的文件。
            onCommitted()
            publish()
            result
        }
    }

    suspend fun createPlaylist(title: String, onCreated: (String) -> Unit = {}): String {
        var createdId = ""
        return write(onCommitted = { onCreated(createdId) }) { db ->
            require(title.isNotBlank()) { "请输入歌单名称" }
            val id = "library-${UUID.randomUUID()}"
            db.insertOrThrow("playlists", null, ContentValues().apply {
                put("id", id); put("title", title.trim()); put("position", System.currentTimeMillis())
            })
            id.also { createdId = it }
        }
    }
    suspend fun renamePlaylist(id: String, title: String) = write { db ->
        require(id != UNIFIED_PLAYLIST_ID && title.isNotBlank()) { "综合歌单不能重命名，歌单名称不能为空" }
        db.update("playlists", ContentValues().apply { put("title", title.trim()) }, "id=?", arrayOf(id))
    }
    suspend fun deletePlaylist(id: String) = write { db ->
        require(id != UNIFIED_PLAYLIST_ID && id != PHONE_STORAGE_PLAYLIST_ID) { "固定歌单不能删除" }
        db.delete("playlists", "id=?", arrayOf(id))
    }
    suspend fun addTracks(playlistId: String, tracks: List<MusicTrack>) = write { db ->
        require(playlistId != PHONE_STORAGE_PLAYLIST_ID) { "手机存储自动同步全部本地音乐" }
        db.rawQuery("SELECT remote_folder FROM playlists WHERE id=?", arrayOf(playlistId)).use {
            require(it.moveToFirst()) { "目标歌单已不存在" }
            require(it.isNull(0)) { "B站订阅歌单只能通过原收藏夹同步歌曲" }
        }
        var position = db.nextPosition(playlistId)
        // 同毫秒的后续批次也必须排在旧成员之前，同批继续按传入顺序排列。
        val addedAt = db.rawQuery("SELECT MAX(added_at) FROM members WHERE playlist_id=?", arrayOf(playlistId)).use {
            it.moveToFirst()
            maxOf(System.currentTimeMillis(), it.getLong(0) + 1)
        }
        tracks.distinctBy { it.id }.forEach { track ->
            if (db.song(track.id) == null) db.upsertSong(track) else db.completeLibraryArtwork(track)
            db.insertWithOnConflict("members", null, ContentValues().apply {
                put("playlist_id", playlistId); put("song_id", track.id); put("position", position++)
                put("added_at", addedAt)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.execSQL("UPDATE members SET hidden=0,remote_member=0 WHERE playlist_id=? AND song_id=?", arrayOf(playlistId, track.id))
        }
    }
    suspend fun removeTrack(playlistId: String, songId: String) = write { db ->
        require(playlistId != PHONE_STORAGE_PLAYLIST_ID) { "手机存储自动同步全部本地音乐" }
        // 保留远端成员的排除记录，下一次订阅刷新不把手动移除的歌曲加回。
        db.execSQL("UPDATE members SET hidden=1 WHERE playlist_id=? AND song_id=? AND remote_member=1", arrayOf(playlistId, songId))
        db.delete("members", "playlist_id=? AND song_id=? AND remote_member=0", arrayOf(playlistId, songId))
    }
    suspend fun reorder(playlistId: String, ids: List<String>) = write { db ->
        ids.distinct().forEachIndexed { index, id ->
            db.execSQL("UPDATE members SET position=? WHERE playlist_id=? AND song_id=?", arrayOf<Any>(index, playlistId, id))
        }
        db.execSQL("UPDATE playlists SET manual_order=1 WHERE id=?", arrayOf(playlistId))
    }
    suspend fun reorderPlaylists(ids: List<String>) = write { db ->
        ids.filter { it != UNIFIED_PLAYLIST_ID }.distinct().forEachIndexed { i, id ->
            db.execSQL("UPDATE playlists SET position=? WHERE id=?", arrayOf<Any>(i + 1, id))
        }
    }
    suspend fun saveTrack(track: MusicTrack, uri: String = track.localUri, folderId: String? = null, relativePath: String = "", size: Long = 0L, onCommitted: () -> Unit = {}) = write(onCommitted) { db ->
        db.upsertSong(track)
        if (uri.isNotBlank()) db.insertWithOnConflict("locations", null, ContentValues().apply {
            put("song_id", track.id); put("uri", uri); put("folder_id", folderId); put("relative_path", relativePath); put("size", size)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    suspend fun updateOverrides(songId: String, title: String?, artist: String?, artwork: String?, onCommitted: () -> Unit = {}) = write(onCommitted) { db ->
        val song = db.song(songId) ?: error("歌曲已不存在")
        require(song.source.supportsLibraryEditing) { "此音源不支持编辑资料" }
        db.update("songs", ContentValues().apply {
            if (title == null) putNull("title_override") else put("title_override", title.takeIf(String::isNotBlank))
            if (artist == null) putNull("artist_override") else put("artist_override", artist.takeIf(String::isNotBlank))
            if (artwork == null) putNull("artwork_override") else put("artwork_override", artwork.takeIf(String::isNotBlank))
        }, "id=?", arrayOf(songId))
    }
    suspend fun setLyrics(songId: String, path: String?, onCommitted: () -> Unit = {}) = write(onCommitted) { db ->
        lyricRevision++
        db.update("songs", ContentValues().apply { put("lyric_path", path) }, "id=?", arrayOf(songId))
    }
    suspend fun setLyricOffset(songId: String, offsetMs: Long) = write { db ->
        lyricRevision++
        db.update("songs", ContentValues().apply { put("lyric_offset", offsetMs.coerceIn(-3_600_000L, 3_600_000L)) }, "id=?", arrayOf(songId))
    }
    suspend fun setDefaultLyrics(songId: String, path: String, onCommitted: () -> Unit = {}) = write(onCommitted) { db ->
        lyricRevision++
        db.update("songs", ContentValues().apply { put("default_lyric_path", path) }, "id=?", arrayOf(songId))
    }
    fun defaultLyrics(songId: String): String? = helper.readableDatabase.rawQuery(
        "SELECT default_lyric_path FROM songs WHERE id=?", arrayOf(songId),
    ).use { if (it.moveToFirst()) it.getString(0) else null }
    fun lyricAsset(songId: String): Pair<String?, Long> = helper.readableDatabase.rawQuery(
        "SELECT lyric_path,lyric_offset FROM songs WHERE id=?", arrayOf(songId),
    ).use { if (it.moveToFirst()) (it.getString(0) to it.getLong(1)) else (null to 0L) }
    fun track(songId: String): MusicTrack? = helper.readableDatabase.song(songId)
    fun defaultTrack(songId: String): MusicTrack? = helper.readableDatabase.rawQuery("SELECT data FROM songs WHERE id=?", arrayOf(songId))
        .use { if (it.moveToFirst()) JSONObject(it.getString(0)).toQqStoredTrack() else null }
    /** 同步要包含隐藏成员，才能保留用户对原分 P 的移除记录。 */
    fun subscriptionTracks(playlistId: String): List<MusicTrack> = helper.readableDatabase.rawQuery(
        "SELECT songs.data FROM members JOIN songs ON songs.id=members.song_id WHERE playlist_id=? ORDER BY members.position,members.rowid",
        arrayOf(playlistId),
    ).use { rows -> buildList { while (rows.moveToNext()) JSONObject(rows.getString(0)).toQqStoredTrack()?.let(::add) } }
    fun overrides(songId: String): Triple<String?, String?, String?> = helper.readableDatabase.rawQuery(
        "SELECT title_override,artist_override,artwork_override FROM songs WHERE id=?", arrayOf(songId),
    ).use { if (it.moveToFirst()) Triple(it.getString(0), it.getString(1), it.getString(2)) else Triple(null, null, null) }
    fun locationId(uri: String): String? = helper.readableDatabase.rawQuery("SELECT song_id FROM locations WHERE uri=?", arrayOf(uri))
        .use { if (it.moveToFirst()) it.getString(0) else null }
    fun locations(folderId: String): List<Triple<String, String, Long>> = helper.readableDatabase.rawQuery(
        "SELECT song_id,relative_path,size FROM locations WHERE folder_id=?", arrayOf(folderId),
    ).use { rows -> buildList { while (rows.moveToNext()) add(Triple(rows.getString(0), rows.getString(1), rows.getLong(2))) } }
    suspend fun saveFolder(folder: LibraryFolder) = write { db ->
        db.insertWithOnConflict("folders", null, ContentValues().apply {
            put("id", folder.id); put("uri", folder.uri); put("title", folder.title); put("copy_files", if (folder.copyFiles) 1 else 0)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    suspend fun unavailable(songIds: List<String>) = write { db ->
        songIds.distinct().forEach { id ->
            val raw = db.rawQuery("SELECT data FROM songs WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.getString(0) else null }
            if (raw != null) db.update("songs", ContentValues().apply {
                put("data", JSONObject(raw).put("playable", false).put("unavailableReason", "文件已失联，请重新关联").toString())
            }, "id=?", arrayOf(id))
        }
    }
    suspend fun removeFolder(id: String) = write { it.delete("folders", "id=?", arrayOf(id)) }
    suspend fun removeLocalSong(id: String) = write { db ->
        require(db.song(id)?.source == MusicSource.LOCAL) { "只能从本地音乐移除本地歌曲" }
        // 仅移除音乐库引用，原文件和已导入的音频资产都不在这里删除。
        db.delete("members", "song_id=?", arrayOf(id))
        db.delete("locations", "song_id=?", arrayOf(id))
        db.delete("songs", "id=?", arrayOf(id))
    }
    suspend fun updateLocalAvailability(values: List<LibraryFileAvailability>) = write { db ->
        values.forEach { value ->
            val raw = db.rawQuery("SELECT data FROM songs WHERE id=? AND source=?", arrayOf(value.id, MusicSource.LOCAL.name))
                .use { if (it.moveToFirst()) it.getString(0) else null } ?: return@forEach
            val data = JSONObject(raw)
            val track = data.toQqStoredTrack() ?: return@forEach
            if (track.localUri != value.uri || track.playable == value.readable) return@forEach
            data.put("playable", value.readable)
            if (value.readable) data.remove("unavailableReason") else data.put("unavailableReason", "文件已失联，请重新关联")
            db.update("songs", ContentValues().apply { put("data", data.toString()) }, "id=?", arrayOf(value.id))
        }
    }

    suspend fun subscribe(playlistId: String, folderId: Long, ownerId: String) = write { db ->
        require(playlistId != UNIFIED_PLAYLIST_ID && playlistId != ALL_LOCAL_PLAYLIST_ID && playlistId != PHONE_STORAGE_PLAYLIST_ID) { "请为订阅创建独立本地歌单" }
        db.execSQL("UPDATE playlists SET remote_folder=?,owner=? WHERE id=?", arrayOf<Any>(folderId, ownerId, playlistId))
    }
    suspend fun stopSubscription(playlistId: String) = write { db ->
        db.execSQL("UPDATE playlists SET remote_folder=NULL,owner=NULL WHERE id=?", arrayOf(playlistId))
        db.execSQL("DELETE FROM members WHERE playlist_id=? AND hidden=1", arrayOf(playlistId))
        db.execSQL("UPDATE members SET remote_member=0 WHERE playlist_id=?", arrayOf(playlistId))
    }
    suspend fun mergeSubscription(subscription: LibrarySubscription, tracks: List<MusicTrack>, previous: LibrarySubscription?,
                                  isCancelled: () -> Boolean, onCommitted: () -> Unit = {}) = write(onCommitted) { db ->
        if (isCancelled()) throw kotlinx.coroutines.CancellationException()
        db.rawQuery("SELECT remote_folder,owner FROM playlists WHERE id=?",
            arrayOf(subscription.playlistId)).use {
            check(it.moveToFirst()) { "订阅关系已变化，请重新刷新" }
            check(if (previous == null) it.isNull(0) && it.isNull(1) else
                !it.isNull(0) && it.getLong(0) == previous.folderId && it.getString(1) == previous.ownerId) { "订阅关系已变化，请重新刷新" }
        }
        // 订阅始终跟随收藏夹顺序，同时清除旧版本留下的手动排序标记。
        db.execSQL("UPDATE playlists SET remote_folder=?,owner=?,manual_order=0 WHERE id=?", arrayOf<Any>(subscription.folderId, subscription.ownerId, subscription.playlistId))
        // 只接收已完整分页的结果；调用方在账号代次校验后才提交。
        val remoteIds = tracks.mapTo(hashSetOf()) { it.id }
        val oldMemberIds = mutableListOf<String>()
        db.rawQuery("SELECT song_id FROM members WHERE playlist_id=? AND remote_member=1", arrayOf(subscription.playlistId)).use {
            while (it.moveToNext()) oldMemberIds += it.getString(0)
        }
        oldMemberIds.filter { it !in remoteIds }.forEach { db.delete("members", "playlist_id=? AND song_id=? AND hidden=0", arrayOf(subscription.playlistId, it)) }
        tracks.distinctBy { it.id }.forEachIndexed { index, song ->
            db.upsertSong(song)
            db.insertWithOnConflict("members", null, ContentValues().apply {
                put("playlist_id", subscription.playlistId); put("song_id", song.id)
                put("position", index.toLong())
                put("remote_member", 1)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.execSQL("UPDATE members SET position=? WHERE playlist_id=? AND song_id=? AND remote_member=1", arrayOf<Any>(index, subscription.playlistId, song.id))
        }
        var position = tracks.distinctBy { it.id }.size
        val manual = mutableListOf<String>()
        db.rawQuery("SELECT song_id FROM members WHERE playlist_id=? AND remote_member=0 ORDER BY position,rowid", arrayOf(subscription.playlistId)).use {
            while (it.moveToNext()) manual += it.getString(0)
        }
        manual.forEach { id -> db.execSQL("UPDATE members SET position=? WHERE playlist_id=? AND song_id=?", arrayOf<Any>(position++, subscription.playlistId, id)) }
        if (isCancelled()) throw kotlinx.coroutines.CancellationException()
    }

    internal fun database() = helper.writableDatabase
    internal suspend fun <T> importTransaction(onCommitted: () -> Unit, block: (SQLiteDatabase) -> T): T = write(onCommitted = {
        lyricRevision++
        onCommitted()
    }, block = block)
    companion object {
        @Volatile private var instance: MusicLibraryStore? = null
        fun get(context: Context): MusicLibraryStore = instance ?: synchronized(this) {
            instance ?: MusicLibraryStore(context.applicationContext).also { instance = it }
        }
    }
}

internal fun SQLiteDatabase.upsertSong(track: MusicTrack) {
    val values = ContentValues().apply { put("id", track.id); put("source", track.source.name); put("data", track.toQqStoredTrackJson().toString()) }
    if (update("songs", values, "id=?", arrayOf(track.id)) == 0) insertOrThrow("songs", null, values)
}
internal fun SQLiteDatabase.song(id: String): MusicTrack? = rawQuery("SELECT * FROM songs WHERE id=?", arrayOf(id)).use { if (it.moveToFirst()) it.song() else null }
internal fun Cursor.text(name: String): String = getString(getColumnIndexOrThrow(name)).orEmpty()
internal fun Cursor.number(name: String): Long = getLong(getColumnIndexOrThrow(name))
internal fun Cursor.song(): MusicTrack? = runCatching {
    JSONObject(text("data")).toQqStoredTrack()?.let { song -> song.copy(
        title = text("title_override").ifBlank { song.title }, artists = text("artist_override").ifBlank { song.artists },
        artworkUrl = text("artwork_override").ifBlank { song.artworkUrl.orEmpty() }.takeIf(String::isNotBlank),
    ) }
}.getOrNull()
internal fun SQLiteDatabase.nextPosition(id: String): Long = rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM members WHERE playlist_id=?", arrayOf(id))
    .use { it.moveToFirst(); it.getLong(0) }
internal inline fun <T> SQLiteDatabase.inTransaction(block: (SQLiteDatabase) -> T): T {
    beginTransaction()
    try { return block(this).also { setTransactionSuccessful() } } finally { endTransaction() }
}
