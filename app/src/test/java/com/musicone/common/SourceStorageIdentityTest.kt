package com.musicone

import com.musicone.source.sdk.*
import com.musicone.sourceintegration.*
import org.junit.Assert.*
import org.junit.Test

class SourceStorageIdentityTest {
    private fun track(source: String, account: String = "same-account") = SourceTrackDetails(
        SourceTrack(TrackIdentity(source, account, "same-remote"), "同名曲目"), "same-stable")

    @Test fun matchingRemoteAndStableIdsStaySeparateAcrossSourcesAndAccounts() {
        val first = track("org.example.one").toMusicTrack()
        val second = track("org.example.two").toMusicTrack()
        val otherAccount = track("org.example.one", "other-account").toMusicTrack()
        assertEquals(3, setOf(first.id, second.id, otherAccount.id).size)
        assertEquals("same-stable", first.toSourceTrack().stableId)
        assertEquals("same-remote", first.toSourceTrack().track.identity.remoteId)
        assertEquals(first.id, first.toSourceTrack().toMusicTrack().id)
        assertFalse(first.toSourceTrack().track.opaqueData.containsKey(SOURCE_ORIGINAL_STABLE_ID))
    }

    @Test fun collectionRoundTripKeepsProviderIdentityWithoutSharingHostIdentity() {
        val first = SourceCollection(TrackIdentity("org.example.one", "same-account", "same-collection"),
            "same-stable", "收藏夹")
        val second = first.copy(identity = first.identity.copy(sourceId = "org.example.two"))
        assertNotEquals(first.toMusicPlaylist().id, second.toMusicPlaylist().id)
        val restored = first.toMusicPlaylist().toSourceCollection()
        assertEquals(first.identity, restored.identity)
        assertEquals(first.stableId, restored.stableId)
        assertEquals(first.toMusicPlaylist().id, restored.toMusicPlaylist().id)
    }

    @Test fun migratedSourceIdsAndExternalIdsSurviveRepeatedStorageRoundTrips() {
        for ((source, legacyId) in listOf("qq" to "qq-42", "kugou" to "kugou-42",
            "netease" to "netease-42", "bilibili" to "bilibili-BV42_1")) {
            val original = track("com.musicone.source.$source").copy(stableId = legacyId)
            assertEquals(legacyId, original.toMusicTrack().id)
            assertEquals(legacyId, original.toMusicTrack().toSourceTrack().stableId)
        }
        val original = track("org.example.one").toMusicTrack()
        val stored = original.toStoredTrackJson().toStoredTrack()!!
        assertEquals(original.id, stored.id)
        assertEquals("same-stable", stored.toSourceTrack().stableId)
    }
}
