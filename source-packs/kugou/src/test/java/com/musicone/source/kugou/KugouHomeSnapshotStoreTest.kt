package com.musicone.source.kugou

import com.musicone.source.sdk.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KugouHomeSnapshotStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun completeRoundTripRetainsAudioIdentityReferencesAccessAndTrueTotals() {
        val ref = SourceEntityRef("9", "歌手", "artist", SourcePayload("artist", 2, "raw"))
        val track = SourceTrackDetails(SourceTrack(TrackIdentity(KUGOU_SOURCE_ID, "42", "HASH"), "歌曲", listOf("歌手"), "track.jpg",
            mapOf("catalogId" to "77", "mediaId" to "88", "qualityIds" to "{real}")), "legacy-stable", "专辑", 193003,
            SourceArtwork("art.jpg", 11, 22, "封"), listOf(ref), ref.copy(kind = "album"),
            TrackAccessInfo(TrackAccessBadge.MEMBERSHIP, true, false, "版权", SourceTrackBadge("VIP", 1, 2, 3, 4)),
            listOf(SourceAudioQuality("LOSSLESS", "无损", "audio/flac", false, "付费", SourcePayload("quality", 1, "HASH-FLAC"))),
            SourceLyrics(listOf(SourceLyricLine(7, "歌词", listOf(SourceLyricWord(7, 8, "词")), "翻译", "音译", 4)), "原文"),
            "local", SourcePayload("track", 3, "opaque"), "歌手原名", "preview", listOf("alias"))
        val collection = SourceCollection(TrackIdentity(KUGOU_SOURCE_ID, "42", "global:abc"), "legacy-list", "推荐歌单", "作者", "简介", 999,
            SourceArtwork("list.jpg", 3, 4, "歌"), true, false, SourcePayload("kugou.collection", 1, "{gid:real}"))
        val state = KugouHomeState(owner = KugouHomeOwner("42", 9), homeSections = listOf(SourceHomeSection("real", "推荐", listOf(track),
            listOf(collection), listOf(SourceEntity(ref, "介绍", SourceArtwork("artist.jpg"), mapOf("x" to "y"))), SourcePayload("section", 1, "real"))),
            daily = listOf(track), recent = SourceRecentSnapshot(listOf(track), 1000, listOf(collection), 17, listOf(collection), 29, listOf(collection)),
            homeLoaded = true, dailyLoaded = true, recentLoaded = true, dailyDate = "20261023")
        val store = KugouHomeSnapshotStore(temporary.newFolder()) { "20261023" }
        store.save(state)
        assertEquals(state.copy(owner = null), store.read("42"))
        assertNull(store.read("77"))
        assertNull(store.read(null))
    }

    @Test fun nextDayExpiresOnlyDailyAndRejectedWriteKeepsPreviousSnapshot() {
        val folder = temporary.newFolder()
        val today = KugouHomeSnapshotStore(folder) { "20261023" }
        val state = KugouHomeState(owner = KugouHomeOwner("42", 0), homeSections = listOf(SourceHomeSection("real", "推荐")),
            homeLoaded = true, dailyLoaded = true, recentLoaded = true, dailyDate = "20261023")
        today.save(state)
        try { today.save(state.copy(homeSections = emptyList())) { error("账号变化") }; fail() }
        catch (failure: IllegalStateException) { assertEquals("账号变化", failure.message) }
        assertEquals("real", today.read("42")!!.homeSections.single().id)
        val tomorrow = KugouHomeSnapshotStore(folder) { "20261024" }.read("42")!!
        assertFalse(tomorrow.dailyLoaded)
        assertTrue(tomorrow.homeLoaded)
        assertTrue(tomorrow.recentLoaded)
        assertEquals("real", tomorrow.homeSections.single().id)
    }
}
