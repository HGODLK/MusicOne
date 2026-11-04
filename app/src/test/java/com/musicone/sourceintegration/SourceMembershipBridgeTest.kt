package com.musicone.sourceintegration

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SourceMembershipBridgeTest {
    private open class Entry(kind: SourceKind = SourceKind.MAIN) : SourceEntry {
        override val descriptor = SourceDescriptor("org.example.membership", "会员测试", kind, 1, "1", emptySet())
        override val implementedCapabilities = emptySet<SourceCapability>()
        override fun initialize(environment: SourceEnvironment) {}
        override fun close() {}
        @Composable override fun Content(route: SourceRoute) {}
    }
    private fun track() = SourceTrackDetails(SourceTrack(TrackIdentity("org.example.membership", "42", "song"), "歌曲"),
        "song", access = TrackAccessInfo(TrackAccessBadge.MEMBERSHIP, trialAvailable = true))

    @Test fun undeclaredSourceKeepsExistingTrackAccessWithoutMembershipRequests() = runBlocking {
        val value = track()
        val entry = Entry(SourceKind.SUPPLEMENTAL)
        assertEquals(false, entry.detectMembership())
        assertSame(value.access, refreshedSourceTrackAccess(entry, value))
    }

    @Test fun mainAndSupplementalTracksCheckTheSameDetectorBeforeAvailability() = runBlocking {
        SourceKind.entries.forEach { kind ->
            val calls = mutableListOf<String>()
            val value = track()
            val entry = object : Entry(kind), SourceMembershipProvider {
                override val membershipDetector = object : SourceMembershipDetector {
                    override suspend fun detectMembership(): Boolean? { calls += "会员"; return null }
                }
                override val mediaPlayback = object : SourceMediaPlayback {
                    override suspend fun qualities(track: SourceTrackDetails): List<SourceAudioQuality> = emptyList()
                    override suspend fun resolve(request: SourcePlaybackRequest): SourcePlaybackResource = error("不能试播")
                    override suspend fun recover(request: SourcePlaybackRequest, previous: SourcePlaybackResource): SourcePlaybackResource = error("不能重取票")
                    override suspend fun availability(tracks: List<SourceTrackDetails>): List<TrackAccessInfo> {
                        calls += "单曲权益"
                        assertSame(value, tracks.single())
                        return listOf(value.access)
                    }
                }
            }
            val access = refreshedSourceTrackAccess(entry, value)
            assertSame(value.access, access)
            assertEquals(listOf("会员", "单曲权益"), calls)
            assertEquals(TrackAccessBadge.MEMBERSHIP, access.badge)
        }
    }

    @Test fun failedDetectionKeepsUnknownAndStillReadsSongRights() = runBlocking {
        val value = track()
        val entry = object : Entry(), SourceMembershipProvider {
            override val membershipDetector = object : SourceMembershipDetector {
                override suspend fun detectMembership(): Boolean? = throw java.io.IOException("暂时断网")
            }
        }
        assertNull(entry.detectMembership())
        assertSame(value.access, refreshedSourceTrackAccess(entry, value))
    }

    @Test fun cancellationStopsFollowingRightsRequests() = runBlocking {
        val entry = object : Entry(), SourceMembershipProvider {
            override val membershipDetector = object : SourceMembershipDetector {
                override suspend fun detectMembership(): Boolean? = throw CancellationException()
            }
        }
        assertTrue(runCatching { refreshedSourceTrackAccess(entry, track()) }.exceptionOrNull() is CancellationException)
    }

    @Test fun supplementaryPlaylistCannotUseAnotherSourcesMembership() = runBlocking {
        val other = track().copy(track = track().track.copy(identity = TrackIdentity("org.example.other", "42", "song")))
        assertTrue(runCatching { refreshedSourceTrackAccess(Entry(SourceKind.SUPPLEMENTAL), other) }
            .exceptionOrNull() is IllegalArgumentException)
    }
}
