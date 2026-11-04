package com.musicone.sourceintegration

import com.musicone.source.sdk.*
import org.junit.Assert.*
import org.junit.Test

class SourceLoadedHomePlaylistTest {
    private val source = "com.musicone.source.kugou"
    private fun collection(account: String? = "42", count: Int? = 37) = SourceCollection(
        TrackIdentity(source, account, "home-view:daily:42:20261023"), "original-daily-id", "每日推荐", "真实副标题", "真实简介", count,
        SourceArtwork("cover.jpg", 11, 22, "日"), false, false, SourcePayload("original.collection", 2, "original-payload"))
    private fun track(index: Int, account: String? = "42") = SourceTrackDetails(
        SourceTrack(TrackIdentity(source, account, "HASH-$index"), "歌曲$index", listOf("歌手"), "track-$index.jpg",
            mapOf("catalogId" to "$index", "mediaId" to "99", "private-key" to "opaque-$index", "qualityIds" to "{\"LOSSLESS\":\"FLAC-$index\"}")),
        "legacy-song-$index", "专辑", 195003, SourceArtwork("cover-$index.jpg", 33, 44, "曲"),
        listOf(SourceEntityRef("7", "歌手", "artist", SourcePayload("artist", 3, "ref-data"))),
        SourceEntityRef("99", "专辑", "album"), TrackAccessInfo(TrackAccessBadge.PURCHASE, true, false, "版权", SourceTrackBadge("购买")),
        listOf(SourceAudioQuality("LOSSLESS", "无损", "audio/flac", false, "版权", SourcePayload("quality", 1, "FLAC-$index"))),
        payload = SourcePayload("original.track", 3, "track-payload-$index"), artistsText = "歌手原文本", previewUrl = "preview-$index",
        identityAliases = listOf("alias-$index"))

    @Test fun allThirtySevenSongsAndTheirOriginalMetadataReachTheLoadedPlaylist() {
        val input = List(37) { track(it) }
        val original = collection()
        val result = loadedHomePlaylist(original, input, source, "42")
        assertEquals(37, result.tracks.size)
        assertEquals(37, result.count)
        assertEquals(input.map { it.track.identity }, result.tracks.map { it.toSourceTrack().track.identity })
        assertEquals(input.map { it.stableId }, result.tracks.map { it.toSourceTrack().stableId })
        assertEquals(original, result.toSourceCollection())
        input.zip(result.tracks).forEach { (expected, actual) ->
            val restored = actual.toSourceTrack()
            assertEquals(expected.track.title, actual.title)
            assertEquals(expected.artistsText, actual.artists)
            assertEquals(expected.album, actual.album)
            assertEquals(expected.durationMs, actual.durationMs)
            assertEquals(expected.artwork, restored.artwork)
            assertEquals(expected.artists, restored.artists)
            assertEquals(expected.albumRef, restored.albumRef)
            assertEquals(expected.track.opaqueData["private-key"], actual.providerData["private-key"])
            assertEquals(expected.track.opaqueData["catalogId"], actual.catalogId)
            assertEquals(expected.track.opaqueData["mediaId"], actual.mediaId)
            assertEquals(expected.payload, restored.payload)
            assertEquals(expected.access, restored.access)
            assertEquals(expected.qualities, restored.qualities)
            assertEquals(expected.previewUrl, actual.previewUrl)
            assertEquals(expected.identityAliases, actual.identityAliases)
        }
    }

    @Test fun missingCountUsesActualSizeAndEmptyInputDoesNotInventSongs() {
        assertEquals(37, loadedHomePlaylist(collection(count = null), List(37) { track(it) }, source, "42").count)
        val empty = loadedHomePlaylist(collection(count = null), emptyList(), source, "42")
        assertEquals(0, empty.count)
        assertTrue(empty.tracks.isEmpty())
    }

    @Test fun publicNullAccountIdentityRemainsPublicInsteadOfBeingRebound() {
        val original = track(1, null)
        val result = loadedHomePlaylist(collection(account = null), listOf(original), source, "42")
        assertNull(result.sourceAccountId)
        assertNull(result.tracks.single().sourceAccountId)
        assertEquals(original.track.identity, result.tracks.single().toSourceTrack().track.identity)
    }

    @Test fun collectionAndEverySongMustBelongToTheRequestedSourceAndAccount() {
        assertThrows(IllegalArgumentException::class.java) { loadedHomePlaylist(collection("77"), listOf(track(1)), source, "42") }
        assertThrows(IllegalArgumentException::class.java) {
            loadedHomePlaylist(collection().copy(identity = collection().identity.copy(sourceId = "other")), listOf(track(1)), source, "42")
        }
        assertThrows(IllegalArgumentException::class.java) { loadedHomePlaylist(collection(), listOf(track(1), track(2, "77")), source, "42") }
        assertThrows(IllegalArgumentException::class.java) {
            val foreign = track(2).let { it.copy(track = it.track.copy(identity = it.track.identity.copy(sourceId = "other"))) }
            loadedHomePlaylist(collection(), listOf(track(1), foreign), source, "42")
        }
        assertThrows(IllegalArgumentException::class.java) { loadedHomePlaylist(collection(), listOf(track(1)), source, null) }
    }
}
