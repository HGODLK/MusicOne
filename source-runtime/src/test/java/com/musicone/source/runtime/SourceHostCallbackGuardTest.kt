package com.musicone.source.runtime

import com.musicone.source.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.junit.Assert.*
import org.junit.Test

class SourceHostCallbackGuardTest {
    private val descriptor = SourceDescriptor("org.example.callback", "回调", SourceKind.MAIN, 1, "1", emptySet())
    private val track = SourceTrack(TrackIdentity(descriptor.id, "account", "track"), "歌曲")

    @Test fun exitCarriesItsOwnSourceAndStillRejectsRemovedInstances() {
        val host = QueuedHost()
        val lease = SourceHostLease(host, false, descriptor)
        lease.exitSource()
        assertEquals(descriptor.id, host.lastSourceId)
        lease.invalidate()
        host.drain()
        assertTrue(host.executed.isEmpty())
        assertNull(SourceHostCallbackGuard.capture())
    }

    @Test fun currentInstanceAndAccountAllowQueuedActionsWithoutLeakingThreadScope() {
        val host = QueuedHost()
        val lease = SourceHostLease(host, false, descriptor)
        lease.operations.accountVersion = { 1 }
        lease.play(listOf(track), 0)
        lease.enqueueDetailed(listOf(SourceTrackDetails(track, "stable")), true)
        assertNull(SourceHostCallbackGuard.capture())
        assertTrue(host.executed.isEmpty())
        host.drain()
        assertEquals(listOf("play", "enqueue"), host.executed)
    }

    @Test fun removalRejectsAlreadyPostedPlaybackAndQueueChanges() {
        val host = QueuedHost()
        val lease = SourceHostLease(host, false, descriptor)
        lease.play(listOf(track), 0)
        lease.enqueueDetailed(listOf(SourceTrackDetails(track, "stable")), false)
        lease.invalidate()
        host.drain()
        assertTrue(host.executed.isEmpty())
    }

    @Test fun recommendationIsForwardedAndGuardedLikeOtherPlayback() {
        val host = QueuedHost()
        val lease = SourceHostLease(host, false, descriptor)
        val details = SourceTrackDetails(track, "stable")
        lease.playRecommendation(details)
        host.drain()
        assertEquals(listOf("recommendation"), host.executed)
        host.executed.clear()
        var revision = 1
        lease.operations.accountVersion = { Triple(SourceSessionStatus.AUTHENTICATED, "account", revision) }
        lease.playRecommendation(details)
        revision++
        host.drain()
        assertTrue(host.executed.isEmpty())
        lease.invalidate()
        assertTrue(runCatching { lease.playRecommendation(details) }.isFailure)
        host.drain()
        assertTrue(host.executed.isEmpty())
    }

    @Test fun recommendationRejectsTrackFromOtherSource() {
        val host = QueuedHost()
        val lease = SourceHostLease(host, false, descriptor)
        val other = track.copy(identity = track.identity.copy(sourceId = "org.example.other"))
        assertTrue(runCatching { lease.playRecommendation(SourceTrackDetails(other, "stable")) }.isFailure)
        host.drain()
        assertTrue(host.executed.isEmpty())
    }

    @Test fun changedAccountRevisionRejectsPostedActionsIncludingNavigation() {
        val host = QueuedHost()
        val lease = SourceHostLease(host, false, descriptor)
        var revision = 1
        lease.operations.accountVersion = { Triple(SourceSessionStatus.AUTHENTICATED, "account", revision) }
        lease.playDetailed(listOf(SourceTrackDetails(track, "stable")), 0)
        lease.importTracks(listOf(track), "local")
        requireNotNull(lease.ui).openDestination("source-radio", mapOf("sourceId" to descriptor.id))
        revision++
        host.drain()
        assertTrue(host.executed.isEmpty())
        lease.play(listOf(track), 0)
        host.drain()
        assertEquals(listOf("play"), host.executed)
    }

    @Test fun asyncImportInheritsGuardAndRejectsAccountChangeAfterItsInitialCheck() = runBlocking {
        var revision = 1
        val lease = SourceOperationLease { }
        lease.accountVersion = { revision }
        val guard = lease.dispatch { requireNotNull(SourceHostCallbackGuard.capture()) }
        var committed = false
        val result = runCatching {
            withContext(guard.context) {
                assertTrue(guard.isCurrent())
                revision++
                requireNotNull(coroutineContext[SourceCommitGuard]).commit { committed = true }
            }
        }
        assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertFalse(committed)
    }

    private class QueuedHost : SourceHost, SourceUiHost {
        override val ui: SourceUiHost get() = this
        val executed = mutableListOf<String>()
        var lastSourceId: String? = null
        private val queue = mutableListOf<() -> Unit>()
        private fun post(name: String) {
            val guard = requireNotNull(SourceHostCallbackGuard.capture())
            lastSourceId = guard.sourceId
            queue += { if (guard.isCurrent()) executed += name }
        }
        fun drain() { queue.toList().also { queue.clear() }.forEach { it() } }
        override fun play(tracks: List<SourceTrack>, startIndex: Int) = post("play")
        override fun enqueueDetailed(tracks: List<SourceTrackDetails>, playNext: Boolean) = post("enqueue")
        override fun playRecommendation(track: SourceTrackDetails) = post("recommendation")
        override fun importTracks(tracks: List<SourceTrack>, destinationId: String) = post("import")
        override fun exitSource() = post("exit")
        override fun reportError(message: String) = post("error")
        override fun openDestination(destination: String, arguments: Map<String, String>) = post("destination")
        override suspend fun request(request: SourceUiRequest): SourceUiResult = error("本测试不请求交互结果")
    }
}
