package com.musicone.source.kugou

import com.musicone.source.sdk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouSavedCollectionTracksTest {
    private val cookie = "userid=42; token=test-token; KUGOU_API_MID=test-mid"
    private val origin = "collection_3_77_30_0"
    private fun target(owned: Boolean = false, count: Int = 160, album: Boolean = false): KugouManagedCollection {
        val raw = JSONObject().put("listid", 111).put("type", if (owned) 0 else 1)
            .put("name", "已收藏歌单").put("count", count).put("list_ver", 0)
            .put("list_create_userid", if (owned) 42 else 77).put("list_create_listid", 30)
            .put("global_collection_id", "collection_3_42_111_0").put("list_create_gid", origin)
        if (album) raw.put("authors", JSONArray())
        return requireNotNull(kugouManagedCollection(raw, 1, "42"))
    }
    private fun collection(target: KugouManagedCollection) = SourceCollection(
        TrackIdentity(KUGOU_SOURCE_ID, "42", "cloudlist:111"), target.playlist.id, target.playlist.title,
        payload = SourcePayload("kugou.collection", 1, target.raw.toString()))

    private fun copyClient(data: (Int) -> JSONObject, status: Int = 1) = KugouCollectionClient(object : KugouCollectionTransport {
        override fun post(session: KugouSession, path: String, body: JSONObject, extra: Map<String, String>,
            encrypted: Boolean, router: String?): JSONObject {
            assertEquals("/v4/get_list_all_file", path)
            assertEquals("111", body.getString("listid"))
            return JSONObject().put("status", status).put("error_code", 0).put("data", data(body.getInt("page")))
        }
    })
    private fun emptyData(info: Any = JSONObject.NULL) = JSONObject().put("count", 0).put("info", info)
        .put("list_ver", 0).put("page", 1)
    private fun publicClient(visit: (Map<String, String>) -> Unit = {}) = KugouPublicCollectionClient(
        KugouPublicCollectionTransport { path, params, body, sentCookie, _ ->
            assertEquals("/pubsongs/v2/get_other_list_file_nofilt", path)
            assertEquals(origin, params["global_collection_id"])
            assertEquals(cookie, sentCookie)
            assertNull(body)
            visit(params)
            JSONObject().put("status", 1).put("data", JSONObject().put("count", 2).put("songs", JSONArray()
                .put(JSONObject().put("hash", "hash-" + params.getValue("begin_idx"))
                    .put("name", "歌手 - 歌曲").put("timelen", 180000))))
        })

    @Test fun observedNullCopyReadsOriginAndKeepsSavedIdentityAndFavoriteKeys() = runBlocking {
        val target = target()
        val collection = collection(target)
        val payload = collection.payload
        val keys = kugouCollectionFavoriteKeys(collection)
        val copy = copyClient({ emptyData() })
        val page = KugouSavedCollectionTracks(publicClient()).read(collection, target,
            SourceCursorRequest(limit = 1), cookie, false, {}) {
            copy.visitEntries(cookie, target) { fail("空副本不应交付歌曲或写入快照") }
            error("空副本必须触发原始歌单读取")
        }
        assertEquals("kugou-hash-0", page.items.single().stableId)
        assertEquals("42", page.items.single().track.identity.accountId)
        assertTrue(page.nextCursor!!.startsWith("kg-origin:"))
        assertEquals("kugou-cloudlist:111", collection.stableId)
        assertEquals("cloudlist:111", collection.identity.remoteId)
        assertEquals(payload, collection.payload)
        assertEquals(keys, kugouCollectionFavoriteKeys(collection))
        assertTrue("global:$origin" in keys)
        assertTrue("global:collection_3_42_111_0" in keys)
    }

    @Test fun subsequentPagesStayOnOriginWithoutReadingRecoveredCopy() = runBlocking {
        val target = target()
        val offsets = mutableListOf<String>()
        val reader = KugouSavedCollectionTracks(publicClient { offsets += it.getValue("begin_idx") })
        val first = reader.read(collection(target), target, SourceCursorRequest(limit = 1), cookie, false, {}) {
            throw KugouEmptySavedCollection()
        }
        val second = reader.read(collection(target), target, SourceCursorRequest(first.nextCursor, 1), cookie, false, {}) {
            error("续页不能切回收藏副本")
        }
        assertEquals(listOf("0", "1"), offsets)
        assertEquals("kugou-hash-1", second.items.single().stableId)
        assertNull(second.nextCursor)
    }

    @Test fun emptyCopyAndShieldedOriginLoadTogetherWithoutChangingFavoriteIdentity() = runBlocking {
        val target = target()
        val collection = collection(target)
        val keys = kugouCollectionFavoriteKeys(collection)
        val copy = copyClient({ emptyData() })
        val public = KugouPublicCollectionClient(KugouPublicCollectionTransport { _, params, _, _, _ ->
            assertEquals(origin, params["global_collection_id"])
            JSONObject().put("status", 1).put("data", JSONObject().put("count", 3).put("songs", JSONArray()
                .put(JSONObject().put("hash", "normal-before").put("name", "歌手 - 歌曲甲"))
                .put(JSONObject().put("fileid", 164).put("shield", 1))
                .put(JSONObject().put("hash", "normal-after").put("name", "歌手 - 歌曲乙"))))
        })
        val page = KugouSavedCollectionTracks(public).read(collection, target, SourceCursorRequest(), cookie, false, {}) {
            copy.visitEntries(cookie, target) { fail("空副本不应交付歌曲") }
            error("必须切换原始来源")
        }
        assertEquals(3, page.items.size)
        assertEquals(listOf(true, false, true), page.items.map { it.access.playable })
        assertEquals("kugou-normal-after", page.items.last().stableId)
        assertTrue(page.items.all { it.track.identity.accountId == "42" })
        assertNull(page.nextCursor)
        assertEquals(keys, kugouCollectionFavoriteKeys(collection))
        assertEquals("kugou-cloudlist:111", collection.stableId)
    }

    @Test fun normalCopyNeverCallsPublicEndpoint() = runBlocking {
        val target = target()
        val expected = SourcePage<SourceTrackDetails>(emptyList(), "kgct1:copy:50")
        val reader = KugouSavedCollectionTracks(publicClient { error("正常副本不应访问原始歌单") })
        assertSame(expected, reader.read(collection(target), target, SourceCursorRequest(), cookie, false, {}) { expected })
    }

    @Test fun onlyExplicitFirstEmptySavedCopyCanSelectOrigin() {
        val target = target()
        assertTrue(target.hasEmptySavedCopy(emptyData(), 1))
        assertTrue(target.hasEmptySavedCopy(emptyData(JSONArray()), 1))
        assertFalse(target.hasEmptySavedCopy(emptyData(), 2))
        assertFalse(target(owned = true).hasEmptySavedCopy(emptyData(), 1))
        assertFalse(target(count = 0).hasEmptySavedCopy(emptyData(), 1))
        assertFalse(target(album = true).hasEmptySavedCopy(emptyData(), 1))
        assertFalse(target.hasEmptySavedCopy(JSONObject().put("count", 0), 1))
        assertFalse(target.hasEmptySavedCopy(emptyData().put("count", 160), 1))
        assertFalse(target.hasEmptySavedCopy(emptyData("invalid"), 1))
        target.raw.put("list_create_gid", target.raw.getString("global_collection_id"))
        assertFalse(target.hasEmptySavedCopy(emptyData(), 1))
    }

    @Test fun platformAndMalformedResponseErrorsNeverSelectOrigin() {
        val target = target()
        listOf(copyClient({ emptyData() }, status = 0), copyClient({ emptyData().put("count", 160) }),
            copyClient({ JSONObject().put("count", 0) })).forEach { copy ->
            val reader = KugouSavedCollectionTracks(publicClient { error("真实失败不能触发回退") })
            val failure = assertThrows(PlatformApiException::class.java) {
                runBlocking {
                    reader.read(collection(target), target, SourceCursorRequest(), cookie, false, {}) {
                        copy.visitEntries(cookie, target) {}
                        error("异常响应不能交付成功结果")
                    }
                }
            }
            assertFalse(failure is KugouEmptySavedCollection)
        }
    }

    @Test fun originCursorCannotCrossAccountCollectionOrOrigin() = runBlocking {
        val target = target()
        val reader = KugouSavedCollectionTracks(publicClient())
        val first = reader.read(collection(target), target, SourceCursorRequest(limit = 1), cookie, false, {}) {
            throw KugouEmptySavedCollection()
        }
        val otherList = target.copy(raw = JSONObject(target.raw.toString()).put("listid", 112))
        val otherOrigin = target.copy(raw = JSONObject(target.raw.toString()).put("list_create_gid", "another-origin"))
        listOf(target to cookie.replace("userid=42", "userid=43"), otherList to cookie, otherOrigin to cookie).forEach { (value, session) ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { reader.read(collection(value), value, SourceCursorRequest(first.nextCursor), session, false, {}) {
                    error("错误游标不能读取副本")
                } }
            }
        }
    }

    @Test fun accountChangeBeforeOrAfterOriginReadDiscardsResult() {
        val target = target()
        listOf(false, true).forEach { cancelAfterRequest ->
            var current = true
            var calls = 0
            val reader = KugouSavedCollectionTracks(publicClient { calls++; current = false })
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    reader.read(collection(target), target, SourceCursorRequest(limit = 1), cookie, false,
                        { if (!current) throw CancellationException("账号已变化") }) {
                        current = cancelAfterRequest
                        throw KugouEmptySavedCollection()
                    }
                }
            }
            assertEquals(if (cancelAfterRequest) 1 else 0, calls)
        }
    }
}
