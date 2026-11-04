package com.musicone

import com.musicone.source.sdk.*
import com.musicone.sourceintegration.selectedSourceSubscriptionTracks
import com.musicone.sourceintegration.sourceSubscriptionOrder
import com.musicone.sourceintegration.toMusicTrack
import org.junit.Assert.*
import org.junit.Test

class SourceSubscriptionTracksTest {
    private val sourceId = "org.example.supplemental"
    private fun track(id: String) = SourceTrackDetails(SourceTrack(TrackIdentity(sourceId, "42", id), "歌曲$id"), "track-$id")
    @Test fun newMemberRemainsPlayableBeforeItsChildrenAreRequested() {
        val track = selectedSourceSubscriptionTracks(SourceSubscriptionItem("member", listOf(track("fallback")), 0), emptyList()).single()
        assertTrue(track.playable)
        assertEquals(track("fallback").toHostTrack().id, track.id)
    }
    @Test fun refreshingListKeepsExistingChildIdentitiesAndMetadata() {
        val first = track("part1").toHostTrack().copy(title = "自定义标题")
        val second = track("part2").toHostTrack()
        val incoming = SourceSubscriptionItem("member", listOf(track("fallback")), 0)
        assertEquals(listOf(first, second), selectedSourceSubscriptionTracks(incoming, listOf(first, second)))
    }
    @Test fun successfulRefreshRestoresRemoteOrderWhileKeepingSelectedChildrenAndManualTail() {
        val part1 = track("part1").toHostTrack().copy(title = "自定义标题", lyrics = listOf(TimedLyric(123, "手动歌词")))
        val part2 = track("part2").toHostTrack()
        val oldFirst = track("old-first").toHostTrack()
        val old = linkedMapOf("old-first" to listOf(oldFirst), "video" to listOf(part1, part2))
        val incoming = listOf(SourceSubscriptionItem("old-first", listOf(track("old-first")), 20),
            SourceSubscriptionItem("video", listOf(track("fallback")), 10))
        val selected = incoming.sortedBy { it.position }.flatMap { selectedSourceSubscriptionTracks(it, old[it.remoteMemberId].orEmpty()) }
        val order = sourceSubscriptionOrder(selected.map { it.id }, listOf("manual-second", "manual-first"))
        assertEquals(listOf(part1, part2, oldFirst), selected)
        assertEquals(listOf(part1.id, part2.id, oldFirst.id), order.remotePositions.keys.toList())
        assertEquals(listOf(0L, 1L, 2L), order.remotePositions.values.toList())
        assertEquals(mapOf("manual-second" to 3L, "manual-first" to 4L), order.manualPositions)
        assertFalse(order.manualOrder)
    }
    private fun SourceTrackDetails.toHostTrack() = toMusicTrack()
}
