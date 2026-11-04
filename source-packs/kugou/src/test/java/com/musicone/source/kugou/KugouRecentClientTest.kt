package com.musicone.source.kugou

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouRecentClientTest {
    private val cookie = "userid=42; token=private-test-token; KUGOU_API_MID=mid"
    private fun item(id: Int, time: Long, pc: Int = 1) = JSONObject().put("mxid", id).put("ot", time).put("pc", pc)
        .put("info", JSONObject().put("hash", "hash-$id").put("mixsongid", id).put("album_id", 88)
            .put("name", "歌手 - 歌曲$id").put("singername", "歌手").put("timelen", 180000))
    private fun response(items: List<JSONObject>, next: String? = null) = JSONObject().put("status", 1).put("error_code", 0)
        .put("data", JSONObject().put("songs", JSONArray(items)).apply { next?.let { put("bp", it) } })

    @Test fun readsAllCloudPagesWithoutRankingDepthLimitOrFakeTotal() = runBlocking {
        var calls = 0
        val client = KugouRecentClient(KugouContentClient(KugouContentTransport { request, sentCookie ->
            calls++
            assertEquals(cookie, sentCookie)
            assertEquals("/playhistory/v1/get_songs", request.path)
            assertNull(request.router)
            assertFalse(request.publicCatalog)
            val body = requireNotNull(request.body)
            assertEquals("42", body.getString("userid"))
            assertEquals("private-test-token", body.getString("token"))
            assertEquals("app", body.getString("source_classify"))
            assertEquals(1, body.getInt("to_subdivide_sr"))
            assertFalse(body.has("page"))
            assertFalse(body.has("pagesize"))
            if (calls == 1) assertFalse(body.has("bp")) else assertEquals("opaque-$calls", body.getString("bp"))
            response(((calls - 1) * 100 + 1..calls * 100).map { item(it, it.toLong(), pc = 9999) },
                if (calls < 4) "opaque-${calls + 1}" else null)
        }))
        val result = client.snapshot(cookie, false)
        assertEquals(4, calls)
        assertEquals(400, result.totalTracks)
        assertEquals(400, result.tracks.size)
        assertEquals("kugou-hash-400", result.tracks.first().stableId)
        assertTrue(result.collections.isEmpty())
        assertTrue(result.albums.isEmpty())
        assertTrue(result.radios.isEmpty())
    }

    @Test fun sortsByLastPlayedTimeAndDeduplicatesCloudSongWithoutSummingPlayCounts() = runBlocking {
        var calls = 0
        val latest = item(1, 50, 8).apply { getJSONObject("info").put("name", "歌手 - 最新资料") }
        val client = KugouRecentClient(KugouContentClient(KugouContentTransport { _, _ ->
            calls++
            if (calls == 1) response(listOf(item(1, 10, 999), item(2, 40), item(3, 0)), "next")
            else response(listOf(latest, item(4, 40)))
        }))
        val result = client.snapshot(cookie, false)
        assertEquals(4, result.totalTracks)
        assertEquals(listOf("kugou-hash-1", "kugou-hash-2", "kugou-hash-4", "kugou-hash-3"), result.tracks.map { it.stableId })
        assertEquals("最新资料", result.tracks.first().track.title)
    }

    @Test fun nestedInfoRetainsOriginalAudioIdentityResourcesAndAccess() = runBlocking {
        val raw = item(1, 50)
        raw.getJSONObject("info").put("mixsongid", 777).put("HQFileHash", "high-hash")
            .put("SQFileHash", "lossless-hash").put("PayType", 3).put("cover", "https://imge.kugou.com/480/a.jpg")
        val original = raw.getJSONObject("info").kugouPublicCollectionTrack(false)
        val client = KugouRecentClient(KugouContentClient(KugouContentTransport { _, _ -> response(listOf(raw)) }))
        val result = client.snapshot(cookie, false).tracks.single()
        val restored = KugouSourceMapping.track(result)
        assertEquals(original, restored)
        assertEquals("kugou-hash-1", result.stableId)
        assertEquals("777", result.track.opaqueData["catalogId"])
        assertEquals("88", result.track.opaqueData["mediaId"])
        assertEquals("42", result.track.identity.accountId)
        assertEquals(180000L, result.durationMs)
    }

    @Test fun mxidOnlyFillsMissingCatalogIdAndDoesNotBecomeHashIdentity() = runBlocking {
        val raw = item(77, 50).apply { getJSONObject("info").remove("mixsongid") }
        val client = KugouRecentClient(KugouContentClient(KugouContentTransport { _, _ -> response(listOf(raw)) }))
        val result = client.snapshot(cookie, false).tracks.single()
        assertEquals("kugou-hash-77", result.stableId)
        assertEquals("hash-77", result.track.identity.remoteId)
        assertEquals("77", result.track.opaqueData["catalogId"])
        assertFalse(raw.getJSONObject("info").has("mixsongid"))
    }

    @Test fun accountChangeAfterAResponsePreventsNextRequestAndResult() = runBlocking {
        var current = true
        var calls = 0
        val client = KugouRecentClient(KugouContentClient(KugouContentTransport { _, _ ->
            calls++
            current = false
            response(listOf(item(1, 50)), "next")
        }))
        assertThrows(IllegalStateException::class.java) { runBlocking { client.snapshot(cookie, false) { check(current) } } }
        assertEquals(1, calls)
    }

    @Test fun transportCancellationContinuesOutWithoutEmptySuccess() {
        val cancelled = CancellationException("取消")
        val client = KugouRecentClient(KugouContentClient(KugouContentTransport { _, _ -> throw cancelled }))
        val actual = assertThrows(CancellationException::class.java) { runBlocking { client.snapshot(cookie, false) } }
        assertSame(cancelled, actual)
    }

    @Test fun repeatedCursorEmptyContinuationAndRepeatedPageAreFailures() {
        val pages = listOf(
            listOf(response(listOf(item(1, 1)), "same"), response(listOf(item(2, 2)), "same")),
            listOf(response(emptyList(), "next")),
            listOf(response(listOf(item(1, 1)), "one"), response(listOf(item(1, 1)), "two")),
        )
        pages.forEach { script ->
            var calls = 0
            val client = KugouRecentClient(KugouContentClient(KugouContentTransport { _, _ -> script[calls++] }))
            assertThrows(PlatformApiException::class.java) { runBlocking { client.snapshot(cookie, false) } }
            assertEquals(script.size, calls)
        }
    }

    @Test fun emptyCloudHistoryIsDifferentFromMalformedOrFailedResponse() {
        val invalid = listOf(JSONObject().put("status", 0).put("data", JSONObject()),
            JSONObject().put("status", 1).put("data", JSONObject()),
            response(listOf(JSONObject().put("mxid", 1))), JSONObject().put("status", 1))
        invalid.forEach { raw ->
            val client = KugouRecentClient(KugouContentClient(KugouContentTransport { _, _ -> raw }))
            assertThrows(PlatformApiException::class.java) { runBlocking { client.snapshot(cookie, false) } }
        }
        val client = KugouRecentClient(KugouContentClient(KugouContentTransport { _, _ -> response(emptyList()) }))
        val result = runBlocking { client.snapshot(cookie, false) }
        assertEquals(0, result.totalTracks)
        assertTrue(result.tracks.isEmpty())
    }

    @Test fun officialEndMarkerStopsDespiteRetainedOrRepeatedCursor() = runBlocking {
        var calls = 0
        val client = KugouRecentClient(KugouContentClient(KugouContentTransport { _, _ ->
            calls++
            val result = response(listOf(item(calls, calls.toLong())), "retained-cursor")
            result.getJSONObject("data").put("has_more", if (calls == 1) 1 else 0).put("bp_finished", "finished")
            result
        }))
        val result = client.snapshot(cookie, false)
        assertEquals(2, calls)
        assertEquals(2, result.totalTracks)
    }

    @Test fun officialEmptyEndPageIsValidEvenWithNonemptyCursor() = runBlocking {
        var calls = 0
        val client = KugouRecentClient(KugouContentClient(KugouContentTransport { _, _ ->
            calls++
            response(emptyList(), "last-cursor").apply { getJSONObject("data").put("has_more", 0) }
        }))
        assertEquals(0, client.snapshot(cookie, false).totalTracks)
        assertEquals(1, calls)
    }

    @Test fun observedTenFullPagesThenEmptyTerminalPageReturnAllThousandSongs() = runBlocking {
        var calls = 0
        val client = KugouRecentClient(KugouContentClient(KugouContentTransport { _, _ ->
            calls++
            val rows = if (calls <= 10) ((calls - 1) * 100 + 1..calls * 100).map { item(it, it.toLong()) } else emptyList()
            response(rows, if (calls <= 10) "observed-page-$calls" else "terminal-retained-cursor-26").apply {
                getJSONObject("data").put("has_more", if (calls <= 10) 1 else 0)
            }
        }))
        val result = client.snapshot(cookie, false)
        assertEquals(11, calls)
        assertEquals(1000, result.totalTracks)
        assertEquals(1000, result.tracks.size)
        assertEquals("kugou-hash-1000", result.tracks.first().stableId)
        assertEquals("kugou-hash-1", result.tracks.last().stableId)
    }

    @Test fun officialContinuationStillRequiresProgressingCursorAndNonemptyPage() {
        val scripts = listOf(
            listOf(response(listOf(item(1, 1))).apply { getJSONObject("data").put("has_more", 1) }),
            listOf(response(emptyList(), "next").apply { getJSONObject("data").put("has_more", 1) }),
            listOf(response(listOf(item(1, 1)), "same"), response(listOf(item(2, 2)), "same"))
                .map { it.apply { getJSONObject("data").put("has_more", 1) } },
        )
        scripts.forEach { script ->
            var calls = 0
            val client = KugouRecentClient(KugouContentClient(KugouContentTransport { _, _ -> script[calls++] }))
            assertThrows(PlatformApiException::class.java) { runBlocking { client.snapshot(cookie, false) } }
            assertEquals(script.size, calls)
        }
    }
}
