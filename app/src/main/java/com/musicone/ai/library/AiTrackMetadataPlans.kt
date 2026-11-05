package com.musicone

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject

internal val aiMetadataFields = mapOf("title" to "title_override", "artists" to "artist_override", "album" to "album_override")

/** 只修改本地显示覆盖值；每个字段单独记账，撤销不会覆盖后续用户编辑。 */
internal class AiTrackMetadataPlans(private val validateTracks: (List<MusicTrack>) -> Unit) {
    fun prepare(db: SQLiteDatabase, edits: List<Pair<MusicTrack, Map<String, String>>>): List<JSONObject> {
        require(edits.isNotEmpty()) { "请选择歌曲" }
        require(edits.map { it.first.id }.distinct().size == edits.size) { "同一首歌曲请合并成一次修改" }
        return edits.map { (reference, fields) ->
            val existing = db.song(reference.id)
            val track = existing ?: reference
            require(track.source == reference.source && track.sourceAccountId == reference.sourceAccountId) { "歌曲来源或账号已变化" }
            require(track.source.supportsLibraryEditing) { "${track.source.label}不支持编辑歌曲资料" }
            require(fields.isNotEmpty() && fields.keys.all { it in aiMetadataFields }) { "仅支持歌名、艺人和专辑" }
            validateTracks(listOf(track))
            val diff = JSONArray(fields.map { (name, value) ->
                require(value.isNotBlank()) { "新资料不能为空" }
                val column = aiMetadataFields.getValue(name)
                JSONObject().put("field", name).put("column", column).put("old", db.overrideValue(track.id, column) ?: JSONObject.NULL)
                    .put("oldDisplay", track.metadataDisplay(name)).put("new", value.trim()).put("version", db.aiTrackFieldVersion(track.id, column))
            })
            JSONObject().put("songId", track.id).put("title", track.title).put("track", track.toStoredTrackJson())
                .put("session", track.aiMetadataSession()).put("existed", existing != null)
                .put("snapshot", existing?.toStoredTrackJson()?.toString() ?: JSONObject.NULL).put("fields", diff)
        }
    }

    fun undo(db: SQLiteDatabase, actual: List<JSONObject>): Pair<List<JSONObject>, Int> {
        var skipped = 0
        val changes = actual.mapNotNull { old ->
            val track = db.song(old.getString("songId"))
            if (track == null || !track.source.supportsLibraryEditing || track.aiMetadataSession() != old.getString("session")) {
                skipped += old.getJSONArray("fields").length(); return@mapNotNull null
            }
            val fields = old.getJSONArray("fields").objects().mapNotNull { field ->
                val column = field.getString("column")
                if (!aiMetadataUndoMatches(db.aiTrackFieldVersion(track.id, column), field.getLong("appliedVersion"),
                        db.overrideValue(track.id, column), field.nullable("new"))) { skipped++; null }
                else JSONObject(field.toString()).put("old", field.opt("new")).put("new", field.opt("old"))
                    .put("oldDisplay", track.metadataDisplay(field.getString("field"))).put("version", db.aiTrackFieldVersion(track.id, column))
            }
            if (fields.isEmpty()) null else JSONObject(old.toString()).put("title", track.title).put("fields", JSONArray(fields))
                .put("existed", true).put("snapshot", track.toStoredTrackJson().toString()).put("track", track.toStoredTrackJson())
        }
        return changes to skipped
    }

    fun validate(db: SQLiteDatabase, edits: List<JSONObject>) {
        edits.forEach { edit ->
            val existing = db.song(edit.getString("songId"))
            val existed = edit.optBoolean("existed", true)
            require((existing != null) == existed) { "歌曲本地资料在确认前发生变化，请重新生成计划" }
            val track = existing ?: edit.getJSONObject("track").toStoredTrack() ?: error("歌曲资料无效")
            require(track.source.supportsLibraryEditing) { "歌曲已失去资料编辑权限" }
            require(track.aiMetadataSession() == edit.getString("session")) { "音源或账号已变化，请重新生成计划" }
            if (existed) require(track.toStoredTrackJson().toString() == edit.getString("snapshot")) { "歌曲在确认前发生变化，请重新生成计划" }
            validateTracks(listOf(track))
            edit.getJSONArray("fields").objects().forEach { field ->
                require(db.aiTrackFieldVersion(track.id, field.getString("column")) == field.getLong("version")) { "歌曲资料已变化，请重新生成计划" }
            }
        }
    }

    fun apply(db: SQLiteDatabase, edits: List<JSONObject>): List<JSONObject> = edits.map { edit ->
        val id = edit.getString("songId")
        val fields = edit.getJSONArray("fields").objects()
        // 仅确认阶段为未保存的真实歌曲建本地显示记录，不加入任何歌单，也不覆盖已有资料。
        if (!edit.optBoolean("existed", true)) {
            require(db.song(id) == null) { "歌曲已被其他入口保存，请重新生成计划" }
            db.upsertSong(edit.getJSONObject("track").toStoredTrack() ?: error("歌曲资料无效"))
        }
        db.updateLibraryOverrides(id, fields.associate { it.getString("column") to it.nullable("new") })
        JSONObject(edit.toString()).put("fields", JSONArray(fields.map { field ->
            JSONObject(field.toString()).put("appliedVersion", db.aiTrackFieldVersion(id, field.getString("column")))
        }))
    }
}

internal fun aiMetadataUndoMatches(currentVersion: Long, appliedVersion: Long, current: String?, applied: String?) =
    currentVersion == appliedVersion && current == applied

private fun SQLiteDatabase.overrideValue(id: String, column: String): String? = rawQuery("SELECT $column FROM songs WHERE id=?", arrayOf(id))
    .use { if (it.moveToFirst()) it.getString(0) else null }
private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key)
private fun MusicTrack.metadataDisplay(field: String): String = when (field) { "title" -> title; "artists" -> artists; else -> album }
private fun MusicTrack.aiMetadataSession(): String {
    if (source == MusicSource.LOCAL) return "local"
    val instance = com.musicone.sourceintegration.SourcePackageController.current()?.running(source.id) ?: error("音源未启用")
    val account = instance.entry.authentication?.currentAccount()
    return "${source.id}:${instance.entry.descriptor.versionCode}:${account?.account?.id}:${account?.revision}:${account?.status}"
}

internal fun aiMetadataDetails(plan: JSONObject): List<String> =
    (if (plan.optInt("skippedCount") > 0) listOf("保留 ${plan.getInt("skippedCount")} 项后续用户修改或失效字段") else emptyList()) + plan.optJSONArray("trackChanges")?.objects().orEmpty().flatMap { edit ->
    listOf(edit.getString("title")) + edit.getJSONArray("fields").objects().map { field ->
        val label = when (field.getString("field")) { "title" -> "歌名"; "artists" -> "艺人"; else -> "专辑" }
        "$label：${field.getString("oldDisplay")} → ${if (field.isNull("new")) "恢复原始资料" else field.getString("new") }"
    }
}
