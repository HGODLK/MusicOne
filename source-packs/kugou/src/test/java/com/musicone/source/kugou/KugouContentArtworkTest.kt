package com.musicone.source.kugou

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouContentArtworkTest {
    @Test fun artistDetailsProvideBackgroundsForExistingPublicHeader() {
        val raw = JSONObject("""{"author_id":15139,"author_name":"RADWIMPS",
            "sizable_avatar":"http://singerimg.kugou.com/{size}/radwimps.jpg"}""")
        val entity = raw.toKugouContentEntity("artist")
        val backgrounds = org.json.JSONArray(entity.attributes.getValue("backgrounds"))
        assertEquals("https://singerimg.kugou.com/480/radwimps.jpg", entity.artwork.url)
        assertEquals(entity.artwork.url, backgrounds.getString(0))
        assertEquals("15139", entity.reference.id)
    }

    @Test fun artistSongCoverEnrichmentRetainsOriginalPlaybackFields() {
        val raw = JSONObject("""{"hash":"original-hash","album_audio_id":123,"album_id":1773341,
            "audio_name":"夢灯籠","author_name":"RADWIMPS",
            "album_info":{"cover":"http://imge.kugou.com/{size}/album.jpg"}}""")
        val original = raw.toKugouTrack(false)
        val enriched = raw.toKugouContentTrack(false)
        assertEquals(original.id, enriched.id)
        assertEquals(original.catalogId, enriched.catalogId)
        assertEquals(original.mediaId, enriched.mediaId)
        assertEquals(original.qualityIds, enriched.qualityIds)
        assertEquals("https://imge.kugou.com/480/album.jpg", enriched.artworkUrl)
    }

    @Test fun normalizesActualAbsoluteAndProtocolRelativeImageAddresses() {
        assertEquals("https://imge.kugou.com/stdmusic/480/cover.jpg",
            " http://imge.kugou.com/stdmusic/{size}/cover.jpg ".normalizeKugouContentArtwork())
        assertEquals("https://imge.kugou.com/a.jpg?a=1&b=2",
            "//imge.kugou.com/a.jpg?a=1&amp;b=2".normalizeKugouContentArtwork())
        assertEquals("https://imge.kugou.com/480/a.jpg",
            "https:\\/\\/imge.kugou.com\\/{size}\\/a.jpg".normalizeKugouContentArtwork())
    }

    @Test fun missingMalformedAndNonNetworkImagesStayMissing() {
        listOf(null, "", "null", "undefined", "0", "cover.jpg", "javascript:alert(1)", "data:image/png;base64,abc", "https://")
            .forEach { assertNull(it.normalizeKugouContentArtwork()) }
    }

    @Test fun searchTrackImageAndSearchAlbumImgUseTheirRealProtocolFields() {
        val track = JSONObject().put("Image", "http://imge.kugou.com/stdmusic/{size}/song.jpg")
        assertEquals("https://imge.kugou.com/stdmusic/480/song.jpg", track.kugouContentTrackArtwork())
        val album = JSONObject().put("img", "//imge.kugou.com/stdmusic/240/album.jpg")
        assertEquals("https://imge.kugou.com/stdmusic/240/album.jpg", album.kugouContentEntityArtwork("album"))
        assertEquals("https://imge.kugou.com/stdmusic/480/album.jpg", JSONObject()
            .put("sizable_cover", "https://imge.kugou.com/stdmusic/{size}/album.jpg").kugouContentEntityArtwork("album"))
    }

    @Test fun nestedAlbumAndAudioCoversAreExtractedWithoutChangingMediaData() {
        val value = JSONObject().put("hash", "original-hash").put("album_audio_id", 123)
            .put("album_info", JSONObject().put("cover", "https://imge.kugou.com/{size}/album.jpg"))
        val original = value.toString()
        assertEquals("https://imge.kugou.com/480/album.jpg", value.kugouContentTrackArtwork())
        assertEquals(original, value.toString())
        assertEquals("https://imge.kugou.com/480/audio.jpg", JSONObject().put("audio_info", JSONObject()
            .put("trans_param", JSONObject().put("union_cover", "//imge.kugou.com/{size}/audio.jpg")))
            .kugouContentTrackArtwork())
    }

    @Test fun invalidTopLevelImageDoesNotHideValidNestedCover() {
        assertEquals("https://imge.kugou.com/480/album.jpg", JSONObject().put("Image", "null")
            .put("album_info", JSONObject().put("sizable_cover", "//imge.kugou.com/{size}/album.jpg"))
            .kugouContentTrackArtwork())
    }

    @Test fun artistBackgroundUsesOnlyReturnedArtworkAndNeverInventsPortraitAddresses() {
        val artist = JSONObject().put("author_id", 42).put("sizable_avatar", "http://singerimg.kugou.com/{size}/artist.jpg")
        assertEquals(listOf("https://singerimg.kugou.com/480/artist.jpg"), artist.kugouContentArtistBackgrounds())
        assertEquals(artist.kugouContentEntityArtwork("artist"), artist.kugouContentArtistBackgrounds().single())
        assertTrue(JSONObject().put("author_id", 42).kugouContentArtistBackgrounds().isEmpty())
    }
}
