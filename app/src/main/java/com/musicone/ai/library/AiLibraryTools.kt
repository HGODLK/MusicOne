package com.musicone

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 完整曲目按行保存在本机，模型和原生界面都按页读取，避免长歌单反复全量编码。 */
internal class AiLibraryTools(
    context: Context,
    private val store: MusicLibraryStore = MusicLibraryStore.get(context),
    validateTracks: (List<MusicTrack>) -> Unit = {},
) {
    private val mutations = AiLibraryMutations(store, validateTracks)
    private val initialization = Mutex()
    @Volatile private var initialized = false
    private suspend fun ensureReady() {
        if (!initialized) initialization.withLock {
            if (!initialized) {
                store.aiTransaction { it.ensureAiLibrarySchema() }
                store.refresh()
                initialized = true
            }
        }
    }
    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { ensureReady(); block() }

    suspend fun directory(cursor: String? = null, limit: Int = 50): JSONObject = io {
        val playlists = store.state.value.let { it.playlists + it.allLocal }
        val offset = offset(cursor, playlists.size)
        val end = minOf(playlists.size, offset + limit.coerceIn(1, 100))
        JSONObject().put("playlists", JSONArray(playlists.subList(offset, end).map { playlist ->
            playlistIdentity(playlist).put("id", playlist.id).put("title", playlist.title).put("count", playlist.tracks.size)
                .put("editable", store.database().aiEditable(playlist.id)).put("complete", true)
        })).put("count", playlists.size).put("complete", true).put("cursor", if (end < playlists.size) end.toString() else JSONObject.NULL)
    }

    suspend fun readPage(playlistId: String, cursor: String? = null, limit: Int = 50, query: String = ""): JSONObject = io {
        if (cursor != null) {
            val handle = when {
                cursor.startsWith("result:") -> cursor.removePrefix("result:").substringBeforeLast(':')
                '|' in cursor -> cursor.substringBefore('|')
                else -> throw AiToolInputFailure("invalid_cursor", "请使用read_result及其continuation续读本地结果")
            }
            val data = store.database().aiLoad("ai_library_results", handle)
            if (data?.optJSONObject("playlist")?.optString("id") != playlistId) throw AiToolInputFailure("invalid_cursor", "游标不属于目标歌单，请重新读取")
            return@io playlistPage(handle, cursor, limit)
        }
        val playlist = playlist(playlistId)
        val filtered = playlist.tracks.asSequence().filter { matches(it, query) }.asIterable()
        val result = registerRows(filtered, playlist.title, true, null)
        val meta = store.database().aiLoad("ai_library_results", result.getString("handle"))!!
        meta.put("playlist", playlistIdentity(playlist).put("id", playlist.id).put("title", playlist.title))
        store.database().aiSave("ai_library_results", result.getString("handle"), meta)
        playlistPage(result.getString("handle"), null, limit)
    }

    suspend fun search(query: String, playlistIds: List<String>? = null, limit: Int = 50): JSONObject = io {
        val tracks = if (playlistIds == null) store.state.value.tracks.asSequence() else playlistIds.asSequence().flatMap { playlist(it).tracks.asSequence() }
        val result = registerRows(tracks.filter { matches(it, query) }.asIterable(), "本地筛选：$query", true, null)
        readResult(result.getString("handle"), null, limit)
    }

    suspend fun stats(playlistIds: List<String>): JSONObject = io {
        val sets = playlistIds.distinct().map { playlist(it).tracks.mapTo(hashSetOf()) { track -> track.id } }
        val union = hashSetOf<String>().apply { sets.forEach { addAll(it) } }
        val intersection = sets.firstOrNull()?.toMutableSet()?.apply { sets.drop(1).forEach { retainAll(it) } }.orEmpty()
        JSONObject().put("counts", JSONArray(sets.map { it.size })).put("uniqueCount", union.size)
            .put("intersectionCount", intersection.size).put("duplicateCount", sets.sumOf { it.size } - union.size).put("complete", true)
    }

    suspend fun registerResult(tracks: List<MusicTrack>, label: String, complete: Boolean = true, totalCount: Int = tracks.size,
        playlist: JSONObject? = null, playback: JSONObject? = null, pagination: JSONObject? = null): JSONObject = io {
        registerRows(tracks, label, complete, totalCount, preserveEntries = playback != null).also { result ->
            if (playlist != null || playback != null || pagination != null) {
                val data = store.database().aiLoad("ai_library_results", result.getString("handle"))!!
                playlist?.let { data.put("playlist", it) }
                playback?.let { data.put("playback", it) }
                pagination?.let { data.put("pagination", it) }
                store.database().aiSave("ai_library_results", result.getString("handle"), data)
            }
        }
    }

    private fun registerRows(tracks: Iterable<MusicTrack>, label: String, complete: Boolean, totalCount: Int?,
        preserveEntries: Boolean = false): JSONObject {
        val id = UUID.randomUUID().toString()
        val db = store.database()
        var count = 0
        // 只保存结果表，不发布音乐库快照；metadata 和行在同一事务中可见。
        db.beginTransaction()
        try {
            val seen = hashSetOf<Pair<String, String>>()
            db.compileStatement("INSERT INTO ai_library_result_tracks(handle,position,data) VALUES(?,?,?)").use { statement ->
                tracks.forEach { track ->
                    if (preserveEntries || seen.add(track.source.id to track.id)) {
                        statement.bindString(1, id); statement.bindLong(2, (count++).toLong()); statement.bindString(3, track.toStoredTrackJson().toString())
                        statement.executeInsert()
                    }
                }
            }
            val meta = JSONObject().put("label", label).put("complete", complete).put("totalCount", totalCount ?: count).put("count", count)
            db.aiSave("ai_library_results", id, meta)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return JSONObject().put("handle", id).put("count", count).put("totalCount", totalCount ?: count).put("complete", complete)
    }

    suspend fun readResult(handle: String, cursor: String? = null, limit: Int = 50): JSONObject = io {
        val data = store.database().aiLoad("ai_library_results", handle) ?: throw AiToolInputFailure("invalid_handle", "结果句柄不存在，请重新读取原歌单")
        val count = data.getInt("count")
        val start = aiResultOffset(handle, cursor, count)
        val size = limit.coerceIn(1, 100)
        val rows = mutableListOf<Pair<Int, MusicTrack>>()
        store.database().rawQuery("SELECT position,data FROM ai_library_result_tracks WHERE handle=? AND position>=? ORDER BY position LIMIT ?", arrayOf(handle, start.toString(), size.toString())).use { cursor ->
            while (cursor.moveToNext()) {
                val track = JSONObject(cursor.getString(1)).toStoredTrack() ?: continue
                rows += cursor.getInt(0) to track
            }
        }
        val (items, itemDefaults) = aiResultItems(handle, rows)
        val end = minOf(count, start + size)
        val next = if (end < count) "result:$handle:$end" else null
        JSONObject().put("playlist", data.opt("playlist") ?: JSONObject.NULL).put("handle", handle).put("items", items)
            .apply { if (itemDefaults.length() > 0) put("itemDefaults", itemDefaults) }
            .put("count", count).put("totalCount", data.getInt("totalCount"))
            .put("complete", data.getBoolean("complete")).put("cursor", next ?: JSONObject.NULL)
            .put("pageStart", start + 1).put("pageEnd", end).put("localPageComplete", end == count)
            .put("continuation", next?.let { aiContinuation("read_result", JSONObject().put("handle", handle).put("cursor", it)) } ?: JSONObject.NULL)
            .apply { data.optJSONObject("pagination")?.let { page ->
                put("scope", "page").put("pageComplete", true).put("collectionComplete", data.getBoolean("complete"))
                put("isLastPage", page.isNull("nextCursor")).put("nextCursor", page.opt("nextCursor") ?: JSONObject.NULL)
                put("remoteContinuation", page.optional("nextCursor")?.let { aiContinuation("remote_tracks",
                    JSONObject().put("playlistRef", page.getString("playlistRef")).put("cursor", it)) } ?: JSONObject.NULL)
            } }
            .apply { data.optJSONObject("playback")?.let { put("playback", it) } }
    }

    suspend fun resolveTracks(trackRefs: List<String>): List<MusicTrack> = io {
        val handles = mutableMapOf<String, String>()
        trackRefs.distinct().map { ref ->
            val (part, index) = aiParseTrackRef(ref) ?: throw AiToolInputFailure("invalid_track_reference", "歌曲引用无效，请重新读取原结果句柄")
            val handle = handles.getOrPut(part) { fullResultHandle(part) }
            store.database().rawQuery("SELECT data FROM ai_library_result_tracks WHERE handle=? AND position=?", arrayOf(handle, index.toString())).use {
                require(it.moveToFirst()) { "歌曲引用已失效" }
                JSONObject(it.getString(0)).toStoredTrack() ?: error("歌曲资料无效")
            }
        }
    }

    // 短引用按句柄前缀匹配，必须唯一；完整句柄原样使用。前缀查询走 (handle,position) 主键的范围扫描。
    private fun fullResultHandle(part: String): String {
        if (part.length != AI_SHORT_HANDLE) return part
        val found = store.database().rawQuery("SELECT DISTINCT handle FROM ai_library_result_tracks WHERE handle>=? AND handle<? LIMIT 2",
            arrayOf(part, part + "￿")).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        return when (found.size) {
            1 -> found.single()
            0 -> throw AiToolInputFailure("invalid_track_reference", "歌曲引用已失效，请重新读取原结果句柄")
            else -> throw AiToolInputFailure("invalid_track_reference", "歌曲引用不唯一，请用read_result重新读取原结果句柄取得引用")
        }
    }

    /** 每个句柄只展开本机已经保存的完整结果，远端后续页仍需先读取取得各自句柄。 */
    suspend fun resolveResultHandles(handles: List<String>): List<MusicTrack> = io { AiResultOperations(store.database()).tracks(handles) }

    suspend fun compareResults(operation: String, left: List<String>, right: List<String>): JSONObject = io {
        val results = AiResultOperations(store.database())
        val tracks = aiTrackSet(operation, results.tracks(left, requireComplete = true), results.tracks(right, requireComplete = true))
        val registered = registerRows(tracks, "集合运算：$operation", true, tracks.size)
        readResult(registered.getString("handle")).put("operation", operation).put("leftHandles", JSONArray(left)).put("rightHandles", JSONArray(right))
    }

    suspend fun prepareRemove(playlistId: String, tracks: List<MusicTrack>) = io { mutations.prepareRemove(playlistId, tracks) }

    suspend fun prepareEditTracks(edits: List<Pair<MusicTrack, Map<String, String>>>) = io { mutations.prepareEditTracks(edits) }
    suspend fun prepareCreatePlaylist(title: String, tracks: List<MusicTrack>) = io { mutations.prepareCreatePlaylist(title, tracks) }
    suspend fun prepareDeletePlaylist(playlistId: String) = io { mutations.prepareDeletePlaylist(playlistId) }
    suspend fun prepareRename(titles: Map<String, String>) = io { mutations.prepareRename(titles) }
    suspend fun prepareAdd(playlistId: String, tracks: List<MusicTrack>) = io { mutations.prepareAdd(playlistId, tracks) }
    suspend fun prepareUndo(operationId: String) = io { mutations.prepareUndo(operationId) }
    suspend fun prepareLyrics(changes: List<JSONObject>) = io { mutations.prepareLyrics(changes) }
    fun pendingPlan(planId: String): AiLibraryPlan? = runCatching { mutations.pendingPlan(planId) }.getOrNull()
    /** 只允许原生确认按钮调用，不能注册成模型工具。 */
    suspend fun confirm(planId: String) = io {
        mutations.confirm(planId).also { if (it.has("lyricChanges")) AiLyricLayer.committed(store) }
    }
    suspend fun cancel(planId: String) = io { mutations.cancel(planId) }

    private suspend fun playlistPage(handle: String, cursor: String?, limit: Int): JSONObject = readResult(handle, cursor, limit)
    private fun offset(cursor: String?, count: Int): Int {
        val value = if (cursor == null) 0 else cursor.toIntOrNull() ?: throw AiToolInputFailure("invalid_cursor", "目录分页游标无效，请从list_playlists首批重新读取")
        require(value in 0..count) { "分页游标无效" }
        return value
    }
    private fun playlist(id: String): MusicPlaylist = store.state.value.let { snapshot ->
        if (id == ALL_LOCAL_PLAYLIST_ID) snapshot.allLocal else snapshot.playlists.firstOrNull { it.id == id } ?: throw AiToolInputFailure("invalid_playlist", "歌单不存在，请重新读取list_playlists目录")
    }
    private fun playlistIdentity(playlist: MusicPlaylist): JSONObject {
        val subscription = store.state.value.subscriptions.firstOrNull { it.playlistId == playlist.id }
        val mirror = playlist.isLocalLibraryMirror
        val type = when {
            subscription != null -> "subscription"
            playlist.id == UNIFIED_PLAYLIST_ID -> "local_cross_source_favorites"
            mirror -> "local_music_mirror"
            else -> "local_created"
        }
        return JSONObject().put("type", type).put("sourceId", subscription?.sourceId ?: MusicSource.LOCAL.id)
            .put("source", subscription?.sourceId?.let { MusicSource.fromId(it).label } ?: "MusicOne 本地")
            .put("account", subscription?.accountId?.takeIf { it.isNotBlank() } ?: if (subscription == null) "local" else "unknown")
            .put("creator", if (subscription == null) "local" else "unknown")
            .put("owned", if (subscription == null) true else "unknown")
            .put("remoteCollectionId", subscription?.remoteCollectionId ?: JSONObject.NULL)
            .put("canEditMembers", store.database().aiEditable(playlist.id))
            .put("canDelete", store.database().aiDeletable(playlist.id))
            .put("scope", if (playlist.id == UNIFIED_PLAYLIST_ID) "用户本地收藏的跨音源曲目，不是资料库全部歌曲" else "歌单成员")
    }
    private fun matches(track: MusicTrack, query: String) = query.isBlank() ||
        listOf(track.title, track.artists, track.album).any { it.contains(query, ignoreCase = true) }
}
