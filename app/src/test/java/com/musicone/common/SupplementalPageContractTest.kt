package com.musicone

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SupplementalPageContractTest {
    private open class Entry : SourceEntry {
        override val descriptor = SourceDescriptor("test.supplemental", "测试", SourceKind.SUPPLEMENTAL, 1, "1", emptySet())
        override val implementedCapabilities = emptySet<SourceCapability>()
        override fun initialize(environment: SourceEnvironment) = Unit
        override fun close() = Unit
        @Composable override fun Content(route: SourceRoute) = Unit
    }
    private open class Modern(private val policy: SourceSupplementalPagePolicy = SourceSupplementalPagePolicy(), private val publicIdentity: String? = null) : Entry(), SourceSupplementalPageProvider {
        override fun supplementalPagePolicy(page: SourceSupplementalPage) = policy
        override fun publicSubscriptionIdentity() = publicIdentity
        @Composable override fun SupplementalPage(context: SourceSupplementalPageContext) = Unit
    }
    @Test fun legacyEntryNeverBecomesModernThroughEmptyGuardAccess() {
        val guarded = object : Entry(), SourceSupplementalPageProviderAccess {
            override val supplementalPageProvider: SourceSupplementalPageProvider? = null
        }
        assertNull(guarded.supplementalPageProviderOrNull())
        assertTrue(supplementalPageAvailable(guarded, SourceSupplementalPage.LINK))
    }
    @Test fun defaultPageRequiresBusinessCapabilityButCustomPageDoesNot() {
        assertFalse(supplementalPageAvailable(Modern(), SourceSupplementalPage.LINK))
        assertTrue(supplementalPageAvailable(Modern(SourceSupplementalPagePolicy(SourceSupplementalPageMode.CUSTOM)), SourceSupplementalPage.LINK))
        assertFalse(supplementalPageAvailable(Modern(SourceSupplementalPagePolicy(SourceSupplementalPageMode.DISABLED)), SourceSupplementalPage.HOME))
        assertFalse(SourceSupplementalPagePolicy().allowPublicFallback)
        assertNull(Modern().publicSubscriptionIdentity())
    }
    @Test fun collectionDetailsCanBeDisabledWithoutDisablingTheDirectory() {
        val entry = object : Modern() {
            override val remoteCollections = object : SourceRemoteCollections {
                override suspend fun collections(request: SourceCursorRequest) = SourcePage<SourceRemoteCollection>(emptyList(), null)
                override suspend fun contents(collection: SourceRemoteCollection, query: String?, request: SourceCursorRequest) = SourcePage<SourceContent>(emptyList(), null)
            }
            override fun supplementalPagePolicy(page: SourceSupplementalPage) = SourceSupplementalPagePolicy(
                if (page == SourceSupplementalPage.COLLECTION) SourceSupplementalPageMode.DISABLED else SourceSupplementalPageMode.DEFAULT)
        }
        assertTrue(supplementalPageAvailable(entry, SourceSupplementalPage.COLLECTIONS))
        assertFalse(supplementalPageAvailable(entry, SourceSupplementalPage.COLLECTION))
        assertTrue(supplementalPageAvailable(Entry(), SourceSupplementalPage.COLLECTION))
    }
    @Test fun publicSubscriptionIdentityIsExplicitStableAndSeparateFromRealAccounts() {
        val entry = Modern(publicIdentity = "shared")
        val anonymous = supplementalSubscriptionAccountId(entry, null)
        assertNotNull(anonymous)
        assertEquals(anonymous, supplementalSubscriptionAccountId(entry, null))
        assertNotEquals("shared", anonymous)
        assertEquals("shared", supplementalSubscriptionAccountId(entry, SourceAccount("shared", "账号")))
        assertNull(supplementalSubscriptionAccountId(Modern(), null))
        try {
            supplementalSubscriptionAccountId(entry, SourceAccount(checkNotNull(anonymous), "冲突账号"))
            fail("真实账号不能占用公开订阅命名空间")
        } catch (_: IllegalArgumentException) { }
    }
    @Test fun actionCapabilitiesRequireDeclarationsAndRealHostPlaybackService() {
        val media = object : SourceMediaPlayback {
            override suspend fun qualities(track: SourceTrackDetails) = emptyList<SourceAudioQuality>()
            override suspend fun resolve(request: SourcePlaybackRequest): SourcePlaybackResource = error("不应请求播放")
            override suspend fun recover(request: SourcePlaybackRequest, previous: SourcePlaybackResource): SourcePlaybackResource = error("不应请求播放")
            override suspend fun availability(tracks: List<SourceTrackDetails>) = tracks.map { it.access }
        }
        val undeclared = object : Modern() { override val mediaPlayback = media }
        assertFalse(supplementalCanPlay(undeclared))
        val playback = object : Modern() {
            override val descriptor = super.descriptor.copy(capabilities = setOf(SourceCapability.PLAYBACK))
            override val mediaPlayback = media
        }
        assertTrue(supplementalCanPlay(playback))
        val oldPlaybackOnly = object : Modern() {
            override val descriptor = super.descriptor.copy(capabilities = setOf(SourceCapability.PLAYBACK))
            override val playback = object : SourcePlayback {
                override suspend fun resolve(track: SourceTrack, quality: String?): PlaybackAddress = error("不应请求播放")
            }
        }
        assertFalse(supplementalCanPlay(oldPlaybackOnly))
        val details = SourceTrackDetails(SourceTrack(TrackIdentity("test.supplemental", null, "song"), "歌曲"), "song")
        val content = SourceContent(details.track.identity, "歌曲", playableTrack = details)
        assertFalse(supplementalCanImport(Modern(), content))
        val importing = object : Modern() {
            override val descriptor = super.descriptor.copy(capabilities = setOf(SourceCapability.IMPORT))
        }
        assertTrue(supplementalCanImport(importing, content))
        assertFalse(supplementalCanImport(importing, content, hasChildren = true))
        requireSupplementalAction(importing, play = false, contents = listOf(content))
        try {
            requireSupplementalAction(undeclared, play = true)
            fail("自定义动作不能绕过播放声明")
        } catch (_: IllegalStateException) { }
        assertTrue(supplementalCanPlay(Entry()))
        assertTrue(supplementalCanImport(Entry()))
    }
    @Test fun defaultConversionLoadsDetailAndOnlyDeclaredDefaultChildren() = runBlocking {
        val identity = TrackIdentity("test.supplemental", null, "item")
        val content = SourceContent(identity, "初始")
        val child = SourceChildItem(TrackIdentity("test.supplemental", null, "child"), 0, "默认子项目", initiallySelected = true)
        val details = SourceTrackDetails(SourceTrack(identity, "歌曲"), "song")
        var detailCalls = 0
        var selected: List<SourceChildItem>? = null
        val entry = object : Modern() {
            override val contents = object : SourceContents {
                override suspend fun resolve(input: String) = content
                override suspend fun detail(content: SourceContent): SourceContent {
                    detailCalls++; return content.copy(title = "完整")
                }
            }
            override val childItems = object : SourceChildItems {
                override suspend fun children(content: SourceContent, request: SourceCursorRequest): SourcePage<SourceChildItem> {
                    assertEquals("完整", content.title)
                    return SourcePage(listOf(child, child.copy(identity = identity.copy(remoteId = "other"), initiallySelected = false)), null)
                }
            }
            override val importing = object : SourceImporting {
                override suspend fun tracks(content: SourceContent, selectedChildren: List<SourceChildItem>): List<SourceTrackDetails> {
                    selected = selectedChildren; return listOf(details)
                }
            }
        }
        assertEquals(listOf(details), supplementalDefaultTracks(entry, content))
        assertEquals(1, detailCalls)
        assertEquals(listOf(child), selected)
    }
    @Test fun completeSongSkipsImportServiceOnlyForModernEntry() = runBlocking {
        val details = SourceTrackDetails(SourceTrack(TrackIdentity("test.supplemental", null, "song"), "歌曲"), "song")
        val content = SourceContent(details.track.identity, "歌曲", playableTrack = details)
        assertEquals(listOf(details), supplementalTracks(Modern(), content, emptyList()))
        try {
            supplementalTracks(Entry(), content, emptyList())
            fail("旧包必须继续要求原导入服务")
        } catch (_: IllegalStateException) { }
    }
    @Test fun selectedChildrenAlwaysUseImportService() = runBlocking {
        val details = SourceTrackDetails(SourceTrack(TrackIdentity("test.supplemental", null, "song"), "歌曲"), "song")
        val content = SourceContent(details.track.identity, "歌曲", playableTrack = details)
        try {
            supplementalTracks(Modern(), content, listOf(SourceChildItem(TrackIdentity("test.supplemental", null, "child"), 0, "子项目")))
            fail("有选择子项目时不能误用父歌曲")
        } catch (_: IllegalStateException) { }
    }
}
