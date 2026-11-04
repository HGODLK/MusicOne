package com.musicone.source.kugou

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouArtistArtworkTest {
    private fun picture(name: String) = JSONObject().put("sizable_portrait", "https://singerimg.kugou.com/{size}/$name.jpg")
    private fun response(authorId: Int, images: JSONObject): JSONObject = JSONObject().put("status", 1).put("error_code", 0)
        .put("data", JSONArray().put(JSONArray().put(JSONObject().put("author_id", authorId).put("imgs", images))))

    @Test fun preservesPortraitOrderDeduplicatesThenMovesFirstToLast() {
        val images = JSONObject().put("3", JSONArray().put(picture("first")).put(picture("second"))
            .put(picture("first")).put(picture("third")))
        assertEquals(listOf("second", "third", "first").map { "https://singerimg.kugou.com/1080/$it.jpg" },
            response(15139, images).kugouArtistPortraits("15139"))
    }

    @Test fun realResponseShapeHasSevenDistinctPicturesAndFiltersOtherAuthors() {
        val images = JSONObject().put("2", JSONArray().put(picture("avatar")))
            .put("3", JSONArray().put(picture("wide-1")).put(picture("wide-2")))
            .put("4", JSONArray().put(picture("portrait-1")).put(picture("portrait-2"))
                .put(picture("portrait-3")).put(picture("portrait-4")))
        val raw = response(15139, images)
        raw.getJSONArray("data").getJSONArray(0).put(JSONObject().put("author_id", 42)
            .put("imgs", JSONObject().put("3", JSONArray().put(picture("wrong-author")))))
        val result = raw.kugouArtistPortraits("15139")
        assertEquals(7, result.size)
        assertEquals(7, result.distinct().size)
        assertFalse(result.any { it.contains("wrong-author") })
        assertTrue(raw.kugouArtistPortraits("999").isEmpty())
        assertTrue(JSONObject("""{"status":1,"error_code":0,"data":[]}""").kugouArtistPortraits("15139").isEmpty())
        assertThrows(PlatformApiException::class.java) {
            JSONObject("""{"status":0,"error_code":20006,"data":[]}""").kugouArtistPortraits("15139")
        }
    }

    @Test fun fetchesAnonymousRealSongSeedBeforeRequestingPortraits() = runBlocking {
        var params: Map<String, String>? = null
        val content = KugouContentClient(KugouContentTransport { request, cookie ->
            assertEquals("", cookie)
            assertTrue(request.publicCatalog)
            assertEquals("15139", request.query["author_id"])
            assertEquals("1", request.query["pagesize"])
            JSONObject("""{"status":1,"error_code":0,"data":{"songs":[{"album_audio_id":39886708,
                "audio_id":22532907,"audio_info":{"hash":"real-song-hash"}}]}}""")
        })
        val client = KugouArtistArtworkClient(KugouArtistArtworkTransport { request ->
            params = request
            response(15139, JSONObject().put("3", JSONArray().put(picture("a")).put(picture("b"))))
        })
        val result = client.backgrounds("15139", content)
        assertEquals(listOf("b", "a").map { "https://singerimg.kugou.com/1080/$it.jpg" }, result)
        val request = requireNotNull(params)
        assertFalse(request.keys.any { it in setOf("userid", "token", "cookie", "mid", "dfid") })
        val seed = JSONArray(request.getValue("data")).getJSONObject(0)
        assertEquals("real-song-hash", seed.getString("hash"))
        assertEquals("39886708", seed.getString("album_audio_id"))
        assertEquals("22532907", seed.getString("audio_id"))
    }

    @Test fun cancellationFromArtworkRequestIsNotConvertedToEmptyPictures() = runBlocking {
        val cancelled = CancellationException("取消")
        val client = KugouArtistArtworkClient(KugouArtistArtworkTransport { throw cancelled })
        try {
            client.backgrounds("15139", KugouArtistArtworkSeed("real-hash"))
            fail("应继续抛出取消")
        } catch (actual: CancellationException) {
            assertSame(cancelled, actual)
        }
    }
}
