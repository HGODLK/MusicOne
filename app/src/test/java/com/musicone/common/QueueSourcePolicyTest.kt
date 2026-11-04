package com.musicone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QueueSourcePolicyTest {
    private val remote = MusicSource.fromId("test.queue.source")
    private fun track(id: String, source: MusicSource = remote) =
        MusicTrack(id, source, id, "歌手", "", 1000, 0, 0, "", "")

    @Test fun initializingDoesNotPretendThatASourceWasRemoved() {
        assertEquals(QueueSourceStatus.INITIALIZING,
            queueSourceStatus(remote, active = false, installed = false, initializing = true))
        assertEquals(QueueSourceStatus.INITIALIZING,
            queueSourceStatus(remote, active = false, installed = true, initializing = true))
        assertEquals(QueueSourceStatus.AVAILABLE,
            queueSourceStatus(MusicSource.LOCAL, active = false, installed = false, initializing = true))
        assertEquals(QueueSourceStatus.MISSING,
            queueSourceStatus(remote, active = false, installed = false, initializing = false))
    }

    @Test fun missingIsDerivedAndSameIdentityRestoresPausedWithoutLosingMetadata() {
        val original = track("stable").copy(providerData = mapOf("private" to "preserved"))
        assertEquals(QueueSourceStatus.MISSING, queueSourceStatus(remote, active = false, installed = false))
        assertEquals(QueueSourceStatus.UNAVAILABLE, queueSourceStatus(remote, active = false, installed = true))
        assertEquals(QueueSourceStatus.AVAILABLE, queueSourceStatus(remote, active = true, installed = true))
        assertEquals(QueueSourceStatus.AVAILABLE, queueSourceStatus(MusicSource.LOCAL, active = false, installed = false))
        val restored = restorePlaybackSnapshot(listOf(original), original.id, emptyList())!!
        assertFalse(restored.isPlaying)
        assertEquals(original, restored.currentTrack)
        assertTrue(original.playable)
    }

    @Test fun explicitRemovalWinsOverActiveLeaseAndInitializationUntilUndo() {
        assertEquals(QueueSourceStatus.PENDING_REMOVAL,
            queueSourceStatus(remote, active = true, installed = true, pendingRemoval = true))
        assertEquals(QueueSourceStatus.PENDING_REMOVAL,
            queueSourceStatus(remote, active = false, installed = true, initializing = true, pendingRemoval = true))
        assertEquals(QueueSourceStatus.MISSING,
            queueSourceStatus(remote, active = true, installed = false, initializing = true, removed = true))
        assertEquals(QueueSourceStatus.AVAILABLE,
            queueSourceStatus(remote, active = true, installed = true, pendingRemoval = false))
        assertEquals(QueueSourceStatus.AVAILABLE,
            queueSourceStatus(MusicSource.LOCAL, active = false, installed = false, pendingRemoval = true, removed = true))
    }

    @Test fun automaticAdvanceSkipsMissingInVisibleOrderWithoutDroppingQueueRows() {
        val first = track("first", MusicSource.LOCAL)
        val queue = listOf(first, track("missing"), track("available", MusicSource.LOCAL), track("last-missing"))
        val state = MusicOneUiState(currentTrack = first, queue = queue, isPlaying = true)
        val next = nextQueuePlaybackIndex(state, automatic = true) { it.source == MusicSource.LOCAL }
        assertEquals(2, next)
        assertEquals(queue, state.queue)
        assertEquals(0, nextQueuePlaybackIndex(state.copy(repeatMode = RepeatMode.ONE), true) { it.source == MusicSource.LOCAL })
    }

    @Test fun allMissingIsBoundedAndStopsWhileKeepingQueueAndCurrentTrack() {
        val queue = listOf(track("one"), track("two"), track("three"))
        for (repeat in listOf(RepeatMode.ALL, RepeatMode.ONE)) {
            val state = MusicOneUiState(currentTrack = queue.first(), queue = queue, isPlaying = true,
                qualityLoading = true, repeatMode = repeat)
            var inspected = 0
            assertNull(nextQueuePlaybackIndex(state, automatic = true) { inspected++; false })
            assertEquals(queue.size, inspected)
            val stopped = state.withUnavailableQueueStopped()
            assertFalse(stopped.isPlaying)
            assertFalse(stopped.qualityLoading)
            assertEquals(queue, stopped.queue)
            assertEquals(state.currentTrack, stopped.currentTrack)
        }
    }

    @Test fun missingOrFailedSourceCannotBypassGateWithAPreloadedOrCompleteCachedResource() = runBlocking {
        var cachedCalls = 0
        var networkCalls = 0
        for (status in listOf(QueueSourceStatus.INITIALIZING, QueueSourceStatus.MISSING,
            QueueSourceStatus.PENDING_REMOVAL, QueueSourceStatus.UNAVAILABLE)) {
            try {
                resolveQueuePlayback(track("cached"), status,
                    cached = { cachedCalls++; "complete-cached-resource" },
                    resolve = { networkCalls++; "online-resource" })
                fail("缺包队列项不能绕过来源检查播放缓存")
            } catch (_: TrackUnavailableException) { }
        }
        assertEquals(0, cachedCalls)
        assertEquals(0, networkCalls)
        assertEquals("cached", resolveQueuePlayback(track("ready"), QueueSourceStatus.AVAILABLE,
            cached = { "cached" }, resolve = { networkCalls++; "online" }))
        assertEquals(0, networkCalls)
    }
}
