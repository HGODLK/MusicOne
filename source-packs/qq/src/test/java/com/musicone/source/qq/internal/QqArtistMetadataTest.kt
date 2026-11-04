package com.musicone.source.qq.internal

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QqArtistMetadataTest {
    private fun song() = JSONObject("""{"mid":"001Abc12345678","name":"测试歌曲",
        "singer":[{"mid":"singer-a","name":"甲"},{"mid":"singer-b","name":"乙"}],
        "album":{"mid":"album-mid","name":"测试专辑"}}""").toQqTrack()
    @Test fun metadataEnrichmentKeepsReliableEntityIdentities() {
        val original = song()
        val old = original.copy(artistRefs = emptyList(), albumId = "")
        assertEquals(original.artistRefs, old.mergeQqTrackMetadata(original).artistRefs)
        assertEquals(original.albumId, old.mergeQqTrackMetadata(original).albumId)
    }
    @Test fun absentAlbumDoesNotCreateAnAlbumAction() {
        assertNull(song().copy(albumId = "").relatedAlbum())
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
