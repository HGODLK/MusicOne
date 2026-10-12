package com.musicone.demo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class QqFeedDetailsReuseTest {
    @Test fun oneDetailRequestSuppliesArtworkAlbumAndPurchasePermissionWithoutQuality() {
        val original = track()
        var requests = 0
        val resolver = QqTrackAccessResolver(membership = { null }, details = { batch, _ ->
            requests++
            assertEquals(listOf(original.id), batch.map { it.id })
            response("feed-mid", "专辑", purchased = true)
        })
        val result = resolver.resolve(listOf(original), "uin=330001; qqmusic_key=sample;",
            metadataTrackIds = setOf(original.id)).single()
        assertEquals(1, requests)
        assertEquals("专辑", result.album)
        assertNotNull(result.artworkUrl)
        assertEquals(180_000L, result.durationMs)
        assertTrue(result.qualityIds.isEmpty())
        assertNull(result.accessBadge)
        assertEquals("330001", result.qqAccess!!.permissionAccountId)
        assertFalse(needsQqFeedMetadata(result, inSongGroup = true))
    }

    @Test fun cachedPermissionDoesNotSkipRequiredMetadataAndDoesNotChangeOtherCallers() {
        val original = track()
        val cookie = "uin=330002; qqmusic_key=sample;"
        QqEntitlements.remember(cookie, original.id, QqTrackAccessInfo(payPlay = 0, checkedAtMs = System.currentTimeMillis()))
        var requests = 0
        val resolver = QqTrackAccessResolver(membership = { null }, details = { _, _ -> requests++; response("feed-mid", "专辑") })
        assertEquals(original.album, resolver.resolve(listOf(original), cookie).single().album)
        assertEquals(0, requests)
        val prepared = resolver.resolve(listOf(original), cookie, metadataTrackIds = setOf(original.id)).single()
        assertEquals("专辑", prepared.album)
        assertEquals(1, requests)
    }

    @Test fun wrongSongDetailCannotChangeMetadataOrKnownRestriction() {
        val original = track().copy(qqAccess = QqTrackAccessInfo(payPlay = 1, payMonth = 0, trackPrice = 200),
            accessBadge = MusicAccessBadge.PAID, playable = false)
        val resolver = QqTrackAccessResolver(membership = { null }, details = { _, _ -> response("wrong-mid", "错误专辑", true) })
        val result = resolver.resolve(listOf(original), "uin=330003; qqmusic_key=sample;",
            metadataTrackIds = setOf(original.id)).single()
        assertEquals(original.album, result.album)
        assertEquals(original.artworkUrl, result.artworkUrl)
        assertEquals(MusicAccessBadge.PAID, result.accessBadge)
        assertFalse(result.playable)
    }

    @Test fun displayMetadataFailureKeepsExistingArtworkAndRestriction() {
        val original = track().copy(qqAccess = QqTrackAccessInfo(payPlay = 1, payMonth = 0, trackPrice = 200),
            accessBadge = MusicAccessBadge.PAID, artworkUrl = "https://example.test/original.jpg")
        val resolver = QqTrackAccessResolver(membership = { null }, details = { _, _ -> throw java.io.IOException("离线") })
        val result = resolver.resolve(listOf(original), "uin=330004; qqmusic_key=sample;",
            metadataTrackIds = setOf(original.id)).single()
        assertEquals(original.artworkUrl, result.artworkUrl)
        assertEquals(MusicAccessBadge.PAID, result.accessBadge)
    }

    private fun response(mid: String, album: String, purchased: Boolean = false) = JSONObject("""
        {"code":0,"access_0":{"code":0,"data":{"track_info":{
            "mid":"$mid","id":123,"name":"歌曲","interval":180,
            "album":{"mid":"album-mid","name":"$album"},
            "pay":{"pay_play":1,"pay_month":0,"price_track":200,"pay_status":${if (purchased) 1 else 0}}
        }}}}
    """)

    private fun track() = MusicTrack(
        id = "qq-feed-mid", songMid = "feed-mid", catalogId = "123", source = MusicSource.QQ,
        title = "歌曲", artists = "歌手", album = "", durationMs = 0,
        artworkStart = 0, artworkEnd = 0, artworkMark = "歌", previewUrl = "",
    )
}
