package com.musicone

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** 使用独立数据库执行真实计划事务，不接触用户音乐库。 */
@RunWith(AndroidJUnit4::class)
class AiLibraryTransactionTest {
    @Test fun cancelledRemovalAndStaleConfirmationNeverDeleteMembers() = isolated { store, tools ->
        val id = store.createPlaylist("取消移除")
        val song = track("cancel-remove")
        store.addTracks(id, listOf(song))
        val cancelled = tools.prepareRemove(id, listOf(song))
        tools.cancel(cancelled.getString("planId"))
        assertFails { tools.confirm(cancelled.getString("planId")) }
        assertEquals(listOf(song.id), store.state.value.playlists.first { it.id == id }.tracks.map { it.id })
        val stale = tools.prepareRemove(id, listOf(song))
        store.addTracks(id, listOf(track("user-later")))
        assertFails { tools.confirm(stale.getString("planId")) }
        assertEquals(2, store.state.value.playlists.first { it.id == id }.tracks.size)
        assertNotNull(store.track(song.id))
    }

    @Test fun memberRemovalWaitsForConfirmationIsIdempotentAndUndoKeepsLaterChanges() = isolated { store, tools ->
        val id = store.createPlaylist("移除测试")
        val other = store.createPlaylist("其他歌单")
        val songs = (0 until 3).map { track("remove-$it") }
        store.addTracks(id, songs)
        store.addTracks(other, songs)
        val plan = tools.prepareRemove(id, listOf(songs[1]))
        assertEquals(songs.map { it.id }, store.state.value.playlists.first { it.id == id }.tracks.map { it.id })
        assertEquals(1, tools.pendingPlan(plan.getString("planId"))!!.changes.single().removedTracks.size)
        tools.confirm(plan.getString("planId"))
        val version = store.database().aiVersion(id)
        tools.confirm(plan.getString("planId"))
        assertEquals(version, store.database().aiVersion(id))
        assertNotNull(store.track(songs[1].id))
        assertEquals(3, store.state.value.playlists.first { it.id == other }.tracks.size)
        val later = track("later-after-remove")
        store.addTracks(id, listOf(later))
        store.renamePlaylist(id, "用户改名")
        val undo = tools.prepareUndo(plan.getString("operationId"))
        tools.confirm(undo.getString("planId"))
        val restored = store.state.value.playlists.first { it.id == id }
        assertEquals("用户改名", restored.title)
        assertEquals((songs + later).map { it.id }, restored.tracks.map { it.id })
    }

    @Test fun removalUndoDoesNotOverwriteMemberRemovedAndReaddedByUser() = isolated { store, tools ->
        val song = track("remove-readd")
        store.addTracks(UNIFIED_PLAYLIST_ID, listOf(song))
        val plan = tools.prepareRemove(UNIFIED_PLAYLIST_ID, listOf(song))
        tools.confirm(plan.getString("planId"))
        store.addTracks(UNIFIED_PLAYLIST_ID, listOf(song))
        store.removeTrack(UNIFIED_PLAYLIST_ID, song.id)
        assertFails { tools.prepareUndo(plan.getString("operationId")) }
        assertNotNull(store.track(song.id))
    }

    @Test fun persistedRemotePagesCompareCompletelyAndDifferenceCanCreatePlaylist() = isolated { store, tools ->
        val local = tools.registerResult((0 until 4000).map { track("batch-$it") }, "本地全量")
        fun pagination(input: String, next: String?) = org.json.JSONObject().put("collectionKey", "qq:account:playlist")
            .put("playlistRef", "remote-ref").put("inputCursor", input).put("nextCursor", next ?: org.json.JSONObject.NULL)
        val first = tools.registerResult((0 until 100).map { track("batch-$it") }, "远端首批", false, 121, pagination = pagination("", "remote-next"))
        val last = tools.registerResult((100 until 121).map { track("batch-$it") }, "远端末批", false, 121, pagination = pagination("remote-next", null))
        val recreated = AiLibraryTools(InstrumentationRegistry.getInstrumentation().targetContext, store)
        val firstPage = recreated.readResult(first.getString("handle"))
        assertEquals("read_result", firstPage.getJSONObject("continuation").getString("tool"))
        assertEquals("remote_tracks", firstPage.getJSONObject("remoteContinuation").getString("tool"))
        assertEquals("remote-next", firstPage.getJSONObject("remoteContinuation").getJSONObject("arguments").getString("cursor"))
        assertFails { recreated.compareResults("difference", listOf(local.getString("handle")), listOf(last.getString("handle"))) }
        val diff = recreated.compareResults("difference", listOf(local.getString("handle")), listOf(first.getString("handle"), last.getString("handle")))
        assertEquals(3879, diff.getInt("count"))
        val tracks = recreated.resolveResultHandles(listOf(diff.getString("handle")))
        assertEquals("batch-121", tracks.first().id)
        assertEquals("batch-3999", tracks.last().id)
        val plan = recreated.prepareCreatePlaylist("综合－QQ喜欢", tracks)
        val created = recreated.confirm(plan.getString("planId"))
        assertEquals(3879, store.state.value.playlists.first { it.id == created.getString("createdPlaylistId") }.tracks.size)
    }

