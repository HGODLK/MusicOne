package com.musicone

import com.musicone.source.sdk.*
import org.junit.Assert.*
import org.junit.Test

class SupplementalPresentationTest {
    @Test fun collectionCardsRetainTheirArtworkAndPublicIdentity() {
        val folder = SourceRemoteCollection(TrackIdentity("org.example.supplemental", "42", "folder"), "收藏夹", 40, SourceArtwork("https://example.test/folder.jpg"))
        val playlist = folder.asSupplementalPlaylist()
        assertEquals(folder.artwork.url, playlist.artworkUrl)
        assertEquals("40 个内容", playlist.subtitle)
        assertEquals(folder.identity.sourceId, playlist.source.id)
        assertEquals(folder.identity.remoteId, playlist.remoteKey)
    }
}
