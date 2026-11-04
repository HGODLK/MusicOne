package com.musicone.sourceintegration

import android.content.ContentValues
import com.musicone.*
import com.musicone.source.sdk.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 全量成功分页后一次提交，远端成员与本地选择的子项分别保留身份。 */
internal class SourceSubscriptionPersistence(private val store: MusicLibraryStore) {
    fun revision(identity: SourceSubscriptionIdentity): Long = store.database().rawQuery(
        "SELECT source_id,source_account,source_collection,source_revision FROM playlists WHERE id=?",
        arrayOf(identity.localCollectionId),
    ).use { row ->
        check(row.moveToFirst()) { "本地歌单不存在" }
        if (row.isNull(0)) row.getLong(3) else {
            check(row.getString(0) == identity.sourceId && row.getString(1) == identity.accountId && row.getString(2) == identity.remoteCollectionId) { "订阅关系已变化" }
            row.getLong(3)
        }
    }

    suspend fun commit(identity: SourceSubscriptionIdentity, expectedRevision: Long, items: List<SourceSubscriptionItem>) {
        require(identity.localCollectionId !in setOf(UNIFIED_PLAYLIST_ID, PHONE_STORAGE_PLAYLIST_ID, ALL_LOCAL_PLAYLIST_ID)) { "请为订阅选择独立歌单" }
        val requestContext = currentCoroutineContext()
        store.importTransaction(onCommitted = {}) { db ->
            requestContext.ensureActive()
            check(revision(identity) == expectedRevision) { "订阅关系已变化，请重新刷新" }
            val old = linkedMapOf<String, MutableList<MusicTrack>>()
            db.rawQuery("SELECT song_id,source_member FROM members WHERE playlist_id=? AND remote_member=1 ORDER BY position,rowid", arrayOf(identity.localCollectionId)).use { rows ->
                while (rows.moveToNext()) {
                    val member = rows.getString(1) ?: continue
                    store.defaultTrack(rows.getString(0))?.let { old.getOrPut(member) { mutableListOf() }.add(it) }
                }
            }
            val selected = items.sortedBy { it.position }.distinctBy { it.remoteMemberId }.map { item ->
                item.remoteMemberId to selectedSourceSubscriptionTracks(item, old[item.remoteMemberId].orEmpty())
            }
            val remoteIds = selected.flatMap { it.second }.mapTo(hashSetOf()) { it.id }
            val removed = mutableListOf<String>()
            db.rawQuery("SELECT song_id FROM members WHERE playlist_id=? AND remote_member=1 AND hidden=0", arrayOf(identity.localCollectionId)).use { rows ->
                while (rows.moveToNext()) if (rows.getString(0) !in remoteIds) removed += rows.getString(0)
            }
            removed.forEach { db.delete("members", "playlist_id=? AND song_id=?", arrayOf(identity.localCollectionId, it)) }
            val manual = mutableListOf<String>()
            db.rawQuery("SELECT song_id FROM members WHERE playlist_id=? AND remote_member=0 ORDER BY position,rowid", arrayOf(identity.localCollectionId)).use { rows -> while (rows.moveToNext()) manual += rows.getString(0) }
            val order = sourceSubscriptionOrder(selected.flatMap { it.second }.map { it.id }, manual)
            selected.forEach { (member, tracks) -> tracks.forEach { track ->
                val position = order.remotePositions.getValue(track.id)
                db.upsertSong(track)
                db.insertWithOnConflict("members", null, ContentValues().apply {
                    put("playlist_id", identity.localCollectionId); put("song_id", track.id); put("position", position)
                    put("added_at", System.currentTimeMillis()); put("remote_member", 1); put("source_member", member)
                }, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
                db.execSQL("UPDATE members SET position=?,source_member=? WHERE playlist_id=? AND song_id=? AND remote_member=1", arrayOf<Any>(position, member, identity.localCollectionId, track.id))
            } }
            order.manualPositions.forEach { (id, position) -> db.execSQL("UPDATE members SET position=? WHERE playlist_id=? AND song_id=?", arrayOf<Any>(position, identity.localCollectionId, id)) }
            db.update("playlists", ContentValues().apply {
                put("source_id", identity.sourceId); put("source_account", identity.accountId)
                put("source_collection", identity.remoteCollectionId); put("source_revision", expectedRevision + 1)
                put("manual_order", if (order.manualOrder) 1 else 0)
            }, "id=?", arrayOf(identity.localCollectionId))
            requestContext.ensureActive()
        }
    }

    suspend fun remove(identity: SourceSubscriptionIdentity, keepTracks: Boolean) = store.importTransaction(onCommitted = {}) { db ->
        revision(identity)
        if (!keepTracks) db.delete("members", "playlist_id=? AND remote_member=1", arrayOf(identity.localCollectionId))
        db.execSQL("UPDATE playlists SET source_id=NULL,source_account=NULL,source_collection=NULL,source_revision=source_revision+1,remote_folder=NULL,owner=NULL WHERE id=?", arrayOf(identity.localCollectionId))
        db.execSQL("DELETE FROM members WHERE playlist_id=? AND hidden=1", arrayOf(identity.localCollectionId))
        db.execSQL("UPDATE members SET remote_member=0,source_member=NULL WHERE playlist_id=?", arrayOf(identity.localCollectionId))
    }
}

/** 远端成员保留已选子项目；初次导入才采用音源提供的默认曲目。 */
internal fun selectedSourceSubscriptionTracks(item: SourceSubscriptionItem, previous: List<MusicTrack>): List<MusicTrack> =
    previous.takeIf { it.isNotEmpty() } ?: item.tracks.map { it.toMusicTrack() }