    @Test fun preparationIsReadOnlyAndConfirmationIsIdempotent() = isolated { store, tools ->
        val id = store.createPlaylist("原名称")
        val other = store.createPlaylist("另一歌单")
        val plan = tools.prepareRename(mapOf(id to "新名称", other to "我的精选"))
        assertEquals("原名称", store.state.value.playlists.first { it.id == id }.title)
        val planId = plan.getString("planId")
        tools.confirm(planId)
        val revision = store.database().aiVersion(id)
        tools.confirm(planId)
        assertEquals(revision, store.database().aiVersion(id))
        assertEquals("我的精选", store.state.value.playlists.first { it.id == other }.title)
    }

    @Test fun unifiedPlaylistCannotBeRenamedButKeepsMemberEditing() = isolated { store, tools ->
        assertFails { tools.prepareRename(mapOf(UNIFIED_PLAYLIST_ID to "我的精选")) }
        assertFails { store.renamePlaylist(UNIFIED_PLAYLIST_ID, "我的精选") }
        assertEquals("综合歌单", store.state.value.unified.title)
        assertTrue(store.database().aiEditable(UNIFIED_PLAYLIST_ID))
    }

    @Test fun previouslyRenamedUnifiedPlaylistIsRestored() = isolated { store, _ ->
        val db = store.database()
        db.execSQL("UPDATE playlists SET title='我的精选' WHERE id=?", arrayOf(UNIFIED_PLAYLIST_ID))
        db.restoreUnifiedPlaylistTitle()
        db.rawQuery("SELECT title FROM playlists WHERE id=?", arrayOf(UNIFIED_PLAYLIST_ID)).use {
            assertTrue(it.moveToFirst())
            assertEquals("综合歌单", it.getString(0))
        }
    }

    @Test fun staleBatchDoesNotPartiallyRenameAnyPlaylist() = isolated { store, tools ->
        val first = store.createPlaylist("一")
        val second = store.createPlaylist("二")
        val plan = tools.prepareRename(linkedMapOf(first to "改一", second to "改二"))
        store.renamePlaylist(second, "用户修改")
        assertFails { tools.confirm(plan.getString("planId")) }
        assertEquals("一", store.state.value.playlists.first { it.id == first }.title)
        assertEquals("用户修改", store.state.value.playlists.first { it.id == second }.title)
    }

    @Test fun undoPreservesPreexistingAndLaterAddedMembers() = isolated { store, tools ->
        val id = store.createPlaylist("列表")
        val original = track("original")
        val added = track("ai")
        val later = track("later")
        store.addTracks(id, listOf(original))
        val plan = tools.prepareAdd(id, listOf(original, added, added))
        assertEquals(2, plan.getJSONArray("changes").getJSONObject(0).getInt("duplicateCount"))
        tools.confirm(plan.getString("planId"))
        store.addTracks(id, listOf(later))
        val undo = tools.prepareUndo(plan.getString("operationId"))
        tools.confirm(undo.getString("planId"))
        assertEquals(setOf(original.id, later.id), store.state.value.playlists.first { it.id == id }.tracks.map { it.id }.toSet())
    }

    @Test fun undoDoesNotEraseRemovedThenReaddedMemberOrLaterRename() = isolated { store, tools ->
        val id = store.createPlaylist("原名")
        val song = track("song")
        val addition = tools.prepareAdd(id, listOf(song))
        tools.confirm(addition.getString("planId"))
        store.removeTrack(id, song.id)
        store.addTracks(id, listOf(song))
        assertFails { tools.prepareUndo(addition.getString("operationId")) }
        val rename = tools.prepareRename(mapOf(id to "AI名称"))
        tools.confirm(rename.getString("planId"))
        store.renamePlaylist(id, "用户名称")
        store.renamePlaylist(id, "AI名称")
        assertFails { tools.prepareUndo(rename.getString("operationId")) }
        assertEquals(listOf(song.id), store.state.value.playlists.first { it.id == id }.tracks.map { it.id })
    }

