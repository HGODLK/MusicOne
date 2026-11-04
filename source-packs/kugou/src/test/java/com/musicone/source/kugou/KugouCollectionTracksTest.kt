package com.musicone.source.kugou

import com.musicone.source.sdk.SourceCursorRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import com.musicone.source.sdk.SourceFetchPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class KugouCollectionTracksTest {
    @get:Rule val temporary = TemporaryFolder()
    private inner class Storage {
        private val directory = temporary.newFolder()
        val files = mutableMapOf<String, String>()
        var writes = 0
        fun open(file: File) = object : KugouCollectionTracksStore {
            override fun read() = files[file.name]
            override fun write(text: String, check: () -> Unit) {
                check(); file.createNewFile(); files[file.name] = text; writes++
            }
            override fun delete() { files.remove(file.name); file.delete() }
        }
        fun cache(mapper: (JSONObject, Boolean) -> MusicTrack = { row, vip -> row.toKugouTrack(vip) }) =
            KugouCollectionTracks(directory, ::open, mapper)
    }
    private fun target(version: Long? = 5, count: Int = 3, id: String = "3", favorite: Boolean = true): KugouManagedCollection {
        val raw = JSONObject().put("listid", id).put("count", count)
        version?.let { raw.put("list_ver", it) }
        return KugouManagedCollection(raw, MusicPlaylist("kugou-cloudlist:$id", title = "目录", count = count,
            isOwned = true, isPlatformFavorites = favorite), 99)
    }
    private fun rows(count: Int = 3) = (1..count).map { index -> JSONObject().put("ID", index)
        .put("hash", "hash-$index").put("hq_hash", "high-$index").put("filename", "歌手 - 歌曲$index")
        .put("album_audio_id", index).put("album_id", 8).put("duration", 180) }

    @Test fun cachedFavoritesRemainReadableWhileCloudRefreshIsWaiting() = runBlocking {
        for (coldMemory in listOf(false, true)) {
            val cache = Storage().cache()
            val list = target()
            cache.read("42", 1, list, {}, { rows() })
            if (coldMemory) cache.clearMemory()
            val release = CompletableDeferred<Unit>()
            val refresh = async(start = CoroutineStart.UNDISPATCHED) {
                cache.read("42", 1, list, {}, SourceFetchPolicy.REFRESH) {
                    release.await()
                    rows().onEach { it.put("filename", "歌手 - 已更新歌曲") }
                }
            }
            try {
                val cached = withTimeout(1_000) {
                    cache.read("42", 1, list, {}, { error("已有有效缓存不能等待或重复联网") })
                }
                assertEquals(3, cached.count)
                assertFalse(refresh.isCompleted)
            } finally { release.complete(Unit) }
            val refreshed = refresh.await()
            assertSame(refreshed, cache.read("42", 1, list, {}, { error("应复用后台核对结果") }))
        }
    }

    @Test fun changedVersionWaitsForMatchingRefreshInsteadOfReturningOldCache() = runBlocking {
        val cache = Storage().cache()
        cache.read("42", 1, target(), {}, { rows() })
        val list = target(version = 6, count = 4)
        val release = CompletableDeferred<Unit>()
        val refresh = async(start = CoroutineStart.UNDISPATCHED) {
            cache.read("42", 1, list, {}, SourceFetchPolicy.REFRESH) { release.await(); rows(4) }
        }
        val read = async(start = CoroutineStart.UNDISPATCHED) {
            cache.read("42", 1, list, {}, { error("在途刷新完成后应复用同一快照") })
        }
        try { assertFalse(read.isCompleted) } finally { release.complete(Unit) }
        assertSame(refresh.await(), read.await())
        assertEquals(4, read.await().count)
    }

    @Test fun accountGuardStillRejectsCacheDuringBackgroundRefresh() = runBlocking<Unit> {
        val cache = Storage().cache()
        val list = target()
        cache.read("42", 1, list, {}, { rows() })
        val release = CompletableDeferred<Unit>()
        val refresh = async(start = CoroutineStart.UNDISPATCHED) {
            cache.read("42", 1, list, {}, SourceFetchPolicy.REFRESH) { release.await(); rows() }
        }
        try {
            try {
                cache.read("42", 1, list, { throw CancellationException("账号已切换") }, { rows() })
                fail("缓存命中也必须核对账号")
            } catch (_: CancellationException) { }
        } finally { release.complete(Unit) }
        refresh.await()
    }

    @Test fun favoriteCardAndDetailUseNewestTrackCoverWithoutDirectoryFallback() = runBlocking {
        for (newestHasCover in listOf(true, false)) {
            val cache = Storage().cache { row, vip ->
                row.toKugouTrack(vip).copy(artworkUrl =
                    if (row.getInt("ID") == 3 && !newestHasCover) null else "https://example.com/${row.getInt("ID")}.jpg")
            }
            val list = target()
            val snapshot = cache.read("42", 1, list, {}, { rows() })
            val collection = KugouSourceMapping.collection(list.playlist.copy(artworkUrl = "https://example.com/old.jpg"), "42")
            val expected = if (newestHasCover) "https://example.com/3.jpg" else null
            assertEquals(expected, snapshot.withFavoriteArtwork(collection).artwork.url)
            assertEquals(expected, snapshot.page(list, false, SourceCursorRequest(limit = 1)).items.first().artwork.url)
            val ordinary = collection.copy(accountFavorites = false)
            assertEquals(ordinary, snapshot.withFavoriteArtwork(ordinary))
        }
    }

    @Test fun explicitRefreshReadsCloudEvenWhenVersionAndCountAreUnchanged() = runBlocking {
        val cache = Storage().cache()
        val list = target()
        var loads = 0
        val load: suspend () -> List<JSONObject> = { loads++; rows() }
        cache.read("42", 1, list, {}, load)
        cache.read("42", 1, list, {}, load)
        assertEquals(1, loads)
        cache.read("42", 1, list, {}, com.musicone.source.sdk.SourceFetchPolicy.REFRESH, load)
        assertEquals(2, loads)
        cache.read("42", 1, list, {}, load)
        assertEquals(2, loads)
    }

    @Test fun unchangedDirectoryReusesCompleteSnapshotAcrossLaunchesAndVipIsMappedAtReadTime() = runBlocking {
        val storage = Storage()
        var loads = 0
        val cache = storage.cache()
        val first = cache.read("42", 1, target(), {}, { loads++; rows() })
        cache.read("42", 1, target(), {}, { loads++; rows() })
        val restarted = storage.cache().read("42", 2, target(), {}, { loads++; rows() })
        assertEquals(1, loads)
        assertEquals(1, storage.writes)
        assertEquals(3, restarted.count)
        assertEquals(first.playlist(target(), false), restarted.playlist(target(), false))
        assertFalse(storage.files.values.single().contains("test-token"))
    }

    @Test fun favoritesKeepGlobalIdDescendingOrderAndCursorCoversAll2012WithoutChangingResources() = runBlocking {
        val raw = rows(2012)
        val target = target(count = 2012)
        val snapshot = Storage().cache().read("42", 1, target, {}, { raw })
        val playlist = snapshot.playlist(target, false)
        assertEquals(raw.last().toKugouTrack(false), playlist.tracks.first())
        val found = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = snapshot.page(target, false, SourceCursorRequest(cursor, 100))
            found += page.items.map { it.stableId }
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals((2012 downTo 1).map { "kugou-hash-$it" }, found)
        assertEquals(raw.mapNotNull { it.kugouFavoriteHash() }.map { "kugou-$it" }.toSet(), snapshot.favoriteTrackIds())
        val ordinary = this@KugouCollectionTracksTest.target(favorite = false, count = 2012)
        assertEquals(raw.last().toKugouTrack(false), snapshot.playlist(ordinary, false).tracks.first())
    }

    @Test fun largeSnapshotOnlyProjectsRequestedPageAndKeepsCurrentMetadata() = runBlocking {
        val raw = rows(2507).onEach { it.put("paytype", 3) }
        var regularCalls = 0
        var vipCalls = 0
        val cache = Storage().cache { row, vip ->
            if (vip) vipCalls++ else regularCalls++
            row.toKugouTrack(vip)
        }
        val original = target(count = 2507)
        val snapshot = cache.read("42", 1, original, {}, { raw })
        assertEquals(2507, regularCalls)
        assertEquals(0, vipCalls)
        snapshot.page(original, true, SourceCursorRequest(limit = 100))
        assertEquals(100, vipCalls)
        snapshot.page(original, false, SourceCursorRequest(limit = 100))
        assertEquals(2607, regularCalls)
        for (vip in listOf(false, true)) {
            var cursor: String? = null
            val ids = mutableListOf<String>()
            do {
                val page = snapshot.page(original, vip, SourceCursorRequest(cursor, 100))
                ids += page.items.map { it.stableId }
                cursor = page.nextCursor
            } while (cursor != null)
            assertEquals((2507 downTo 1).map { "kugou-hash-$it" }, ids)
        }
        val updated = original.copy(playlist = original.playlist.copy(title = "改名", description = "新简介",
            artworkUrl = "https://example.com/new.jpg"))
        assertSame(snapshot, cache.read("42", 1, updated, {}, { error("版本未变不能重取歌曲") }))
        val playlist = snapshot.playlist(updated, false)
        assertEquals("改名", playlist.title)
        assertEquals("新简介", playlist.description)
        assertEquals("https://example.com/new.jpg", playlist.artworkUrl)
        assertEquals(raw.asReversed().map { it.toKugouTrack(false) }, playlist.tracks)
    }

    @Test fun clearingAccountMemoryRetainsCompleteDiskDataAndReloadsAnotherInstance() = runBlocking {
        val storage = Storage()
        val cache = storage.cache()
        val original = cache.read("42", 1, target(), {}, { rows() })
        val files = storage.files.toMap()
        cache.clearMemory()
        val restored = cache.read("42", 2, target(), {}, { error("换号释放内存不能删除磁盘快照") })
        assertNotSame(original, restored)
        assertEquals(original.playlist(target(), false), restored.playlist(target(), false))
        assertEquals(files, storage.files)
        val otherList = target(id = "4")
        cache.read("42", 2, otherList, {}, { rows() })
        assertNotSame(restored, cache.read("42", 2, target(), {}, { error("离开歌单后应从完整磁盘快照恢复") }))
    }

    @Test fun legacySnapshotLoadsAndTruncatedRowSnapshotCannotPretendComplete() = runBlocking {
        val storage = Storage()
        val legacy = JSONObject().put("schema", 1).put("account", "42").put("listId", "3")
            .put("version", "5:3").put("rows", org.json.JSONArray(rows())).toString()
        storage.files["account-42-list-3.json"] = legacy
        assertEquals(3, storage.cache().read("42", 1, target(), {}, { error("旧快照应继续可读") }).count)
        val cache = storage.cache()
        cache.read("42", 1, target(), {}, com.musicone.source.sdk.SourceFetchPolicy.REFRESH) { rows() }
        val complete = storage.files.getValue("account-42-list-3.json")
        storage.files["account-42-list-3.json"] = complete.trimEnd().substringBeforeLast('\n')
        var loads = 0
        assertEquals(3, storage.cache().read("42", 2, target(), {}, { loads++; rows() }).count)
        assertEquals(1, loads)
    }

    @Test fun streamCancellationDoesNotPublishOrOverwriteAnEarlierSnapshot() = runBlocking {
        val storage = Storage()
        val cache = storage.cache()
        cache.read("42", 1, target(), {}, { rows() })
        val before = storage.files.toMap()
        var active = true
        try {
            cache.readStream("42", 1, target(6), { if (!active) throw CancellationException("账号已变化") },
                com.musicone.source.sdk.SourceFetchPolicy.REFRESH) { accept ->
                accept(rows().first())
                active = false
                cache.clearMemory()
                accept(rows().last())
            }
            fail("换号后不能继续构造或保存旧账号快照")
        } catch (_: CancellationException) { }
        assertEquals(before, storage.files)
        assertEquals(1, storage.writes)
    }

    @Test fun streamedFingerprintKeepsExistingCursorFormatIncludingEscapedUnicodeRows() = runBlocking {
        val raw = rows().onEach { it.put("filename", "中文 / 引号\" 换行\n 表情🎵") }
        val fingerprint = KugouProtocol.md5("5:3" + org.json.JSONArray(raw.map { it.toString() }).toString())
        val scope = KugouProtocol.md5("42:3:$fingerprint")
        val snapshot = Storage().cache().read("42", 1, target(), {}, { raw })
        assertEquals("kgct1:$scope:1", snapshot.page(target(), false, SourceCursorRequest(limit = 1)).nextCursor)
    }

    @Test fun cancellationDuringPageMappingStopsBeforeTheRemainingRowsAreProjected() = runBlocking {
        var projections = 0
        val snapshot = Storage().cache { row, vip -> projections++; row.toKugouTrack(vip) }
            .read("42", 1, target(), {}, { rows() })
        var checks = 0
        assertThrows(CancellationException::class.java) {
            snapshot.page(target(), true, SourceCursorRequest(limit = 3)) {
                if (++checks == 2) throw CancellationException("账号已切换")
            }
        }
        assertEquals(4, projections)
    }

    @Test fun actualVersionOrCountChangeReloadsAndOldCursorCannotReadNewSnapshotOrAnotherAccount() = runBlocking {
        val cache = Storage().cache()
        var loads = 0
        val old = cache.read("42", 1, target(), {}, { loads++; rows() })
        val cursor = old.page(target(), false, SourceCursorRequest(limit = 1)).nextCursor!!
        val changed = target(version = 6, count = 4)
        val fresh = cache.read("42", 1, changed, {}, { loads++; rows(4) })
        assertThrows(IllegalArgumentException::class.java) { fresh.page(changed, false, SourceCursorRequest(cursor, 1)) }
        val other = cache.read("77", 1, target(), {}, { loads++; rows() })
        assertThrows(IllegalArgumentException::class.java) { other.page(target(), false, SourceCursorRequest(cursor, 1)) }
        val countOnly = target(version = 6, count = 5)
        cache.read("42", 1, countOnly, {}, { loads++; rows(5) })
        assertEquals(4, loads)
    }

    @Test fun missingVersionOnlyReusesSameDirectoryObjectAndNeverPersists() = runBlocking {
        val storage = Storage()
        val cache = storage.cache()
        var loads = 0
        val firstRound = target(version = null)
        cache.read("42", 1, firstRound, {}, { loads++; rows() })
        cache.read("42", 1, firstRound, {}, { loads++; rows() })
        cache.read("42", 1, target(version = null), {}, { loads++; rows() })
        cache.read("42", 2, firstRound, {}, { loads++; rows() })
        assertEquals(3, loads)
        assertEquals(0, storage.writes)
        assertTrue(storage.files.isEmpty())
    }

    @Test fun failureCancellationAccountChangeAndInvalidListNeverOverwriteValidSnapshot() = runBlocking {
        val storage = Storage()
        val cache = storage.cache()
        cache.read("42", 1, target(), {}, { rows() })
        val previous = storage.files.toMap()
        for (failure in listOf(IllegalStateException("网络失败"), CancellationException("取消"))) {
            try { cache.read("42", 1, target(6), {}, { throw failure }); fail("应抛出原异常") }
            catch (actual: Exception) { assertSame(failure, actual) }
        }
        try { cache.read("42", 1, target(6), {}, { listOf(JSONObject()) }); fail("无效歌曲不能落盘") }
        catch (_: PlatformApiException) { }
        var current = true
        try {
            cache.read("42", 1, target(6), { check(current) { "账号已变化" } }, { current = false; rows() })
            fail("换号不能提交")
        } catch (_: IllegalStateException) { }
        assertEquals(previous, storage.files)
        assertEquals(1, storage.writes)
    }

    @Test fun staleDirectoryCountReturnsSuccessfulFullResponseButNeverCachesItAsValidatedVersion() = runBlocking {
        val storage = Storage()
        val cache = storage.cache()
        cache.read("42", 1, target(), {}, { rows() })
        val previous = storage.files.toMap()
        var loads = 0
        val stale = target(version = 6, count = 3)
        repeat(2) {
            val fresh = cache.read("42", 1, stale, {}, { loads++; rows(4) })
            assertEquals(4, fresh.count)
        }
        assertEquals(2, loads)
        assertEquals(previous, storage.files)
        assertEquals(1, storage.writes)
    }

    @Test fun invalidationOnlyRemovesSpecifiedAccountOrList() = runBlocking {
        val storage = Storage()
        val cache = storage.cache()
        var loads = 0
        suspend fun load(account: String, id: String = "3") = cache.read(account, 1, target(id = id), {}, { loads++; rows() })
        load("42"); load("42", "4"); load("77")
        cache.invalidate("42", "3")
        load("42", "4"); load("77"); load("42")
        assertEquals(4, loads)
        cache.invalidate("42")
        load("77"); load("42"); load("42", "4")
        assertEquals(6, loads)
    }
}
