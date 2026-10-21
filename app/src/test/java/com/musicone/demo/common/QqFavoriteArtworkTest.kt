package com.musicone.demo

import org.junit.Assert.*
import org.junit.Test

class QqFavoriteArtworkTest {
    private fun track(id: String, cover: String? = "https://example.com/$id.jpg") =
        MusicTrack("qq-$id", MusicSource.QQ, id, "歌手", "专辑", 1000L, 1L, 2L, "歌", "", artworkUrl = cover)
    private fun playlist(vararg tracks: MusicTrack) = MusicPlaylist(QQ_FAVORITES_PLAYLIST_ID,
        MusicSource.QQ, "我喜欢", "", "", tracks.size, 0L, 0L, "喜", tracks.toList(),
        artworkUrl = "https://example.com/qq-heart-placeholder.jpg")

    @Test fun cachedPlatformHeartIsReplacedBeforeTheFavoriteSnapshotIsPresented() {
        val first = track("newest")
        val snapshot = QqFavoritePlaylistSnapshot()
        snapshot.record(playlist(first, track("older")))
        assertEquals(first.artworkUrl, snapshot.present(emptyMap())!!.artworkUrl)
    }

    @Test fun localAdditionsUseTheMostRecentCoverAndRemovingItRestoresTheNextCover() {
        val old = track("old")
        val firstAdded = track("first-added")
        val latest = track("latest")
        val changes = linkedMapOf(firstAdded.id to (firstAdded to true), latest.id to (latest to true))
        val updated = playlist(old).withFavoriteChanges(changes)
        assertEquals(listOf(latest, firstAdded, old), updated.tracks)
        assertEquals(latest.artworkUrl, updated.artworkUrl)
        assertEquals(firstAdded.artworkUrl, updated.withFavoriteChanges(mapOf(latest.id to (latest to false))).artworkUrl)
    }

    @Test fun aMissingLatestCoverNeverFallsBackToThePlatformHeartOrAnOlderSong() {
        val latest = track("newest", null)
        val original = playlist(latest, track("older"))
        assertNull(original.withQqFavoriteArtwork().artworkUrl)
        assertTrue(original.needsQqFirstTrackArtworkAlias())
        val resolved = latest.copy(artworkUrl = "https://example.com/resolved.jpg")
        assertEquals(resolved.artworkUrl, original.withResolvedTrackArtwork(listOf(resolved)).artworkUrl)
    }

    @Test fun ordinaryPlaylistsAndOtherSourcesKeepTheirIndependentArtwork() {
        val ordinary = playlist(track("song")).copy(id = "qq-ordinary")
        assertEquals(ordinary, ordinary.withQqFavoriteArtwork())
        val otherSource = playlist(track("song")).copy(source = MusicSource.NETEASE)
        assertEquals(otherSource, otherSource.withQqFavoriteArtwork())
    }
}