    @Test fun subscribedAndDerivedPlaylistsAreReadOnlyAndPermissionsRechecked() = isolated { store, tools ->
        val id = store.createPlaylist("本地")
        val pending = tools.prepareRename(mapOf(id to "修改"))
        store.database().execSQL("UPDATE playlists SET source_id='remote' WHERE id=?", arrayOf(id))
        assertFails { tools.confirm(pending.getString("planId")) }
        assertFails { tools.prepareAdd(id, listOf(track("song"))) }
        assertFails { tools.prepareRename(mapOf(ALL_LOCAL_PLAYLIST_ID to "不允许")) }
        assertFails { tools.prepareRename(mapOf(PHONE_STORAGE_PLAYLIST_ID to "不允许")) }
    }

    @Test fun persistentResultsReadOnlyRequestedPageAndResolveAfterRecreation() = isolated { store, tools ->
        val result = tools.registerResult((0 until 250).map { track("track-$it") }, "大列表")
        val second = AiLibraryTools(InstrumentationRegistry.getInstrumentation().targetContext, store)
        val page = second.readResult(result.getString("handle"), "100", 10)
        assertEquals(250, page.getInt("count"))
        assertEquals(10, page.getJSONArray("items").length())
        val ref = page.getJSONArray("items").getJSONObject(0).getString("trackRef")
        assertEquals("track-100", second.resolveTracks(listOf(ref)).single().id)
        assertEquals("result:${result.getString("handle")}:110", page.getString("cursor"))
    }

    @Test fun resultHandlesExpandAll121TracksInOrderBeforeNativeConfirmation() = isolated { store, tools ->
        val first = tools.registerResult((0 until 100).map { track("batch-$it") }, "远端第一页", false, 121)
        val last = tools.registerResult((100 until 121).map { track("batch-$it") }, "远端末页", false, 121)
        val args = org.json.JSONObject().put("resultHandles", org.json.JSONArray(listOf(first.getString("handle"), last.getString("handle"))))
        val selected = tools.resolveSelection(args)
        assertEquals((0 until 121).map { "batch-$it" }, selected.map { it.id })
        val plan = tools.prepareAdd(UNIFIED_PLAYLIST_ID, selected)
        assertEquals(0, store.state.value.unified.tracks.size)
        tools.confirm(plan.getString("planId"))
        assertEquals(121, store.state.value.unified.tracks.size)
    }

    @Test fun metadataRejectsQqCapabilityAndAcceptsSubscribedBilibiliLocally() = isolated { store, tools ->
        val controller = com.musicone.sourceintegration.SourcePackageController.get(InstrumentationRegistry.getInstrumentation().targetContext)
        controller.awaitStartup()
        assertFalse(MusicSource.QQ.supportsLibraryEditing)
        assertTrue(MusicSource.BILIBILI.supportsLibraryEditing)
        val qq = track("qq-metadata").copy(source = MusicSource.QQ)
        store.saveTrack(qq)
        assertFails { tools.prepareEditTracks(listOf(qq to mapOf("title" to "不允许"))) }
        val account = controller.running(MusicSource.BILIBILI.id)?.entry?.authentication?.currentAccount()?.account?.id
        val bili = track("bili-metadata").copy(source = MusicSource.BILIBILI, sourceAccountId = account,
            providerData = mapOf("payload" to "原音源数据"))
        val id = store.createPlaylist("订阅收藏")
        store.addTracks(id, listOf(bili))
        store.database().execSQL("UPDATE playlists SET source_id=?,source_account=? WHERE id=?",
            arrayOf(MusicSource.BILIBILI.id, account.orEmpty(), id))
        store.refresh()
        val original = store.defaultTrack(bili.id)!!.toStoredTrackJson().toString()
        val plan = tools.prepareEditTracks(listOf(bili to mapOf("title" to "本地标题", "artists" to "本地艺人", "album" to "本地专辑")))
        assertEquals(bili.title, store.track(bili.id)!!.title)
        tools.confirm(plan.getString("planId"))
        assertEquals("本地标题", store.track(bili.id)!!.title)
        assertEquals("本地艺人", store.track(bili.id)!!.artists)
        assertEquals("本地专辑", store.track(bili.id)!!.album)
        assertEquals(original, store.defaultTrack(bili.id)!!.toStoredTrackJson().toString())
        assertEquals(bili.providerData, store.track(bili.id)!!.providerData)
        assertFails { tools.prepareAdd(id, listOf(track("forbidden-member"))) }
    }

