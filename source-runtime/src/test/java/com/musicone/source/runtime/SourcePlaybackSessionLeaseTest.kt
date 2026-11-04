package com.musicone.source.runtime

import com.musicone.source.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class SourcePlaybackSessionLeaseTest {
    private open class Playback : SourceMediaPlayback {
        override suspend fun qualities(track: SourceTrackDetails) = emptyList<SourceAudioQuality>()
        override suspend fun resolve(request: SourcePlaybackRequest): SourcePlaybackResource = error("测试不取源")
        override suspend fun recover(request: SourcePlaybackRequest, previous: SourcePlaybackResource) = resolve(request)
        override suspend fun availability(tracks: List<SourceTrackDetails>) = emptyList<TrackAccessInfo>()
    }
    private val event = SourcePlaybackSessionEvent("1", 1,
        SourceTrackDetails(SourceTrack(TrackIdentity("test", "42", "song"), "歌曲", emptyList()), "song"),
        SourcePlaybackSessionEventType.START, 0, 0, 0)

    @Test fun oldPlaybackAndDefaultProviderRemainDisabled() {
        val lease = SourceOperationLease {}
        try {
            assertNull((guardSourceMediaPlayback(Playback(), lease) as SourcePlaybackSessionProvider).playbackSessions)
            val optional = object : Playback(), SourcePlaybackSessionProvider {}
            assertNull((guardSourceMediaPlayback(optional, lease) as SourcePlaybackSessionProvider).playbackSessions)
        } finally { lease.close() }
    }

    @Test fun capturedObserverRejectsQueuedEventsAfterAccountChange() = runBlocking {
        var account = "42"
        var calls = 0
        val lease = SourceOperationLease {}.apply { accountVersion = { account } }
        val playback = object : Playback(), SourcePlaybackSessionProvider {
            override val playbackSessions = SourcePlaybackSessionObserver { calls++ }
        }
        try {
            val observer = (guardSourceMediaPlayback(playback, lease) as SourcePlaybackSessionProvider).playbackSessions!!
            observer.onPlaybackSession(event)
            account = "77"
            assertTrue(runCatching { observer.onPlaybackSession(event.copy(sequence = 2)) }.exceptionOrNull() is CancellationException)
            assertEquals(1, calls)
        } finally { lease.close() }
    }

    @Test fun qualityProgressPassesThroughCatalogWrapperAndStopsAfterAccountChange() = runBlocking {
        var account = "42"
        val lease = SourceOperationLease {}.apply { accountVersion = { account } }
        val playback = object : Playback(), SourceQualityCatalog {
            override val qualityCatalog = listOf(SourceQualityDefinition("STANDARD", "标准"))
            override val defaultCatalogQualityId = "STANDARD"
            override fun catalogFallbackQualityIds(preferredId: String) = listOf("STANDARD")
            override suspend fun qualities(track: SourceTrackDetails, onProgress: (List<SourceAudioQuality>) -> Unit): List<SourceAudioQuality> {
                onProgress(listOf(SourceAudioQuality("STANDARD", "标准")))
                account = "77"
                onProgress(listOf(SourceAudioQuality("STANDARD", "标准"), SourceAudioQuality("LOSSLESS", "无损")))
                return emptyList()
            }
        }
        val reported = mutableListOf<List<String>>()
        try {
            val result = runCatching { guardSourceMediaPlayback(playback, lease).qualities(event.track) { rows -> reported += rows.map { it.id } } }
            assertTrue(result.exceptionOrNull() is CancellationException)
            assertEquals(listOf(listOf("STANDARD")), reported)
        } finally { lease.close() }
    }

    @Test fun packWithoutProgressOverloadFallsBackToSingleResult() = runBlocking {
        val lease = SourceOperationLease {}
        val playback = object : Playback() {
            override suspend fun qualities(track: SourceTrackDetails) = listOf(SourceAudioQuality("STANDARD", "标准"))
            // 模拟新增重载前编译的旧包。
            override suspend fun qualities(track: SourceTrackDetails, onProgress: (List<SourceAudioQuality>) -> Unit): List<SourceAudioQuality> =
                throw AbstractMethodError()
        }
        val reported = mutableListOf<List<String>>()
        try {
            val result = guardSourceMediaPlayback(playback, lease).qualities(event.track) { rows -> reported += rows.map { it.id } }
            assertEquals(listOf("STANDARD"), result.map { it.id })
            assertEquals(listOf(listOf("STANDARD")), reported)
        } finally { lease.close() }
    }

    @Test fun qualityCatalogDoesNotHideObserverAndRemovalStopsDelivery() = runBlocking {
        var active = true
        var calls = 0
        val lease = SourceOperationLease { check(active) }
        val playback = object : Playback(), SourcePlaybackSessionProvider, SourceQualityCatalog {
            override val qualityCatalog = listOf(SourceQualityDefinition("STANDARD", "标准"))
            override val defaultCatalogQualityId = "STANDARD"
            override fun catalogFallbackQualityIds(preferredId: String) = listOf("STANDARD")
            override val playbackSessions = SourcePlaybackSessionObserver { calls++ }
        }
        try {
            val guarded = guardSourceMediaPlayback(playback, lease)
            assertTrue(guarded is SourceQualityCatalog)
            val observer = (guarded as SourcePlaybackSessionProvider).playbackSessions!!
            active = false
            assertTrue(runCatching { observer.onPlaybackSession(event) }.isFailure)
            assertEquals(0, calls)
        } finally { lease.close() }
    }
}
