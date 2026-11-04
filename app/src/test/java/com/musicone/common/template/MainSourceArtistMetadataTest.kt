package com.musicone

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MainSourceArtistMetadataTest {
    private fun song() = MusicTrack("qq-001Abc12345678", MusicSource.QQ, "测试歌曲", "甲 / 乙", "测试专辑", 0, 0, 0, "", "",
        albumId = "album-mid", artistRefs = listOf(MusicArtist("singer-a", "甲"), MusicArtist("singer-b", "乙")),
        providerData = mapOf("songMid" to "001Abc12345678", "qqAccess" to "{\"payPlay\":1}"))
    @Test fun singerIdentitiesAndAlbumSurvivePlaybackSnapshot() {
        val original = song()
        val restored = original.toStoredTrackJson().toStoredTrack()!!
        assertEquals(original.artistRefs, restored.artistRefs)
        assertEquals("album-mid", restored.albumId)
        assertEquals("", restored.previewUrl)
        val legacy = original.toStoredTrackJson().apply {
            remove("providerData")
            remove("albumId")
            put("songMid", original.providerData["songMid"])
            put("albumMid", original.albumId)
            put("qqAccess", JSONObject(original.providerData.getValue("qqAccess")))
            put("artistRefs", org.json.JSONArray().apply {
                original.artistRefs.forEach { put(JSONObject().put("mid", it.id).put("name", it.name)) }
            })
        }.toStoredTrack()!!
        assertEquals(original.id, legacy.id)
        assertEquals(original.providerData["songMid"], legacy.providerData["songMid"])
        assertEquals(original.providerData["qqAccess"], legacy.providerData["qqAccess"])
        assertEquals(original.albumId, legacy.albumId)
        assertEquals(original.artistRefs, legacy.artistRefs)
        assertFalse(legacy.toStoredTrackJson().has("qqAccess"))
        assertFalse(legacy.toStoredTrackJson().has("songMid"))
    }
}