    @Test fun staleMetadataBatchRejectsAllWritesAndKeepsUserOverride() = isolated { store, tools ->
        val first = track("metadata-one")
        val second = track("metadata-two")
        store.saveTrack(first)
        store.saveTrack(second)
        val plan = tools.prepareEditTracks(listOf(first to mapOf("title" to "AI一"), second to mapOf("title" to "AI二")))
        store.updateOverrides(second.id, "用户标题", null, null)
        assertFails { tools.confirm(plan.getString("planId")) }
        assertEquals(first.title, store.track(first.id)!!.title)
        assertEquals("用户标题", store.track(second.id)!!.title)
        assertEquals("pending", tools.pendingPlan(plan.getString("planId"))!!.status)
    }

    @Test fun metadataUndoPreservesTitleEditedAwayAndBackButRestoresUntouchedArtist() = isolated { store, tools ->
        val song = track("metadata-undo")
        store.saveTrack(song)
        val plan = tools.prepareEditTracks(listOf(song to mapOf("title" to "AI标题", "artists" to "AI艺人")))
        tools.confirm(plan.getString("planId"))
        store.updateOverrides(song.id, "用户标题", "AI艺人", null)
        store.updateOverrides(song.id, "AI标题", "AI艺人", null)
        val undo = tools.prepareUndo(plan.getString("operationId"))
        assertEquals(1, undo.getInt("skippedCount"))
        tools.confirm(undo.getString("planId"))
        assertEquals("AI标题", store.track(song.id)!!.title)
        assertEquals(song.artists, store.track(song.id)!!.artists)
    }

