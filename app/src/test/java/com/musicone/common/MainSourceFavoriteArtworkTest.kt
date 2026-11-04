package com.musicone

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MainSourceFavoriteArtworkTest {
    private fun track(id: String, cover: String? = "https://example.com/$id.jpg") =
        MusicTrack("qq-$id", MusicSource.QQ, id, "歌手", "专辑", 1000L, 1L, 2L, "歌", "", artworkUrl = cover)
    private fun playlist(vararg tracks: MusicTrack) = MusicPlaylist("source-favorites",
        MusicSource.QQ, "我喜欢", "", "", tracks.size, 0L, 0L, "喜", tracks.toList(),
        artworkUrl = "https://example.com/old-cover.jpg", isPlatformFavorites = true)

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

}
