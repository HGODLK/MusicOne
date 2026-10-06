package com.musicone.demo

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class OfflinePlaybackAvailabilityTest {
    @Test fun disconnectUsesAlreadyKnownCacheAndReconnectDoesNotWaitForRescan() = runBlocking {
        val network = MutableStateFlow(true)
        val changes = Channel<Unit>(Channel.UNLIMITED)
        val output = Channel<OfflinePlaybackAvailabilityState>(Channel.UNLIMITED)
        val secondScanStarted = CompletableDeferred<Unit>()
        val finishSecondScan = CompletableDeferred<Unit>()
        var scans = 0
        val collector = launch {
            offlineAvailabilityStates(network, changes.receiveAsFlow()) {
                scans++
                if (scans == 2) { secondScanStarted.complete(Unit); finishSecondScan.await() }
                setOf("qq-cached")
            }.collect { output.send(it) }
        }
        suspend fun nextMatching(test: (OfflinePlaybackAvailabilityState) -> Boolean) = withTimeout(2_000) {
            var result = output.receive()
            while (!test(result)) result = output.receive()
            result
        }
        try {
            changes.send(Unit)
            nextMatching { "qq-cached" in it.trackIds }
            network.value = false
            assertTrue(nextMatching { !it.online }.showsBadge("qq-cached"))
            assertEquals(1, scans)
            changes.send(Unit)
            secondScanStarted.await()
            network.value = true
            assertFalse(nextMatching { it.online }.showsBadge("qq-cached"))
        } finally { collector.cancelAndJoin() }
    }

    @Test fun completedCacheAppearsAndRemovalDisappearsWithoutAnotherNetworkEvent() = runBlocking {
        val changes = Channel<Unit>(Channel.UNLIMITED)
        val output = Channel<OfflinePlaybackAvailabilityState>(Channel.UNLIMITED)
        var tracks = emptySet<String>()
        val collector = launch {
            offlineAvailabilityStates(flowOf(false), changes.receiveAsFlow()) { tracks }
                .collect { output.send(it) }
        }
        suspend fun nextMatching(test: (OfflinePlaybackAvailabilityState) -> Boolean) = withTimeout(2_000) {
            var result = output.receive()
            while (!test(result)) result = output.receive()
            result
        }
        try {
            nextMatching { it.trackIds.isEmpty() }
            tracks = setOf("qq-finished")
            changes.send(Unit)
            assertTrue(nextMatching { it.trackIds.isNotEmpty() }.showsBadge("qq-finished"))
            tracks = emptySet()
            changes.send(Unit)
            assertFalse(nextMatching { it.trackIds.isEmpty() }.showsBadge("qq-finished"))
        } finally { collector.cancelAndJoin() }
    }
}
