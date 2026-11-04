package com.musicone.source.kugou

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class KugouCollectionClientTest {
    @Test fun accountChangeAfterEntryPageStopsBeforeLoadingAnotherPage() {
        var current = true
        var calls = 0
        val client = KugouCollectionClient(object : KugouCollectionTransport {
            override fun post(session: KugouSession, path: String, body: JSONObject, extra: Map<String, String>,
                encrypted: Boolean, router: String?): JSONObject {
                calls++
                current = false
                return JSONObject().put("status", 1).put("data", JSONObject().put("info",
                    JSONArray((1..300).map { JSONObject().put("ID", it).put("FileHash", "HASH$it") })))
            }
        })
        assertThrows(CancellationException::class.java) {
            client.entries(cookie, target()) { if (!current) throw CancellationException("账号已变化") }
        }
        assertEquals(1, calls)
    }

    @Test fun observedAccountDirectorySeparatesFortySixSavedListsAndTwelveAlbums() {
        val rows = (1..67).map { n ->
            val raw = JSONObject().put("listid", n).put("name", "目录$n")
                .put("list_create_userid", if (n <= 9) 42 else 77).put("type", if (n <= 9) 0 else 1)
            if (n <= 2) raw.put("is_def", n)
            if (n > 55) raw.put("authors", JSONArray().put(JSONObject().put("author_id", 99)))
            kugouManagedCollection(raw, 1, "42")!!
        }
        assertEquals(8, rows.count { it.created })
        assertEquals(7, rows.count { it.writable })
        assertEquals(1, rows.count { it.playlist.isPlatformFavorites })
        assertEquals(46, rows.count { it.savedPlaylist })
    }

    @Test fun directoryOwnershipUsesCreatorAndSavedPlaylistsExcludeAlbumSubscriptions() {
        fun mapped(owner: Any, type: Int, album: Boolean = false, default: Int = 0) =
            kugouManagedCollection(JSONObject().put("listid", 10).put("name", "目录")
                .put("list_create_userid", owner).put("type", type).put("is_def", default)
                .apply { if (album) put("authors", JSONArray().put(JSONObject().put("author_id", 8))) }, 4, "42")!!
        assertTrue(mapped(42, 1).writable)
        assertFalse(mapped(42, 1).savedPlaylist)
        assertTrue(mapped("77", 0).savedPlaylist)
        assertFalse(mapped("77", 0).writable)
        assertFalse(mapped("77", 1, album = true).savedPlaylist)
        assertTrue(mapped("", 0, default = 2).playlist.isPlatformFavorites)
        assertFalse(mapped("77", 1, default = 2).playlist.isPlatformFavorites)
        assertFalse(mapped(42, 0, default = 1).savedPlaylist)
        assertFalse(mapped(42, 0, default = 1).writable)
        assertTrue(mapped(42, 0, default = 1).created)
        assertFalse(mapped(42, 0, default = 2).created)
        assertEquals("kugou-cloudlist:10", mapped("77", 1).playlist.id)
    }

    private val cookie = "userid=42; token=test-token; KUGOU_API_MID=test-mid"
    private fun target(id: String = "10") = KugouManagedCollection(JSONObject().put("listid", id)
        .put("type", 0).put("sort", 7).put("tags", "摇滚").put("pic", "custom/original.jpg"),
        MusicPlaylist("kugou-cloudlist:$id", title = "原名称", description = "原简介", isOwned = true), 18)

    @Test fun renamePreservesDescriptionTagsSortAndCover() {
        val body = kugouCollectionEditBody(target(), "新名称", "")
        assertEquals("原简介", body.getString("intro"))
        assertEquals("摇滚", body.getString("tags"))
        assertEquals(7, body.getInt("sort"))
        assertFalse(body.has("pic"))
        assertEquals(18, body.getInt("total_ver"))
        assertEquals("新简介", kugouCollectionEditBody(target(), "新名称", "新简介").getString("intro"))
    }

    @Test fun failedOrMissingStatusCannotConfirmWrite() {
        listOf(JSONObject(), JSONObject().put("status", 0), JSONObject().put("status", 1).put("errcode", 123)).forEach {
            assertThrows(PlatformApiException::class.java) { it.requireKugouCollectionSuccess() }
        }
        JSONObject().put("status", 1).put("error_code", 0).requireKugouCollectionSuccess()
    }

    @Test fun sortUsesTargetEntryIdsAndRequiresExactMembership() {
        val entries = listOf(JSONObject().put("ID", 91).put("FileHash", "AAA"),
            JSONObject().put("ID", 37).put("FileHash", "BBB"))
        val data = kugouCollectionOrderData(entries, listOf("kugou-bbb", "kugou-aaa"))
        assertEquals(37, data.getJSONObject(0).getInt("fileid"))
        assertEquals(91, data.getJSONObject(1).getInt("fileid"))
        assertThrows(IllegalArgumentException::class.java) { kugouCollectionOrderData(entries, listOf("kugou-aaa")) }
        assertThrows(IllegalArgumentException::class.java) { kugouCollectionOrderData(entries, listOf("kugou-aaa", "kugou-ccc")) }
        assertThrows(IllegalArgumentException::class.java) { kugouCollectionOrderData(entries + entries.first(), listOf("kugou-aaa", "kugou-bbb")) }
    }

    @Test fun removalReadsTargetEntriesBeforeWritingAndNeverUsesForeignFileIds() {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val client = KugouCollectionClient(object : KugouCollectionTransport {
            override fun post(session: KugouSession, path: String, body: JSONObject, extra: Map<String, String>,
                encrypted: Boolean, router: String?): JSONObject {
                calls += path to body
                return JSONObject().put("status", 1).put("data", JSONObject().put("info", JSONArray()
                    .put(JSONObject().put("FileHash", "AAA").put("ID", 1234))))
            }
        })
        client.changeTracks(cookie, target("29"), listOf(MusicTrack("kugou-aaa", title = "歌曲", artists = "歌手")), false)
        assertEquals(listOf("/v4/get_list_all_file", "/v4/delete_songs"), calls.map { it.first })
        assertTrue(calls.all { it.second.getString("listid") == "29" })
        assertEquals(1234, calls.last().second.getJSONArray("data").getJSONObject(0).getInt("fileid"))
    }

    @Test fun libraryLoadsAllPagesAndKeepsOnlyRealDefaultFavorites() {
        var count = 0
        val client = KugouCollectionClient(object : KugouCollectionTransport {
            override fun post(session: KugouSession, path: String, body: JSONObject, extra: Map<String, String>,
                encrypted: Boolean, router: String?): JSONObject {
                count++
                val items = if (body.getInt("page") == 1) (1..100).map { n -> JSONObject()
                    .put("listid", n).put("name", if (n == 1) "名字里有喜欢" else "普通歌单")
                    .put("type", 0).put("is_def", if (n == 2) 2 else 0) } else emptyList()
                return JSONObject().put("status", 1).put("data", JSONObject().put("info", JSONArray(items)))
            }
        })
        val result = client.library(cookie)
        assertEquals(2, count)
        assertEquals("kugou-cloudlist:2", result.single { it.playlist.isPlatformFavorites }.playlist.id)
        assertTrue(result.first().writable)
    }

    @Test fun repeatedPagesAreAnErrorRatherThanPartialSuccess() {
        assertThrows(IllegalStateException::class.java) {
            collectKugouCollectionPages(2, { it.toString() }) { _, _ -> listOf(1, 2) }
        }
    }

    @Test fun accountChangeDuringEntryReadPreventsRemovalAndReorderWrites() {
        listOf(false, true).forEach { reorder ->
            var accountCurrent = true
            val paths = mutableListOf<String>()
            val client = KugouCollectionClient(object : KugouCollectionTransport {
                override fun post(session: KugouSession, path: String, body: JSONObject, extra: Map<String, String>,
                    encrypted: Boolean, router: String?): JSONObject {
                    paths += path
                    accountCurrent = false
                    return JSONObject().put("status", 1).put("data", JSONObject().put("list_ver", 3)
                        .put("info", JSONArray().put(JSONObject().put("FileHash", "AAA").put("ID", 1234))))
                }
            })
            val beforeWrite = { check(accountCurrent) { "酷狗账号已变化，旧请求已取消" } }
            assertThrows(IllegalStateException::class.java) {
                if (reorder) client.reorder(cookie, target(), listOf("kugou-aaa"), beforeWrite)
                else client.changeTracks(cookie, target(), listOf(MusicTrack("kugou-aaa", title = "歌曲", artists = "歌手")), false, beforeWrite)
            }
            assertEquals(listOf("/v4/get_list_all_file"), paths)
        }
    }

    @Test fun cancellationDuringEntryReadPreventsRemovalAndReorderWrites() {
        listOf(false, true).forEach { reorder ->
            val job = Job()
            val paths = mutableListOf<String>()
            val client = KugouCollectionClient(object : KugouCollectionTransport {
                override fun post(session: KugouSession, path: String, body: JSONObject, extra: Map<String, String>,
                    encrypted: Boolean, router: String?): JSONObject {
                    paths += path
                    job.cancel()
                    return JSONObject().put("status", 1).put("data", JSONObject().put("list_ver", 3)
                        .put("info", JSONArray().put(JSONObject().put("FileHash", "AAA").put("ID", 1234))))
                }
            })
            val beforeWrite = { job.ensureActive() }
            assertThrows(CancellationException::class.java) {
                if (reorder) client.reorder(cookie, target(), listOf("kugou-aaa"), beforeWrite)
                else client.changeTracks(cookie, target(), listOf(MusicTrack("kugou-aaa", title = "歌曲", artists = "歌手")), false, beforeWrite)
            }
            assertEquals(listOf("/v4/get_list_all_file"), paths)
        }
    }

    @Test fun acknowledgedCreateWithoutResponseIdRecoversOnlyNewMatchingCollectionWithoutRepeatingWrite() {
        val title = "唯一测试歌单"
        var libraryReads = 0
        var createWrites = 0
        val paths = mutableListOf<String>()
        val client = KugouCollectionClient(object : KugouCollectionTransport {
            override fun post(session: KugouSession, path: String, body: JSONObject, extra: Map<String, String>,
                encrypted: Boolean, router: String?): JSONObject {
                paths += path
                if (path == "/cloudlist.service/v5/add_list") {
                    createWrites++
                    return JSONObject().put("status", 1).put("data", JSONObject())
                }
                assertEquals("/v7/get_all_list", path)
                libraryReads++
                val values = JSONArray().put(JSONObject().put("listid", 10).put("name", title).put("type", 0))
                if (libraryReads == 2) values.put(JSONObject().put("listid", 20).put("name", title).put("type", 0))
                return JSONObject().put("status", 1).put("data", JSONObject().put("info", values))
            }
        })
        val before = client.library(cookie).mapTo(mutableSetOf()) { it.listId }
        val responseId = client.create(cookie, title)
        assertNull(responseId)
        val result = kugouCreatedCollection(before, client.library(cookie), title, responseId)
        assertEquals("20", result.listId)
        assertEquals(1, createWrites)
        assertEquals(listOf("/v7/get_all_list", "/cloudlist.service/v5/add_list", "/v7/get_all_list"), paths)
    }

    @Test fun creationReadbackNeverUsesPreexistingSameNameOrAmbiguousNewCollections() {
        fun named(id: String, title: String = "目标", owned: Boolean = true) = target(id).let {
            it.copy(playlist = it.playlist.copy(title = title, isOwned = owned))
        }
        val existing = named("10")
        assertThrows(com.musicone.source.sdk.SourceOperationException::class.java) { kugouCreatedCollection(setOf("10"), listOf(existing), "目标", null) }
        assertThrows(com.musicone.source.sdk.SourceOperationException::class.java) {
            kugouCreatedCollection(setOf("10"), listOf(existing, named("20"), named("30")), "目标", null)
        }
        assertThrows(com.musicone.source.sdk.SourceOperationException::class.java) {
            kugouCreatedCollection(setOf("10"), listOf(existing, named("20", "其他")), "目标", null)
        }
        assertThrows(com.musicone.source.sdk.SourceOperationException::class.java) {
            kugouCreatedCollection(setOf("10"), listOf(existing, named("20", owned = false)), "目标", null)
        }
        assertThrows(com.musicone.source.sdk.SourceOperationException::class.java) {
            kugouCreatedCollection(setOf("10"), listOf(existing, named("20")), "目标", "10")
        }
        assertEquals("20", kugouCreatedCollection(setOf("10"), listOf(existing, named("20")), "目标", "20").listId)
    }

    @Test fun deletionUsesSignedBase64LegacyWireProtocolAndDecryptsAcknowledgement() {
        val key = "abc123"
        var writes = 0
        val client = KugouCollectionClient(deleteTransport = KugouCollectionDeleteTransport { request, sentCookie ->
            writes++
            assertEquals(cookie, sentCookie)
            assertEquals("cloudlist.service.kugou.com", request.headers["x-router"])
            assertEquals("application/x-www-form-urlencoded", request.headers["Content-Type"])
            val digest = KugouProtocol.md5(key)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(digest.take(16).toByteArray(), "AES"),
                IvParameterSpec(digest.takeLast(16).toByteArray()))
            val body = JSONObject(cipher.doFinal(Base64.getDecoder().decode(request.body)).toString(Charsets.UTF_8))
            assertEquals(10, body.getInt("listid"))
            assertEquals(0, body.getInt("total_ver"))
            assertEquals(1, body.getInt("type"))
            val params = URI(request.url).rawQuery.split('&').associate { part ->
                val pair = part.split('=', limit = 2)
                URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
            }
            assertTrue(params.containsKey("signature"))
            assertEquals(request.url, KugouProtocol.signedAndroidUrl("https://gateway.kugou.com/v2/delete_list",
                params - "signature", request.body))
            assertEquals("42", params["userid"])
            assertEquals("gztx", params["last_area"])
            assertEquals(256, params.getValue("p").length)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(digest.take(16).toByteArray(), "AES"),
                IvParameterSpec(digest.takeLast(16).toByteArray()))
            PlatformHttpResponse(200, cipher.doFinal("{\"status\":1,\"error_code\":0}".toByteArray()), emptyMap())
        }, deleteKey = { key })
        client.delete(cookie, target())
        assertEquals(1, writes)
    }

    @Test fun legacyDeletionStillRejectsPlatformFailureAndChecksOwnerImmediatelyBeforeSend() {
        var writes = 0
        val client = KugouCollectionClient(deleteTransport = KugouCollectionDeleteTransport { _, _ ->
            writes++
            PlatformHttpResponse(200, "{\"status\":0,\"error_code\":0}".toByteArray(), emptyMap())
        }, deleteKey = { "abc123" })
        assertThrows(PlatformApiException::class.java) { client.delete(cookie, target()) }
        assertEquals(1, writes)
        assertThrows(IllegalStateException::class.java) { client.delete(cookie, target()) { error("账号已切换") } }
        assertEquals(1, writes)
    }
}
