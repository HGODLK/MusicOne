package com.musicone.demo

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PlaylistAddMembershipTest {
    private fun song(id: String, source: MusicSource = MusicSource.QQ) =
        MusicTrack(id, source, id, "", "", 0, 0, 0, "", "")
    private fun playlist(id: String, tracks: List<MusicTrack> = emptyList(), source: MusicSource = MusicSource.LOCAL) =
        MusicPlaylist(id, source, id, "", "", tracks.size, 0, 0, "", tracks)

    @Test fun existingLocalMembersAreCheckedImmediatelyAndMixedSourcesRemainIndependent() {
        val first = song("qq-1")
        val second = song("local-2", MusicSource.LOCAL)
        val status = PlaylistAddMembership { _, _ -> error("本地成员无需联网") }
        status.reset(listOf(first, second))
        val partial = playlist(UNIFIED_PLAYLIST_ID, listOf(first))
        assertFalse(status.added(partial))
        assertEquals(listOf(second), status.remaining(partial))
        assertTrue(status.added(partial.copy(tracks = listOf(second, first))))
        status.reset(listOf(second))
        assertTrue(status.added(partial.copy(tracks = listOf(second))))
    }

    @Test fun onlineBatchCheckCoversAllEligibleSongsAndDoesNotRequireOtherSources() = runBlocking {
        val a = song("qq-1")
        val b = song("qq-2")
        val local = song("local-3", MusicSource.LOCAL)
        val target = playlist("qq-123", source = MusicSource.QQ)
        val status = PlaylistAddMembership { _, selected ->
            assertEquals(listOf(a, b), selected)
            setOf(a.id)
        }
        status.reset(listOf(a, b, local))
        status.check(target)
        assertFalse(status.added(target))
        assertEquals(listOf(b), status.remaining(target))
        status.confirm(target.id, listOf(b))
        assertTrue(status.added(target))
        assertTrue(status.remaining(target).isEmpty())
    }

    @Test fun aLateMembershipReadCannotEraseAnAcknowledgedAddition() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val selected = song("qq-1")
        val target = playlist("qq-123", source = MusicSource.QQ)
        val status = PlaylistAddMembership { _, _ ->
            started.complete(Unit); release.await(); emptySet()
        }
        status.reset(listOf(selected))
        val query = async { status.check(target) }
        started.await()
        status.confirm(target.id, listOf(selected))
        release.complete(Unit); query.await()
        assertTrue(status.added(target))
    }

    @Test fun changingSongsDiscardsTheOldMembershipResultAndSuccessCheck() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val target = playlist("qq-123", source = MusicSource.QQ)
        val first = song("qq-1")
        val second = song("qq-2")
        val status = PlaylistAddMembership { _, _ ->
            started.complete(Unit); release.await(); setOf(first.id, second.id)
        }
        status.reset(listOf(first))
        val pending = async { status.check(target) }
        started.await()
        status.confirm(target.id, listOf(first))
        status.reset(listOf(second))
        release.complete(Unit); pending.await()
        assertFalse(status.added(target))
        assertTrue(status.checking.isEmpty())
    }

    @Test fun targetMembershipRequestsHaveBoundedConcurrency() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val status = PlaylistAddMembership { _, _ ->
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            delay(10)
            active.decrementAndGet()
            emptySet()
        }
        status.reset(listOf(song("qq-1")))
        (1..6).map { index -> async { status.check(playlist("qq-$index", source = MusicSource.QQ)) } }.awaitAll()
        assertEquals(2, peak.get())
        assertTrue(status.checking.isEmpty())
    }

    @Test fun normalQqMidAndNumericFeedIdentityRecognizeTheSameExistingSong() {
        val stored = song("qq-mid").copy(songMid = "mid", catalogId = "123")
        val selected = song("qq-123").copy(catalogId = "123")
        assertEquals(setOf(selected.id), existingPlaylistSelections(playlist("library-one", listOf(stored)), listOf(selected)))
        assertTrue(existingPlaylistSelections(playlist("library-one", listOf(stored)), listOf(song("local-123", MusicSource.LOCAL))).isEmpty())
    }

    @Test fun subscriptionsAndStorageMirrorsAreNeverManualAdditionTargets() {
        val ids = listOf(UNIFIED_PLAYLIST_ID, "library-own", "library-subscribed", PHONE_STORAGE_PLAYLIST_ID, ALL_LOCAL_PLAYLIST_ID)
        val snapshot = LibrarySnapshot(playlists = ids.map(::playlist),
            subscriptions = listOf(LibrarySubscription("library-subscribed", 7, "user", false)))
        assertEquals(listOf(UNIFIED_PLAYLIST_ID, "library-own"), snapshot.manualAdditionTargets().map { it.id })
    }

    @Test fun successfulRemovalIsNotRestoredByAnOlderMemberQueryAndCanBeAddedAgain() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val track = song("qq-1"); val target = playlist("qq-target", source = MusicSource.QQ)
        val status = PlaylistAddMembership { _, _ -> started.complete(Unit); release.await(); setOf(track.id) }
        status.reset(listOf(track))
        val query = async { status.check(target) }
        started.await()
        status.confirm(target.id, listOf(track), false)
        release.complete(Unit); query.await()
        assertFalse(status.added(target))
        status.confirm(target.id, listOf(track), true)
        assertTrue(status.added(target))
    }

    @Test fun partialBatchRemovalKeepsOnlyTheRemainingSongsChecked() {
        val a = song("qq-1"); val b = song("qq-2")
        val target = playlist("qq-target", source = MusicSource.QQ)
        val status = PlaylistAddMembership { _, _ -> emptySet() }
        status.reset(listOf(a, b)); status.confirm(target.id, listOf(a, b))
        status.confirm(target.id, listOf(a), false)
        assertEquals(setOf(b.id), status.members(target))
        assertEquals(listOf(a), status.remaining(target))
    }
}
