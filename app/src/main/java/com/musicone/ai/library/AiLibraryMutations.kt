package com.musicone

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 所有确认均在同一资料库事务内校验、写入和记账，重复确认返回同一执行结果。 */
internal class AiLibraryMutations(
    private val store: MusicLibraryStore,
    private val validateTracks: (List<MusicTrack>) -> Unit = {},
) {
    private val metadata = AiTrackMetadataPlans(validateTracks)
    private val lifecycle = AiPlaylistLifecycle()
    private val lyrics = AiLyricPlans(store)

    /** 歌词修改与歌曲资料修改一样单独记账，确认窗只列出涉及的歌曲。 */
    suspend fun prepareLyrics(changes: List<JSONObject>): JSONObject = store.aiTransaction { db ->
        require(changes.isNotEmpty()) { "请选择歌曲" }
        lyrics.validate(db, changes)
        val result = savePlan(db, "lyrics", emptyList())
        val plan = db.aiLoad("ai_library_plans", result.getString("planId"))!!
        plan.put("lyricChanges", JSONArray(changes))
        db.aiSave("ai_library_plans", plan.getString("planId"), plan)
        summary(plan)
    }

    suspend fun prepareCreatePlaylist(title: String, tracks: List<MusicTrack>): JSONObject = store.aiTransaction { db ->
        validateTracks(tracks)
        savePlan(db, "create_playlist", listOf(lifecycle.create(db, title, tracks)))
    }

    suspend fun prepareDeletePlaylist(id: String): JSONObject = store.aiTransaction { db ->
        savePlan(db, "delete_playlist", listOf(lifecycle.delete(db, id)))
    }
    suspend fun prepareEditTracks(edits: List<Pair<MusicTrack, Map<String, String>>>): JSONObject = store.aiTransaction { db ->
        val result = savePlan(db, "edit_tracks", emptyList())
        val plan = db.aiLoad("ai_library_plans", result.getString("planId"))!!
        plan.put("trackChanges", JSONArray(metadata.prepare(db, edits)))
        db.aiSave("ai_library_plans", plan.getString("planId"), plan)
        summary(plan)
    }

    suspend fun prepareRename(titles: Map<String, String>): JSONObject = store.aiTransaction { db ->
        require(titles.isNotEmpty()) { "请选择歌单" }
        val changes = titles.map { (id, rawTitle) ->
            val title = rawTitle.trim()
            require(title.isNotBlank()) { "歌单名称不能为空" }
            require(id != UNIFIED_PLAYLIST_ID) { "综合歌单不能重命名" }
            change(db, id).put("newTitle", title)
        }
        savePlan(db, "rename", changes)
    }

    suspend fun prepareAdd(id: String, tracks: List<MusicTrack>): JSONObject = store.aiTransaction { db ->
        require(tracks.isNotEmpty()) { "请选择歌曲" }
        val change = change(db, id)
        // 隐藏成员也是预先存在的成员，AI 不恢复、不覆盖它们。
        val existing = db.rawQuery("SELECT song_id FROM members WHERE playlist_id=?", arrayOf(id)).use { rows ->
            buildSet { while (rows.moveToNext()) add(rows.getString(0)) }
        }
        val additions = tracks.distinctBy { it.id }.filterNot { it.id in existing }
        change.put("tracks", JSONArray(additions.map { it.toStoredTrackJson() }))
            .put("duplicateCount", tracks.size - additions.size)
        savePlan(db, "add", listOf(change))
    }

    suspend fun prepareRemove(id: String, tracks: List<MusicTrack>): JSONObject = store.aiTransaction { db ->
        require(tracks.isNotEmpty()) { "请选择要移出的歌单成员" }
        val change = change(db, id).put("remove", JSONArray(db.aiRemovalMembers(id, tracks)))
        savePlan(db, "remove", listOf(change))
    }

    suspend fun prepareUndo(operationId: String): JSONObject = store.aiTransaction { db ->
        val operation = db.aiLoad("ai_library_plans", operationId) ?: error("操作不存在")
        require(operation.getString("status") == "executed" && operation.getString("kind") != "undo") { "此操作不能撤销" }
        require(!operation.optBoolean("undone")) { "此操作已撤销" }
        if (operation.has("lyricActual")) {
            val (changes, skipped) = lyrics.undo(db, operation.getJSONArray("lyricActual").objects())
            require(changes.isNotEmpty()) { "没有可安全撤销的歌词修改，后续修改已保留" }
            val result = savePlan(db, "undo", emptyList(), operationId, skipped)
            val plan = db.aiLoad("ai_library_plans", result.getString("planId"))!!
            plan.put("lyricChanges", JSONArray(changes))
            db.aiSave("ai_library_plans", plan.getString("planId"), plan)
            return@aiTransaction summary(plan)
        }
        if (operation.has("trackActual")) {
            val (edits, skipped) = metadata.undo(db, operation.getJSONArray("trackActual").objects())
            require(edits.isNotEmpty()) { "没有可安全撤销的修改，后续用户修改已保留" }
            val result = savePlan(db, "undo", emptyList(), operationId, skipped)
            val plan = db.aiLoad("ai_library_plans", result.getString("planId"))!!
            plan.put("trackChanges", JSONArray(edits))
            db.aiSave("ai_library_plans", plan.getString("planId"), plan)
            return@aiTransaction summary(plan)
        }
        val lifecycleActual = operation.getJSONArray("actual").objects().filter { it.has("action") }
        if (lifecycleActual.isNotEmpty()) {
            return@aiTransaction savePlan(db, "undo", lifecycleActual.map { lifecycle.undo(db, it) }, operationId)
        }
        var skipped = 0
        val changes = operation.getJSONArray("actual").objects().mapNotNull { actual ->
            val id = actual.getString("playlistId")
            if (!db.aiEditable(id)) { skipped++; return@mapNotNull null }
            val change = change(db, id)
            if (actual.has("newTitle")) {
                if (id != UNIFIED_PLAYLIST_ID && db.aiVersion(id, true) == actual.getLong("titleVersion") && change.getString("oldTitle") == actual.getString("newTitle")) {
                    change.put("newTitle", actual.getString("oldTitle"))
                } else skipped++
            }
            val removable = actual.optJSONArray("added")?.objects().orEmpty().filter { member ->
                val valid = db.aiMemberVersion(id, member.getString("id")) == member.getLong("version") && db.aiMemberExists(id, member.getString("id"))
                if (!valid) skipped++
                valid
            }
            val restorable = actual.optJSONArray("removed")?.objects().orEmpty().filter { member ->
                val valid = !db.aiMemberExists(id, member.getString("id")) &&
                    db.aiMemberVersion(id, member.getString("id")) == member.getLong("version") && db.song(member.getString("id")) != null
                if (!valid) skipped++
                valid
            }
            change.put("remove", JSONArray(removable)).put("restoreMembers", JSONArray(restorable))
            if (!change.has("newTitle") && removable.isEmpty() && restorable.isEmpty()) null else change
        }
        require(changes.isNotEmpty()) { "没有可安全撤销的修改，后续用户修改已保留" }
        savePlan(db, "undo", changes, operationId, skipped)
    }

    fun pendingPlan(planId: String): AiLibraryPlan? {
        val data = store.database().aiLoad("ai_library_plans", planId) ?: return null
        return AiLibraryPlan(planId, data.getString("kind"), data.getJSONArray("changes").objects().map { c ->
            AiLibraryChange(c.getString("playlistId"), c.getString("oldTitle"), c.optString("newTitle").takeIf { c.has("newTitle") },
                (c.optJSONArray("tracks")?.objects().orEmpty() + c.optJSONArray("restoreMembers")?.objects().orEmpty().map { it.getJSONObject("track") })
                    .mapNotNull { it.toStoredTrack() }, c.optInt("duplicateCount"),
                c.optJSONArray("remove")?.objects().orEmpty().map { it.getString("id") to it.optString("title") })
        }, data.getString("status"), aiMetadataDetails(data), aiChangeGroups(data), aiChangeSummary(data, false))
    }

    suspend fun cancel(planId: String): JSONObject = store.aiTransaction { db ->
        val plan = db.aiLoad("ai_library_plans", planId) ?: error("计划不存在")
        require(plan.getString("status") == "pending") { "计划已经处理" }
        plan.put("status", "cancelled")
        db.aiSave("ai_library_plans", planId, plan)
        summary(plan)
    }

    suspend fun confirm(planId: String): JSONObject = store.aiTransaction(publishChanges = true) { db ->
        val plan = db.aiLoad("ai_library_plans", planId) ?: error("计划不存在")
        if (plan.getString("status") == "executed") return@aiTransaction summary(plan)
        require(plan.getString("status") == "pending") { "计划已经取消" }
        val changes = plan.getJSONArray("changes").objects()
        changes.forEach { c ->
            val id = c.getString("playlistId")
            if (c.has("action")) lifecycle.validate(db, c) else {
                require(db.aiEditable(id)) { "目标歌单已失去编辑权限" }
                // 更新前生成的待确认计划也不能改综合歌单名称。
                require(id != UNIFIED_PLAYLIST_ID || !c.has("newTitle")) { "综合歌单不能重命名" }
                require(db.aiVersion(id) == c.getLong("version")) { "歌单在确认前发生变化，请重新生成计划" }
            }
        }
        // 同一次写事务内复核来源会话，校验失败时整批保持未执行。
        validateTracks(changes.flatMap { it.optJSONArray("tracks")?.objects().orEmpty() }.map { it.toStoredTrack() ?: error("歌曲资料无效") })
        validateTracks(changes.filter { it.optString("action") == "restore" }.flatMap {
            it.getJSONArray("songSnapshot").objects().map { song -> JSONObject(song.getString("data")).toStoredTrack() ?: error("歌曲资料无效") }
        })
        val trackChanges = plan.optJSONArray("trackChanges")?.objects().orEmpty()
        metadata.validate(db, trackChanges)
        val lyricChanges = plan.optJSONArray("lyricChanges")?.objects().orEmpty()
        lyrics.validate(db, lyricChanges)
        val undoOf = plan.optString("undoOf")
        if (undoOf.isNotBlank()) {
            val original = db.aiLoad("ai_library_plans", undoOf) ?: error("原操作不存在")
            require(!original.optBoolean("undone")) { "原操作已撤销" }
            original.put("undone", true)
            db.aiSave("ai_library_plans", undoOf, original)
        }
        val actual = changes.map { c ->
            if (c.has("action")) {
                lifecycle.applyBeforeMembers(db, c)
                val additions = if (c.getString("action") == "create") apply(db, c).getJSONArray("added") else JSONArray()
                lifecycle.apply(db, c).put("added", additions)
            } else apply(db, c)
        }
        actual.firstOrNull { it.optString("action") == "create" }?.let {
            plan.put("createdPlaylistId", it.getString("playlistId")).put("createdPlaylistTitle", it.getString("oldTitle"))
        }
        if (trackChanges.isNotEmpty()) plan.put("trackActual", JSONArray(metadata.apply(db, trackChanges)))
        if (lyricChanges.isNotEmpty()) plan.put("lyricActual", JSONArray(lyrics.apply(db, lyricChanges)))
        plan.put("actual", JSONArray(actual)).put("status", "executed")
        db.aiSave("ai_library_plans", planId, plan)
        summary(plan)
    }

    private fun apply(db: SQLiteDatabase, c: JSONObject): JSONObject {
        val id = c.getString("playlistId")
        val actual = JSONObject().put("playlistId", id).put("oldTitle", c.getString("oldTitle"))
        if (c.has("newTitle") && c.getString("newTitle") != c.getString("oldTitle")) {
            db.execSQL("UPDATE playlists SET title=? WHERE id=?", arrayOf(c.getString("newTitle"), id))
            actual.put("newTitle", c.getString("newTitle")).put("titleVersion", db.aiVersion(id, true))
        }
        val added = JSONArray()
        val timestamp = db.rawQuery("SELECT MAX(added_at) FROM members WHERE playlist_id=?", arrayOf(id)).use {
            it.moveToFirst(); maxOf(System.currentTimeMillis(), it.getLong(0) + 1)
        }
        var position = db.rawQuery("SELECT COALESCE(MAX(position),-1)+1 FROM members WHERE playlist_id=?", arrayOf(id)).use { it.moveToFirst(); it.getLong(0) }
        c.optJSONArray("tracks")?.objects().orEmpty().forEach { raw ->
            val track = raw.toStoredTrack() ?: error("歌曲资料无效")
            require(!db.aiMemberExists(id, track.id)) { "歌单成员已改变" }
            if (db.song(track.id) == null) db.upsertSong(track)
            db.insertOrThrow("members", null, ContentValues().apply {
                put("playlist_id", id); put("song_id", track.id); put("position", position++); put("added_at", timestamp)
            })
            added.put(JSONObject().put("id", track.id).put("title", track.title).put("version", db.aiMemberVersion(id, track.id)))
        }
        val removed = JSONArray()
        db.aiRestoreMembers(id, c.optJSONArray("restoreMembers")?.objects().orEmpty()).forEach(added::put)
        c.optJSONArray("remove")?.objects().orEmpty().forEach { member ->
            val songId = member.getString("id")
            require(db.aiMemberVersion(id, songId) == member.getLong("version")) { "歌曲在确认前发生变化" }
            require(db.delete("members", "playlist_id=? AND song_id=?", arrayOf(id, songId)) == 1) { "歌曲已不在歌单中" }
            if (member.has("track")) removed.put(JSONObject(member.toString()).put("version", db.aiMemberVersion(id, songId)))
        }
        return actual.put("added", added).put("removed", removed)
    }

    private fun change(db: SQLiteDatabase, id: String): JSONObject {
        require(db.aiEditable(id)) { "AI 只能编辑 MusicOne 本地自建歌单和综合歌单" }
        val title = db.rawQuery("SELECT title FROM playlists WHERE id=?", arrayOf(id)).use { require(it.moveToFirst()); it.getString(0) }
        return JSONObject().put("playlistId", id).put("oldTitle", title).put("version", db.aiVersion(id))
    }

    private fun savePlan(db: SQLiteDatabase, kind: String, changes: List<JSONObject>, undoOf: String = "", skipped: Int = 0): JSONObject {
        val id = UUID.randomUUID().toString()
        val plan = JSONObject().put("planId", id).put("operationId", id).put("kind", kind).put("status", "pending")
            .put("changes", JSONArray(changes)).put("undoOf", undoOf).put("skippedCount", skipped)
        db.aiSave("ai_library_plans", id, plan)
        return summary(plan)
    }

    private fun summary(plan: JSONObject): JSONObject = JSONObject(plan.toString()).apply {
        val actual = optJSONArray("actual")?.objects().orEmpty().associateBy { it.getString("playlistId") }
        getJSONArray("changes").objects().forEach { c ->
            actual[c.getString("playlistId")]?.let { saved -> saved.keys().forEach { key -> c.put(key, saved.get(key)) } }
            val tracks = c.optJSONArray("tracks")?.objects().orEmpty()
            c.put("addCount", c.optJSONArray("added")?.length() ?: (tracks.size + (c.optJSONArray("restoreMembers")?.length() ?: 0)))
                .put("removeCount", c.optJSONArray("remove")?.length() ?: 0)
                .put("memberCount", c.optJSONArray("memberSnapshot")?.length() ?: 0)
            // 原生确认页读取完整计划；模型摘要不包含恢复快照或完整歌曲资料。
            c.put("tracks", JSONArray(tracks.take(20).map { t -> JSONObject().put("id", t.getString("id")).put("title", t.optString("title")) }))
                .put("previewComplete", tracks.size <= 20)
            c.remove("playlistSnapshot"); c.remove("memberSnapshot"); c.remove("songSnapshot"); c.remove("creationSongs")
            listOf("remove", "removed", "restoreMembers").forEach { key -> c.optJSONArray(key)?.objects()?.forEach { it.remove("track") } }
        }
        remove("actual")
        remove("trackActual")
        remove("lyricActual")
        // 模型只需要知道改了哪首歌，歌词全文和前后快照不回传。
        optJSONArray("lyricChanges")?.objects().orEmpty().forEach { change ->
            listOf("files", "before", "after", "lyricPath", "key").forEach(change::remove)
        }
        optJSONArray("trackChanges")?.objects().orEmpty().forEach { edit ->
            edit.remove("track"); edit.remove("snapshot"); edit.remove("session")
        }
    }
}
