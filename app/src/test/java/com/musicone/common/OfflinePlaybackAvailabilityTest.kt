package com.musicone

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

    @Test fun burstDuringScanKeepsOneFollowUpWithoutCancellingCurrentScan() = runBlocking {
        val changes = Channel<Unit>(Channel.UNLIMITED)
        val output = Channel<OfflinePlaybackAvailabilityState>(Channel.UNLIMITED)
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val burstReceived = CompletableDeferred<Unit>()
        var changesSeen = 0
        var scans = 0
        var cancelledScans = 0
        val events = changes.receiveAsFlow().onEach {
            if (++changesSeen == 11) burstReceived.complete(Unit)
        }
        val collector = launch {
            offlineAvailabilityStates(flowOf(false), events) {
                val scan = ++scans
                if (scan == 1) {
                    started.complete(Unit)
                    try { finish.await() }
                    catch (cancelled: CancellationException) { cancelledScans++; throw cancelled }
                }
                setOf("scan-$scan")
            }.collect { output.send(it) }
        }
        try {
            changes.send(Unit)
            withTimeout(2_000) { started.await() }
            repeat(10) { changes.send(Unit) }
            withTimeout(2_000) { burstReceived.await() }
            assertEquals(0, cancelledScans)
            finish.complete(Unit)
            withTimeout(2_000) {
                while ("scan-2" !in output.receive().trackIds) { }
            }
            assertEquals(2, scans)
            assertEquals(0, cancelledScans)
        } finally { collector.cancelAndJoin() }
    }

    @Test fun changedAccountCancelsOldScanAndPublishesOnlyTheNewNamespace() = runBlocking {
        val namespaces = MutableStateFlow("account-a")
        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val output = Channel<OfflinePlaybackAvailabilityState>(Channel.UNLIMITED)
        val collector = launch {
            offlineAvailabilityStates(flowOf(false), flowOf(Unit), namespaces) {
                val account = namespaces.value
                if (account == "account-a") {
                    firstStarted.complete(Unit)
                    try { awaitCancellation() }
                    finally { firstCancelled.complete(Unit) }
                }
                setOf(account)
            }.collect { output.send(it) }
        }
        try {
            withTimeout(2_000) { firstStarted.await() }
            namespaces.value = "account-b"
            withTimeout(2_000) {
                firstCancelled.await()
                var state = output.receive()
                while ("account-b" !in state.trackIds) {
                    assertFalse("account-a" in state.trackIds)
                    state = output.receive()
                }
                assertFalse("account-a" in state.trackIds)
            }
        } finally { collector.cancelAndJoin() }
    }
}
