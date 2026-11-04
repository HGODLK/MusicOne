package com.musicone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrackPlaybackAvailabilityTest {
    private val song = MusicTrack("qq-song", MusicSource.QQ, "歌曲", "歌手", "专辑",
        200_000, 0, 0, "歌", "", accessBadge = MusicAccessBadge.PAID)
    private val now = 1_800_000_000_000L
    @Test fun oldFlagsAndPaymentBadgesCannotDisableUnknownTracks() {
        for (source in listOf(MusicSource.QQ, MusicSource.NETEASE, MusicSource.KUGOU)) {
            val shown = displayedTrackAvailability(song.copy(source = source, playable = false,
                unavailableReason = "历史失败"), null, false)
            assertTrue(shown.playable)
            assertNull(shown.unavailableReason)
            assertEquals(MusicAccessBadge.PAID, shown.accessBadge)
        }
    }

    @Test fun onlyConfirmedDenialDisablesAndOfflineAudioTakesPriority() {
        assertFalse(displayedTrackAvailability(song, "明确无权限", false).playable)
        assertTrue(displayedTrackAvailability(song, "明确无权限", true).playable)
        val local = song.copy(source = MusicSource.LOCAL, playable = false, unavailableReason = "文件缺失")
        assertEquals(local, displayedTrackAvailability(local, null, false))
    }

    @Test fun offlineDisablesIncompleteAudioAndCompleteAudioOverridesOnlineDenial() {
        for (source in listOf(MusicSource.QQ, MusicSource.KUGOU, MusicSource.NETEASE, MusicSource.BILIBILI)) {
            val track = song.copy(source = source)
            assertFalse(displayedTrackAvailability(track, null, false, online = false).playable)
            val cached = displayedTrackAvailability(track, "在线无权限", true, online = false)
            assertTrue(cached.playable)
            assertNull(cached.unavailableReason)
        }
        val local = song.copy(source = MusicSource.LOCAL, playable = false)
        assertEquals(local, displayedTrackAvailability(local, null, true, online = false))
    }

    @Test fun removedAndPendingSourcesDisableEvenCompleteOfflineAudioWithoutChangingMetadata() {
        val original = song.copy(providerData = mapOf("artistId" to "preserved"))
        for (status in listOf(QueueSourceStatus.MISSING, QueueSourceStatus.PENDING_REMOVAL,
            QueueSourceStatus.UNAVAILABLE)) {
            for (online in listOf(true, false)) {
                val disabled = displayedTrackAvailability(original, null, offline = true,
                    online = online, sourceStatus = status)
                assertFalse(disabled.playable)
                assertEquals(status.message(original.source), disabled.unavailableReason)
                assertEquals(original, disabled.copy(playable = original.playable,
                    unavailableReason = original.unavailableReason))
            }
        }
        assertTrue(original.playable)
    }

    @Test fun initializationDoesNotGrayUnknownSongsAndUndoImmediatelyRestoresCachedPlayback() {
        assertTrue(displayedTrackAvailability(song, null, offline = false,
            sourceStatus = QueueSourceStatus.INITIALIZING).playable)
        val disabled = displayedTrackAvailability(song, null, offline = true,
            sourceStatus = QueueSourceStatus.PENDING_REMOVAL)
        val restored = displayedTrackAvailability(disabled, null, offline = true,
            sourceStatus = QueueSourceStatus.AVAILABLE)
        assertEquals(song, restored)
        val local = song.copy(source = MusicSource.LOCAL, playable = false, unavailableReason = "本地文件缺失")
        assertEquals(local, displayedTrackAvailability(local, null, offline = true,
            sourceStatus = QueueSourceStatus.PENDING_REMOVAL))
    }

    @Test fun returningToPurchasedSongRefreshesWithoutWaitingForOldFailureToExpire() = runBlocking {
        var requests = 0
        val store = TrackAvailabilityStore(this) { requests++; null }
        store.report(song, 0, "未购买")
        store.refresh(song)
        assertEquals(1, requests)
        assertTrue(store.state.value.unavailable.isEmpty())
    }

    @Test fun unknownRefreshClearsOldDenialAndConfirmedRefreshCanRestoreIt() = runBlocking {
        val store = TrackAvailabilityStore(this) { throw java.io.IOException("暂时离线") }
        store.report(song, 0, "旧权限")
        store.refresh(song)
        assertTrue(store.state.value.unavailable.isEmpty())
        val deniedStore = TrackAvailabilityStore(this) { "当前仍无权限" }
        deniedStore.report(song, 0, "旧权限")
        deniedStore.refresh(song)
        assertEquals("当前仍无权限", deniedStore.state.value.unavailable[song.id])
    }

    @Test fun duplicateDisplayChecksShareOneRequest() = runBlocking {
        var requests = 0
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String?>()
        val store = TrackAvailabilityStore(this) { requests++; entered.complete(Unit); response.await() }
        val legacy = song.copy(playable = false)
        store.report(legacy, 0, "旧权限")
        val first = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(legacy) }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { store.refresh(legacy) }
        response.complete(null)
        first.await()
        second.await()
        assertEquals(1, requests)
    }

    @Test fun accountChangeRejectsLateCheckAndLatePlaybackResult() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String?>()
        val store = TrackAvailabilityStore(this) { entered.complete(Unit); response.await() }
        store.report(song, 0, "旧账号无权限")
        val check = async { store.refresh(song) }
        entered.await()
        store.reset()
        response.complete("旧账号仍无权限")
        check.await()
        store.report(song, 0, "迟到的播放失败")
        assertEquals(1L, store.state.value.revision)
        assertTrue(store.state.value.unavailable.isEmpty())
    }

    @Test fun successfulPlaybackWinsOverLateDisplayCheck() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String?>()
        val store = TrackAvailabilityStore(this) { entered.complete(Unit); response.await() }
        store.report(song, 0, "旧权限")
        val check = async { store.refresh(song) }
        entered.await()
        store.report(song, 0, null)
        response.complete("迟到的无权限结果")
        check.await()
        assertTrue(store.state.value.unavailable.isEmpty())
    }

}