    @Test fun metadataPermissionFailureDuringBatchPreparationLeavesNoPartialChangesOrPlan() = isolated { store, tools ->
        val allowed = track("metadata-allowed")
        val denied = track("metadata-denied").copy(source = MusicSource.QQ)
        store.saveTrack(allowed)
        store.saveTrack(denied)
        tools.directory()
        val before = store.database().rawQuery("SELECT COUNT(*) FROM ai_library_plans", null).use { it.moveToFirst(); it.getInt(0) }
        assertFails { tools.prepareEditTracks(listOf(allowed to mapOf("title" to "AI标题"), denied to mapOf("title" to "禁止标题"))) }
        assertEquals(allowed.title, store.track(allowed.id)!!.title)
        assertEquals(denied.title, store.track(denied.id)!!.title)
        val after = store.database().rawQuery("SELECT COUNT(*) FROM ai_library_plans", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(before, after)
    }

    @Test fun unsavedBilibiliMetadataCreatesOnlyLocalDisplayRecordAfterConfirmationAndUndoKeepsIt() = isolated { store, tools ->
        val controller = com.musicone.sourceintegration.SourcePackageController.get(InstrumentationRegistry.getInstrumentation().targetContext)
        controller.awaitStartup()
        val account = controller.running(MusicSource.BILIBILI.id)?.entry?.authentication?.currentAccount()?.account?.id
        val remote = track("unsaved-bili-result").copy(source = MusicSource.BILIBILI, sourceAccountId = account,
            providerData = mapOf("remotePayload" to "原音源数据"))
        val result = tools.registerResult(listOf(remote), "远端搜索结果")
        val item = tools.readResult(result.getString("handle")).getJSONArray("items").getJSONObject(0)
        assertTrue(item.getBoolean("metadataEditable"))
        val membersBefore = store.database().rawQuery("SELECT COUNT(*) FROM members", null).use { it.moveToFirst(); it.getInt(0) }
        val plan = tools.prepareEditTracks(listOf(remote to mapOf("title" to "本地显示标题", "album" to "本地显示专辑")))
        assertNull(store.track(remote.id))
        tools.confirm(plan.getString("planId"))
        assertEquals("本地显示标题", store.track(remote.id)!!.title)
        assertEquals("本地显示专辑", store.track(remote.id)!!.album)
        assertEquals(remote.toStoredTrackJson().toString(), store.defaultTrack(remote.id)!!.toStoredTrackJson().toString())
        assertEquals(remote.providerData, store.track(remote.id)!!.providerData)
        val membersAfter = store.database().rawQuery("SELECT COUNT(*) FROM members", null).use { it.moveToFirst(); it.getInt(0) }
        assertEquals(membersBefore, membersAfter)
        val undo = tools.prepareUndo(plan.getString("operationId"))
        tools.confirm(undo.getString("planId"))
        assertNotNull(store.track(remote.id))
        assertEquals(remote.title, store.track(remote.id)!!.title)
        assertEquals(remote.album, store.track(remote.id)!!.album)
        val denied = track("unsaved-qq-result").copy(source = MusicSource.QQ)
        assertFails { tools.prepareEditTracks(listOf(denied to mapOf("title" to "禁止"))) }
        assertNull(store.track(denied.id))
    }

    @Test fun metadataPlanForUnsavedTrackNeverOverwritesRecordSavedBeforeConfirmation() = isolated { store, tools ->
        val remote = track("unsaved-concurrent")
        val plan = tools.prepareEditTracks(listOf(remote to mapOf("title" to "AI覆盖")))
        store.saveTrack(remote.copy(title = "后来保存的真实资料"))
        assertFails { tools.confirm(plan.getString("planId")) }
        assertEquals("后来保存的真实资料", store.track(remote.id)!!.title)
    }

    @Test fun createAndCancelPlansNeverWritePlaylistOrSongsBeforeConfirmation() = isolated { store, tools ->
        val song = track("create-only-after-confirm")
        val plan = tools.prepareCreatePlaylist("临时精选标题", listOf(song, song))
        val id = plan.getJSONArray("changes").getJSONObject(0).getString("playlistId")
        assertFalse(store.database().aiDeletable(id))
        assertNull(store.track(song.id))
        tools.cancel(plan.getString("planId"))
        assertFails { tools.confirm(plan.getString("planId")) }
        assertFalse(store.database().aiDeletable(id))
        assertNull(store.track(song.id))
        val accepted = tools.prepareCreatePlaylist("临时精选标题", listOf(song, song))
        val receipt = tools.confirm(accepted.getString("planId"))
        val createdId = receipt.getString("createdPlaylistId")
        assertEquals("临时精选标题", receipt.getString("createdPlaylistTitle"))
        assertEquals(listOf(song.id), store.state.value.playlists.first { it.id == createdId }.tracks.map { it.id })
        tools.confirm(accepted.getString("planId"))
        assertEquals(1, store.state.value.playlists.count { it.id == createdId })
    }

    @Test fun deleteRejectsBuiltinsAndPersistedRemoteTypesRegardlessOfTitle() = isolated { store, tools ->
        tools.directory()
        listOf(UNIFIED_PLAYLIST_ID, PHONE_STORAGE_PLAYLIST_ID, ALL_LOCAL_PLAYLIST_ID).forEach { id ->
            assertFails { tools.prepareDeletePlaylist(id) }
        }
        val local = store.createPlaylist("综合歌单")
        assertTrue(store.database().aiDeletable(local))
        val remote = store.createPlaylist("普通自建歌单")
        store.database().execSQL("UPDATE playlists SET source_collection='remote-identity' WHERE id=?", arrayOf(remote))
        assertFalse(store.database().aiDeletable(remote))
        assertFails { tools.prepareDeletePlaylist(remote) }
        val subscribed = store.createPlaylist("订阅")
        store.database().execSQL("UPDATE playlists SET remote_folder=123 WHERE id=?", arrayOf(subscribed))
        assertFails { tools.prepareDeletePlaylist(subscribed) }
    }

    @Test fun deleteAndRestorePreserveSongsLocationsAndExactMembership() = isolated { store, tools ->
        val id = store.createPlaylist("可恢复")
        val song = track("delete-keeps-song")
        store.addTracks(id, listOf(song))
        val db = store.database()
        val beforeSong = db.song(song.id)!!.toStoredTrackJson().toString()
        db.execSQL("INSERT INTO locations(song_id,uri,relative_path,size) VALUES(?,?,?,?)", arrayOf<Any>(song.id, "content://test/song", "原目录/歌曲.flac", 42))
        val plan = tools.prepareDeletePlaylist(id)
        assertTrue(db.aiDeletable(id))
        assertTrue(db.aiMemberExists(id, song.id))
        tools.confirm(plan.getString("planId"))
        assertFalse(db.aiDeletable(id))
        assertEquals(beforeSong, db.song(song.id)!!.toStoredTrackJson().toString())
        assertEquals(1, db.rawQuery("SELECT COUNT(*) FROM locations WHERE song_id=?", arrayOf(song.id)).use { it.moveToFirst(); it.getInt(0) })
        val undo = tools.prepareUndo(plan.getString("operationId"))
        assertFalse(db.aiDeletable(id))
        tools.confirm(undo.getString("planId"))
        assertTrue(db.aiDeletable(id))
        assertEquals(listOf(song.id), store.state.value.playlists.first { it.id == id }.tracks.map { it.id })
    }

    @Test fun deletionCancellationAndLaterPermissionChangeKeepOriginalPlaylist() = isolated { store, tools ->
        val id = store.createPlaylist("原歌单")
        val cancelled = tools.prepareDeletePlaylist(id)
        tools.cancel(cancelled.getString("planId"))
        assertTrue(store.database().aiDeletable(id))
        val pending = tools.prepareDeletePlaylist(id)
        store.database().execSQL("UPDATE playlists SET source_id='remote' WHERE id=?", arrayOf(id))
        assertFails { tools.confirm(pending.getString("planId")) }
        assertEquals("pending", tools.pendingPlan(pending.getString("planId"))!!.status)
        assertEquals("原歌单", store.state.value.playlists.first { it.id == id }.title)
    }

    @Test fun createUndoRejectsLaterEditsAndRestoreRejectsReusedIdentity() = isolated { store, tools ->
        val created = tools.prepareCreatePlaylist("新建", listOf(track("new-member")))
        val id = tools.confirm(created.getString("planId")).getString("createdPlaylistId")
        val undo = tools.prepareUndo(created.getString("operationId"))
        store.renamePlaylist(id, "后来改名")
        assertFails { tools.confirm(undo.getString("planId")) }
        assertFails { tools.prepareUndo(created.getString("operationId")) }
        val deleted = tools.prepareDeletePlaylist(id)
        tools.confirm(deleted.getString("planId"))
        val restore = tools.prepareUndo(deleted.getString("operationId"))
        store.database().execSQL("INSERT INTO playlists(id,title,position) VALUES(?,?,?)", arrayOf(id, "同ID后续歌单", 0))
        assertFails { tools.confirm(restore.getString("planId")) }
        assertEquals("同ID后续歌单", store.database().rawQuery("SELECT title FROM playlists WHERE id=?", arrayOf(id)).use { it.moveToFirst(); it.getString(0) })
    }

    @Test fun createConfirmationRejectsChangedSongSnapshotAndAccountValidationWithoutPartialWrites() = isolated { store, _ ->
        var allowed = true
        val tools = AiLibraryTools(InstrumentationRegistry.getInstrumentation().targetContext, store) { require(allowed) { "账号已改变" } }
        val song = track("create-account")
        val plan = tools.prepareCreatePlaylist("新建", listOf(song))
        val id = plan.getJSONArray("changes").getJSONObject(0).getString("playlistId")
        allowed = false
        assertFails { tools.confirm(plan.getString("planId")) }
        assertFalse(store.database().aiDeletable(id))
        assertNull(store.track(song.id))
        allowed = true
        store.saveTrack(song.copy(title = "后来保存资料"))
        assertFails { tools.confirm(plan.getString("planId")) }
        assertFalse(store.database().aiDeletable(id))
    }

    private suspend fun assertFails(block: suspend () -> Any?) {
        try { block(); fail("预期拒绝不安全操作") } catch (_: IllegalArgumentException) { }
    }
    private fun track(id: String) = MusicTrack(id, MusicSource.LOCAL, id, "歌手", "专辑", 1000, 0, 0, "", "")
    private fun isolated(block: suspend (MusicLibraryStore, AiLibraryTools) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "ai-library-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = folder
            override fun getDatabasePath(name: String): File = File(folder, name)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
                SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
                SQLiteDatabase.openDatabase(getDatabasePath(name).path, factory, SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
        val constructor = MusicLibraryStore::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val store = constructor.newInstance(context)
        try {
            store.refresh()
            block(store, AiLibraryTools(context, store))
        } finally {
            store.database().close()
            folder.deleteRecursively()
        }
    }
}
