package com.musicone.source.kugou

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouCollectionStreamingTest {
    private val cookie = "userid=42; token=test-token; KUGOU_API_MID=test-mid"
    private val target = KugouManagedCollection(JSONObject().put("listid", 3),
        MusicPlaylist("kugou-cloudlist:3", title = "大歌单", count = 2507, isOwned = true), 1)

    private fun client(load: (Int, Int) -> List<JSONObject>) = KugouCollectionClient(object : KugouCollectionTransport {
        override fun post(session: KugouSession, path: String, body: JSONObject, extra: Map<String, String>,
            encrypted: Boolean, router: String?): JSONObject {
            assertEquals("/v4/get_list_all_file", path)
            return JSONObject().put("status", 1).put("data", JSONObject().put("info",
                JSONArray(load(body.getInt("page"), body.getInt("pagesize")))))
        }
    })

    private fun row(id: Int) = JSONObject().put("ID", id).put("hash", "hash-$id")
        .put("filename", "歌手 - 歌曲$id").put("imgurl", "https://example.com/$id.jpg")

    @Test fun full2507RowsAreConsumedOnePageAtATimeWithoutTruncation() {
        var consumed = 0
        var requests = 0
        val client = client { page, size ->
            assertEquals(300, size)
            assertEquals((page - 1) * size, consumed)
            requests++
            ((page - 1) * size + 1..minOf(page * size, 2507)).map(::row)
        }
        client.visitEntries(cookie, target) { item ->
            consumed++
            assertEquals(consumed, item.getInt("ID"))
        }
        assertEquals(2507, consumed)
        assertEquals(9, requests)
    }

    @Test fun coverScanChoosesHighestIdAcrossAllPagesWithoutAssumingResponseOrder() {
        var requests = 0
        val client = client { page, _ ->
            requests++
            if (page == 1) (1..300).map { row(if (it == 77) 4000 else it) }
            else listOf(row(3500), row(9))
        }
        assertEquals("https://example.com/4000.jpg", client.latestArtwork(cookie, target))
        assertEquals(2, requests)
    }

    @Test fun switchingAccountInsidePageStopsRemainingRowsAndFurtherRequests() {
        var requests = 0
        var consumed = 0
        val client = client { _, _ -> requests++; (1..300).map(::row) }
        assertThrows(CancellationException::class.java) {
            client.visitEntries(cookie, target, {
                if (consumed == 7) throw CancellationException("账号已变化")
            }) { consumed++ }
        }
        assertEquals(7, consumed)
        assertEquals(1, requests)
    }
}
