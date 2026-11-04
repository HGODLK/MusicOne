package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.junit.Assert.*
import org.junit.Test

class KugouAlbumCollectionTracksTest {
    private val artwork = SourceArtwork("https://imge.kugou.com/album.jpg", 1, 2, "君")
    private val album = SourceCollection(TrackIdentity(KUGOU_SOURCE_ID, "account", "album:1773341"),
        "kugou-album:1773341", "君の名は。", artwork = artwork)
    private fun track() = SourceTrackDetails(
        SourceTrack(TrackIdentity(KUGOU_SOURCE_ID, "account", "original-hash"), "夢灯籠", listOf("RADWIMPS"),
            opaqueData = mapOf("catalogId" to "39886699", "mediaId" to "1773341", "qualityIds" to "original-quality-data")),
        "kugou-original-hash", durationMs = 128391,
        artists = listOf(SourceEntityRef("42", "RADWIMPS", "artist")),
        access = TrackAccessInfo(TrackAccessBadge.MEMBERSHIP, true, false, "原权益"),
        qualities = listOf(SourceAudioQuality("LOSSLESS", "无损", payload = SourcePayload("kugou.quality", 1, "lossless-hash"))),
        payload = SourcePayload("kugou.track", 1, "original-media-payload"),
        identityAliases = listOf("existing-alias"), previewUrl = "original-preview")

    @Test fun fillsAlbumDisplayMetadataWithoutChangingPlaybackIdentityResourcesOrAccess() {
        val original = track()
        val page = SourcePage(listOf(original), "next-page")
        val result = page.withKugouAlbumCollectionMetadata(album)
        val updated = result.items.single()
        assertEquals("君の名は。", updated.album)
        assertEquals(SourceEntityRef("1773341", "君の名は。", "album"), updated.albumRef)
        assertEquals(artwork, updated.artwork)
        assertEquals(artwork.url, updated.track.artworkUrl)
        assertEquals(page.nextCursor, result.nextCursor)
        assertEquals(original, updated.copy(album = original.album, albumRef = original.albumRef,
            artwork = original.artwork, track = updated.track.copy(artworkUrl = original.track.artworkUrl)))
        assertSame(original.track.identity, updated.track.identity)
        assertSame(original.track.opaqueData, updated.track.opaqueData)
        assertSame(original.access, updated.access)
        assertSame(original.qualities, updated.qualities)
    }

    @Test fun existingAlbumReferenceTitleAndArtworkTakePriority() {
        val original = track().copy(album = "原专辑", albumRef = SourceEntityRef("99", "原专辑", "album"),
            artwork = SourceArtwork("https://imge.kugou.com/original.jpg"),
            track = track().track.copy(artworkUrl = "https://imge.kugou.com/original.jpg"))
        assertEquals(original, SourcePage(listOf(original), null).withKugouAlbumCollectionMetadata(album).items.single())
    }

    @Test fun existingTrackArtworkIsPreservedAndSharedWithMissingDisplayArtwork() {
        val original = track().let { it.copy(track = it.track.copy(artworkUrl = "https://imge.kugou.com/track.jpg")) }
        val result = SourcePage(listOf(original), null).withKugouAlbumCollectionMetadata(album).items.single()
        assertEquals(original.track.artworkUrl, result.artwork.url)
        assertSame(original.track, result.track)
    }

    @Test fun ordinaryPlaylistsInvalidAlbumIdsAndOtherSourcesRemainUntouched() {
        val page = SourcePage(listOf(track()), "cursor")
        listOf("123", "global:1773341", "cloudlist:1773341", "album:0", "album:invalid").forEach { remote ->
            assertSame(page, page.withKugouAlbumCollectionMetadata(album.copy(identity = album.identity.copy(remoteId = remote))))
        }
        assertSame(page, page.withKugouAlbumCollectionMetadata(album.copy(identity = album.identity.copy(sourceId = "other-source"))))
    }
}
