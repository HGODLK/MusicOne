package com.musicone.source.qq.internal

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MigratedHostPlaylistArtworkTest {
    private fun track(id: String, title: String = id) = MusicTrack("qq-$id", MusicSource.QQ, title,
        "歌手", "专辑", 1000L, 0L, 0L, id, "")
    private fun playlist(tracks: List<MusicTrack>) = MusicPlaylist(QQ_FAVORITES_PLAYLIST_ID,
        MusicSource.QQ, "我喜欢", "", "", tracks.size, 0L, 0L, "喜", tracks)

    @Test fun resolvedFirstTrackSuppliesMissingPlaylistArtwork() {
        val unresolved = track("1").copy(artworkUrl = null)
        val resolved = unresolved.copy(artworkUrl = "https://example.com/first-cover.jpg")

        val updated = playlist(listOf(unresolved)).withResolvedTrackArtwork(listOf(resolved))

        assertEquals(resolved.artworkUrl, updated.artworkUrl)
        assertEquals(resolved, updated.tracks.single())
    }

    @Test fun independentPlaylistArtworkIsNotOverwrittenByFirstTrack() {
        val first = track("1").copy(artworkUrl = "https://example.com/first-cover.jpg")
        val playlistCover = "https://example.com/playlist-cover.jpg"

        val updated = playlist(listOf(first)).copy(id = "qq-ordinary", artworkUrl = playlistCover)
            .withResolvedTrackArtwork(listOf(first))

        assertEquals(playlistCover, updated.artworkUrl)
    }

    @Test fun onlyMissingQqPlaylistAndFirstTrackNeedArtworkAliasResolution() {
        val missing = playlist(listOf(track("1").copy(artworkUrl = null)))
        assertTrue(missing.needsQqFirstTrackArtworkAlias())
        assertFalse(missing.copy(id = "qq-ordinary", artworkUrl = "https://example.com/playlist.jpg")
            .needsQqFirstTrackArtworkAlias())
        assertFalse(missing.copy(tracks = listOf(
            track("1").copy(artworkUrl = "https://example.com/first.jpg"),
        ))
            .needsQqFirstTrackArtworkAlias())
        assertFalse(missing.copy(source = MusicSource.NETEASE)
            .needsQqFirstTrackArtworkAlias())
    }

    @Test fun ownedPlaylistUsesLatestCanonicalTrackButPublicPlaylistKeepsItsCover() {
        val newest = track("new").copy(artworkUrl = "https://example.com/new.jpg")
        val older = track("old").copy(artworkUrl = "https://example.com/old.jpg")
        val source = playlist(listOf(newest, older)).copy(id = "qq-123", isOwned = true,
            artworkUrl = "https://example.com/directory.jpg")
        val resolved = qqPlaylistCoverSource(source).withResolvedTrackArtwork(source.tracks)
        assertEquals(newest.artworkUrl, resolved.artworkUrl)
        assertEquals(newest.artworkUrl, resolved.copy(tracks = resolved.tracks.reversed()).artworkUrl)
        val legacyCreated = source.copy(isOwned = false, providerData = mapOf("qqCoverFromLatest" to "true"))
        assertEquals(newest.artworkUrl, qqPlaylistCoverSource(legacyCreated).withResolvedTrackArtwork(legacyCreated.tracks).artworkUrl)
        val public = source.copy(isOwned = false)
        assertEquals(public.artworkUrl, qqPlaylistCoverSource(public).withResolvedTrackArtwork(public.tracks).artworkUrl)
    }

    @Test fun emptyOwnedPlaylistDoesNotUseOldIndependentCover() {
        val owned = playlist(emptyList()).copy(id = "qq-123", isOwned = true, artworkUrl = "https://example.com/old.jpg")
        assertNull(qqPlaylistCoverSource(owned).withResolvedTrackArtwork(emptyList()).artworkUrl)
    }

}
