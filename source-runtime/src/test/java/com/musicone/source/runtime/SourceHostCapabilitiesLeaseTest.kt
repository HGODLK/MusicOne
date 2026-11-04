package com.musicone.source.runtime

import com.musicone.source.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.*
import org.junit.Test

class SourceHostCapabilitiesLeaseTest {
    @Test fun eachLyricPermissionCombinationIndependentlyGuardsActualHostWrites() = runBlocking {
        for (replace in listOf(false, true)) for (offset in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val delegate = object : SourceLibraryHost {
                override suspend fun destinations() = emptyList<SourceLocalCollection>()
                override suspend fun importTracks(tracks: List<SourceTrackDetails>, destinationId: String) = error("本测试不导入资料")
                override suspend fun editMetadata(track: SourceTrackDetails, edit: SourceTrackMetadataEdit) = error("本测试不改资料")
                override suspend fun setManualLyrics(track: SourceTrackDetails, documentUri: String?) {
                    calls += "lyrics:$documentUri"
                }
                override suspend fun setLineOffset(track: SourceTrackDetails, offsetMs: Long) {
                    calls += "offset:$offsetMs"
                }
                override suspend fun subscriptionRevision(identity: SourceSubscriptionIdentity) = 0L
                override suspend fun commitSubscription(identity: SourceSubscriptionIdentity, expectedRevision: Long,
                    items: List<SourceSubscriptionItem>) = error("本测试不写订阅")
                override suspend fun removeSubscription(identity: SourceSubscriptionIdentity, keepTracks: Boolean) = error("本测试不移除订阅")
            }
            val host = object : SourceHost {
                override val library = delegate
                override fun play(tracks: List<SourceTrack>, startIndex: Int) = error("本测试不播放")
                override fun importTracks(tracks: List<SourceTrack>, destinationId: String) = error("本测试不导入")
                override fun exitSource() {}
                override fun reportError(message: String) {}
            }
            val descriptor = SourceDescriptor("org.example.permissions", "权限组合", SourceKind.MAIN, 1, "1",
                capabilities = emptySet(), permissions = SourcePermissions(replaceManualLyrics = replace, adjustLineOffset = offset))
            val lease = SourceOperationLease { }
            try {
                val guarded = requireNotNull(SourceHostCapabilitiesLease(host, descriptor, lease).library)
                val track = SourceTrackDetails(SourceTrack(TrackIdentity(descriptor.id, "account", "same-remote"), "歌曲"), "stable")
                val lyricsResult = supervisorScope { runCatching { guarded.setManualLyrics(track, "content://test/lyrics") } }
                val offsetResult = supervisorScope { runCatching { guarded.setLineOffset(track, 250) } }
                assertEquals("replace=$replace offset=$offset", replace, lyricsResult.isSuccess)
                assertEquals("replace=$replace offset=$offset", offset, offsetResult.isSuccess)
                if (!replace) assertTrue(lyricsResult.exceptionOrNull() is IllegalStateException)
                if (!offset) assertTrue(offsetResult.exceptionOrNull() is IllegalStateException)
                val expected = buildList {
                    if (replace) add("lyrics:content://test/lyrics")
                    if (offset) add("offset:250")
                }
                assertEquals("禁止的权限不能派发到真实宿主接口", expected, calls)
            } finally { lease.close() }
        }
    }
}
