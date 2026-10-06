package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QqArtistMetadataTest {
    private fun song() = JSONObject("""{"mid":"001Abc12345678","name":"测试歌曲",
        "singer":[{"mid":"singer-a","name":"甲"},{"mid":"singer-b","name":"乙"}],
        "album":{"mid":"album-mid","name":"测试专辑"}}""").toQqTrack()
    @Test fun singerIdentitiesAndAlbumSurvivePlaybackSnapshot() {
        val original = song()
        val restored = original.toQqStoredTrackJson().toQqStoredTrack()!!
        assertEquals(original.artistRefs, restored.artistRefs)
        assertEquals("album-mid", restored.albumMid)
        assertEquals("", restored.previewUrl)
    }
    @Test fun metadataEnrichmentKeepsReliableEntityIdentities() {
        val original = song()
        val old = original.copy(artistRefs = emptyList(), albumMid = "")
        assertEquals(original.artistRefs, old.mergeQqTrackMetadata(original).artistRefs)
        assertEquals(original.albumMid, old.mergeQqTrackMetadata(original).albumMid)
    }
    @Test fun absentAlbumDoesNotCreateAnAlbumAction() {
        assertNull(song().copy(albumMid = "").relatedAlbum())
        assertEquals("qq-album-album-mid", song().relatedAlbum()?.id)
    }
    @Test fun officialAlbumFieldsPreserveReleaseDateAndIdentity() {
        val album = artistAlbum(JSONObject("""{"albumMid":"abc","albumName":"专辑",
            "publishDate":"2026-09-13","singerName":"歌手","totalNum":12}"""))!!
        assertEquals("qq-album-abc", album.id)
        assertEquals("2026-09-13", album.description)
        assertEquals(12, album.count)
        assertNull(artistAlbum(JSONObject("""{"albumName":"没有标识"}""")))
    }
}
