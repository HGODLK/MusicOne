package com.musicone.source.kugou

import com.musicone.source.sdk.SourceEntityRef
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class KugouTrackReferencesTest {
    @Test fun legacyCloudRowsRetainRealArtistAndAlbumForExistingPublicMenu() {
        val raw = JSONObject("""{"hash":"original","hq_hash":"high","album_audio_id":42,
            "filename":"Glass Animals - Heat Waves","album_id":12,"album_name":"Dreamland",
            "singerinfo":[{"id":7,"name":"Glass Animals"}],"duration":238}""")
        val track = raw.toKugouTrack(false)
        val details = KugouSourceMapping.track(track, "account")
        assertEquals(listOf(SourceEntityRef("7", "Glass Animals", "artist")), details.artists)
        assertEquals(SourceEntityRef("12", "Dreamland", "album"), details.albumRef)
        assertEquals("kugou-original", track.id)
        assertEquals("42", track.catalogId)
        assertEquals("12", track.mediaId)
        assertEquals(mapOf(AudioQuality.STANDARD to "original", AudioQuality.EXHIGH to "high"), track.qualityIds)
    }

    @Test fun nestedMetadataAndMultipleArtistsOnlyFillMissingNavigationFields() {
        val original = JSONObject("""{"hash":"original","filename":"A - Song"}""").toKugouTrack(false)
        val metadata = JSONObject("""{"authors":[{"base":{"author_id":7,"author_name":"A"}},
            {"base":{"author_id":8,"author_name":"B"}},{"author_id":7,"author_name":"A"}],
            "album_info":{"album_id":12,"album_name":"Album"}}""")
        val enriched = original.withKugouTrackReferences(metadata)
        assertEquals(listOf("7", "8"), enriched.artistReferences.map { it.id })
        assertEquals(SourceEntityRef("12", "Album", "album"), enriched.albumReference)
        assertEquals(original, enriched.copy(artistReferences = original.artistReferences, albumReference = original.albumReference))
        val existing = original.copy(artistReferences = listOf(SourceEntityRef("99", "Existing", "artist")),
            albumReference = SourceEntityRef("100", "Existing album", "album"))
        assertEquals(existing, existing.withKugouTrackReferences(metadata))
    }

    @Test fun zeroInvalidOrMissingIdsNeverBecomeNameBasedNavigation() {
        val raw = JSONObject("""{"hash":"original","filename":"A - Song","authors":[],
            "singerinfo":[{"id":0,"name":"A"},{"id":"not-an-id","name":"B"}],"album_id":0}""")
        val track = raw.toKugouTrack(false)
        assertTrue(track.artistReferences.isEmpty())
        assertNull(track.albumReference)
    }
}
