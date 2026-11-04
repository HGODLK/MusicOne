package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouPublicCollectionTest {
    private val gid = "collection_3_42_7_0"
    private fun collection(remote: String = "global:$gid") = SourceCollection(
        TrackIdentity(KUGOU_SOURCE_ID, null, remote), "kugou-$remote", "原名称",
        payload = SourcePayload("kugou.collection", 1, JSONObject().put("global_collection_id", gid).toString()))

    @Test fun globalRoutingNeverTreatsNumericSpecialOrAccountListsAsGlobal() {
        assertEquals(gid, collection().kugouGlobalCollectionId())
        assertEquals("123", collection("global:123").kugouGlobalCollectionId())
        assertNull(collection("123").kugouGlobalCollectionId())
        assertNull(collection("album:123").kugouGlobalCollectionId())
        assertNull(collection("cloudlist:123").kugouGlobalCollectionId())
        assertEquals(gid, collection(gid).kugouGlobalCollectionId())
        assertNull(collection("unknown-global").kugouGlobalCollectionId())
    }

    @Test fun metadataUsesRealGlobalInfoEndpointAndPreservesCollectionIdentity() {
        val original = collection()
        val client = KugouPublicCollectionClient(KugouPublicCollectionTransport { path, params, body, cookie, router ->
            assertEquals("/v3/get_list_info", path)
            assertEquals("pubsongs.kugou.com", router)
            assertEquals(gid, body!!.getJSONArray("data").getJSONObject(0).getString("global_collection_id"))
            assertTrue(params.isEmpty())
            assertEquals("", cookie)
            JSONObject().put("status", 1).put("data", JSONArray().put(JSONObject()
                .put("global_collection_id", gid).put("name", "真实歌单").put("intro", "真实简介")
                .put("list_create_userid", 42).put("list_create_listid", 7).put("count", 2)))
        })
        val result = client.detail(original, "")
        assertEquals(original.identity, result.identity)
        assertEquals(original.stableId, result.stableId)
        assertEquals("真实歌单", result.title)
        assertEquals("42", JSONObject(result.payload.encoded).getString("list_create_userid"))
        assertEquals(2, result.count)
    }

    @Test fun globalTracksUseOffsetProtocolAndOriginalHashMapper() {
        var calls = 0
        val client = KugouPublicCollectionClient(KugouPublicCollectionTransport { path, params, body, _, router ->
            calls++
            assertEquals("/pubsongs/v2/get_other_list_file_nofilt", path)
            assertEquals(gid, params["global_collection_id"])
            assertEquals(if (calls == 1) "0" else "1", params["begin_idx"])
            assertEquals("1", params["pagesize"])
            assertNull(body)
            assertNull(router)
            JSONObject().put("status", 1).put("data", JSONObject().put("count", 2)
                .put("songs", JSONArray().put(track(if (calls == 1) "hash-a" else "hash-b"))))
        })
        val first = client.tracks(collection(), SourceCursorRequest(limit = 1), "", false)
        assertEquals("kugou-hash-a", first.items.single().stableId)
        assertEquals("1", first.nextCursor)
        val second = client.tracks(collection(), SourceCursorRequest(cursor = first.nextCursor, limit = 1), "", false)
        assertEquals("kugou-hash-b", second.items.single().stableId)
        assertNull(second.nextCursor)
    }

    @Test fun structuredTrackAliasesPreservePlaybackIdentifiersAndQualityHashes() {
        val raw = track("hash-a").put("albuminfo", JSONObject().put("id", 88).put("name", "专辑"))
            .put("singerinfo", JSONArray().put(JSONObject().put("id", 66).put("name", "歌手")))
            .put("relate_goods", JSONArray().put(JSONObject().put("level", 5).put("hash", "lossless-a")))
        val result = raw.kugouPublicCollectionTrack(false)
        assertEquals("kugou-hash-a", result.id)
        assertEquals("77", result.catalogId)
        assertEquals("88", result.mediaId)
        assertEquals("专辑", result.album)
        assertEquals("歌手", result.artists)
        assertEquals("lossless-a", result.qualityIds[AudioQuality.LOSSLESS])
        assertEquals("66", result.artistReferences.single().id)
    }

    @Test fun wrongPlaylistAndPrematureEmptyPageFailExplicitly() {
        assertThrows(PlatformApiException::class.java) {
            JSONObject().put("data", JSONArray().put(JSONObject().put("global_collection_id", "another-id")))
                .kugouPublicInfo(gid)
        }
        val client = KugouPublicCollectionClient(KugouPublicCollectionTransport { _, _, _, _, _ ->
            JSONObject().put("status", 1).put("data", JSONObject().put("count", 2).put("songs", JSONArray()))
        })
        assertThrows(PlatformApiException::class.java) {
            client.tracks(collection(), SourceCursorRequest(), "", false)
        }
    }

    @Test fun shieldedRowsKeep160ItemsAndRawOffsetsAcrossPages() {
        val shielded = mapOf(77 to 164, 79 to 161, 93 to 145, 123 to 99, 125 to 97, 137 to 71, 156 to 6)
        val offsets = mutableListOf<Int>()
        val client = KugouPublicCollectionClient(KugouPublicCollectionTransport { _, params, _, _, _ ->
            val offset = params.getValue("begin_idx").toInt()
            val size = params.getValue("pagesize").toInt()
            offsets += offset
            val rows = (offset until minOf(offset + size, 160)).map { index ->
                if (index in shielded) JSONObject().put("fileid", shielded.getValue(index)).put("shield", 1)
                else track("hash-$index")
            }
            JSONObject().put("status", 1).put("data", JSONObject().put("count", 160).put("songs", JSONArray(rows)))
        })
        val items = mutableListOf<SourceTrackDetails>()
        var cursor: String? = null
        do {
            val page = client.tracks(collection(), SourceCursorRequest(cursor, 50), "", false)
            items += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        assertEquals(listOf(0, 50, 100, 150), offsets)
        assertEquals(160, items.size)
        assertEquals(160, items.map { it.stableId }.distinct().size)
        assertEquals(153, items.count { it.access.playable })
        assertEquals(7, items.count { !it.access.playable })
        assertEquals("kugou-hash-78", items[78].stableId)
        shielded.keys.forEach { index ->
            val item = items[index]
            assertEquals("暂不可用的歌曲", item.track.title)
            assertFalse(item.access.playable)
            assertTrue(item.access.reason!!.contains("屏蔽"))
            assertTrue(item.qualities.isEmpty())
            assertTrue(item.track.opaqueData.isEmpty())
            assertEquals("", item.payload.encoded)
        }
    }

    @Test fun shieldedIdentityBelongsToOriginalCollectionAndCurrentAccount() {
        val row = JSONObject().put("fileid", 164).put("shield", 1)
        val first = row.kugouPublicCollectionItem(gid, "42", false)
        val again = row.kugouPublicCollectionItem(gid, "42", true)
        val otherList = row.kugouPublicCollectionItem("another-list", "42", false)
        val otherAccount = row.kugouPublicCollectionItem(gid, "77", false)
        assertEquals(first.stableId, again.stableId)
        assertNotEquals(first.stableId, otherList.stableId)
        assertEquals("42", first.track.identity.accountId)
        assertEquals("77", otherAccount.track.identity.accountId)
        assertEquals(first.stableId, "kugou-" + first.track.identity.remoteId)
    }

    @Test fun malformedRowsStillFailInsteadOfInventingPlayableSongs() {
        listOf(JSONObject().put("fileid", 164), JSONObject().put("shield", 1),
            JSONObject().put("shield", 1).put("fileid", 0)).forEach { row ->
            assertThrows(PlatformApiException::class.java) { row.kugouPublicCollectionItem(gid, null, false) }
        }
    }

    private fun track(hash: String) = JSONObject().put("hash", hash).put("name", "歌手 - 歌曲")
        .put("mixsongid", 77).put("timelen", 180000)
}
