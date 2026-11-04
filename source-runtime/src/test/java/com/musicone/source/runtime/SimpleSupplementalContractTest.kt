package com.musicone.source.runtime

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SimpleSupplementalContractTest {
    private val id = "org.example.simple.test"
    private fun track(remote: String, source: String = id) = SourceTrackDetails(
        SourceTrack(TrackIdentity(source, null, remote), remote), stableId = remote)
    private fun source(tracks: List<SourceTrackDetails>) = object : SimpleSupplementalSourceEntry(
        SimpleSupplementalSourceInfo(id, "简易测试", 1, "1")) {
        override suspend fun parseInput(input: String) = tracks
        override suspend fun resolvePlayback(request: SourcePlaybackRequest) = SourcePlaybackResource(
            PlaybackAddress("https://example.org/audio", cacheIdentity = request.track.stableId), "default", "")
    }

    private fun changingSource(parse: (String) -> List<SourceTrackDetails>) = object : SimpleSupplementalSourceEntry(
        SimpleSupplementalSourceInfo(id, "动态解析测试", 1, "1")) {
        override suspend fun parseInput(input: String) = parse(input)
        override suspend fun resolvePlayback(request: SourcePlaybackRequest) = SourcePlaybackResource(
            PlaybackAddress("https://example.org/audio", cacheIdentity = request.track.stableId), "default", "")
    }

    private suspend fun evictInput(entry: SimpleSupplementalSourceEntry) {
        repeat(32) { entry.contents!!.resolve("other-$it") }
    }

    @Test fun evictedSingleContentUsesUpdatedTrackAndContentDetails() = runBlocking {
        var current = listOf(track("one"))
        val entry = changingSource { current }
        val old = entry.contents!!.resolve("target")
        evictInput(entry)
        val updated = track("one").copy(track = track("one").track.copy(title = "更新标题"),
            durationMs = 3000, artwork = SourceArtwork(url = "https://example.org/new-art"),
            payload = SourcePayload("updated", 1, "new-payload"))
        current = listOf(updated)
        val detail = entry.contents!!.detail(old)
        assertEquals(updated.track.title, detail.title)
        assertEquals(updated.artwork, detail.artwork)
        assertEquals(updated.durationMs, detail.durationMs)
        assertEquals(updated, detail.playableTrack)
        assertEquals(listOf(updated), entry.importing!!.tracks(old, emptyList()))
        assertTrue(entry.childItems!!.children(old, SourceCursorRequest()).items.isEmpty())
    }

    @Test fun evictedSingleContentCanBecomeMultipleChildrenWithoutOldTrackShortcut() = runBlocking {
        var current = listOf(track("one"))
        val entry = changingSource { current }
        val old = entry.contents!!.resolve("target")
        evictInput(entry)
        current = listOf(track("one"), track("two"))
        val children = entry.childItems!!.children(old, SourceCursorRequest()).items
        assertEquals(listOf("one", "two"), children.map { it.identity.remoteId })
        assertNull(entry.contents!!.detail(old).playableTrack)
        assertEquals(listOf(track("two")), entry.importing!!.tracks(old, listOf(children[1])))
        assertTrue(entry.importing!!.tracks(old, emptyList()).isEmpty())
    }

    @Test fun evictedMultipleContentCanBecomeSingleAndRejectRemovedSelection() = runBlocking<Unit> {
        var current = listOf(track("one"), track("two"))
        val entry = changingSource { current }
        val old = entry.contents!!.resolve("target")
        val removed = entry.childItems!!.children(old, SourceCursorRequest()).items[1]
        evictInput(entry)
        current = listOf(track("one"))
        assertEquals(track("one"), entry.contents!!.detail(old).playableTrack)
        assertTrue(entry.childItems!!.children(old, SourceCursorRequest()).items.isEmpty())
        assertEquals(listOf(track("one")), entry.importing!!.tracks(old, emptyList()))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { entry.importing!!.tracks(old, listOf(removed)) }
        }
    }

    @Test fun evictedContentCannotRefreshIntoAnotherAccount() = runBlocking<Unit> {
        var current = listOf(track("one"))
        val entry = changingSource { current }
        val old = entry.contents!!.resolve("target")
        evictInput(entry)
        current = listOf(track("one").let { it.copy(track = it.track.copy(
            identity = it.track.identity.copy(accountId = "another-account"))) })
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { entry.contents!!.detail(old) }
        }
    }

    @Test fun cacheHitReusesSnapshotAcrossDetailChildrenAndConversion() = runBlocking {
        var parses = 0
        val song = track("one")
        val entry = changingSource { parses++; listOf(song) }
        val content = entry.contents!!.resolve("target")
        assertEquals(song, entry.contents!!.detail(content).playableTrack)
        assertTrue(entry.childItems!!.children(content, SourceCursorRequest()).items.isEmpty())
        assertEquals(listOf(song), entry.importing!!.tracks(content, emptyList()))
        assertEquals(1, parses)
    }

    @Test fun newOptionalServicesKeepApiOneAndSatisfyHomeWithoutCustomRoot() {
        assertEquals(SourceSdk.API_VERSION, SourceSupplementalContract.API_VERSION)
        val entry = source(listOf(track("one")))
        SourceCapabilityValidator.check(entry)
        SourceOptionalContractValidator.check(entry)
        assertFalse(entry.descriptor.useCustomPages)
        assertEquals(SourceSupplementalPageMode.DEFAULT, entry.supplementalPagePolicy(SourceSupplementalPage.HOME).mode)
    }

    @Test fun singleTrackDoesNotRequireFakeChildOrAdditionalConversion() = runBlocking {
        val song = track("one")
        val entry = source(listOf(song))
        val content = entry.contents!!.resolve("one")
        assertEquals(song, content.playableTrack)
        assertTrue(entry.childItems!!.children(content, SourceCursorRequest()).items.isEmpty())
        assertEquals(listOf(song), entry.importing!!.tracks(content, emptyList()))
    }

    @Test fun multipleTracksPreserveSelectionInsteadOfImportingUnselectedMembers() = runBlocking {
        val entry = source(listOf(track("one"), track("two")))
        val content = entry.contents!!.resolve("collection")
        assertNull(content.playableTrack)
        val children = entry.childItems!!.children(content, SourceCursorRequest()).items
        assertEquals(listOf(true, true), children.map { it.initiallySelected })
        assertEquals(listOf("two"), entry.importing!!.tracks(content, listOf(children[1])).map { it.stableId })
        assertTrue(entry.importing!!.tracks(content, emptyList()).isEmpty())
    }

    @Test fun wrongSourceAndDuplicateIdentityAreRejectedBeforeImport() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { source(listOf(track("one", "org.other"))).contents!!.resolve("one") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { source(listOf(track("one"), track("one"))).contents!!.resolve("one") } }
    }

    @Test fun guardedLegacyEntryDoesNotAcquireNewPageOrQualityCapabilities() {
        val original = object : SourceEntry {
            override val descriptor = SourceDescriptor(id, "旧包", SourceKind.SUPPLEMENTAL, 1, "1", emptySet())
            override val implementedCapabilities = emptySet<SourceCapability>()
            override fun initialize(environment: SourceEnvironment) {}
            override fun close() {}
            @Composable override fun Content(route: SourceRoute) {}
        }
        val guarded = SourceGuardedEntry(original, SourceOperationLease {})
        assertNull(guarded.supplementalPageProviderOrNull())
        assertNull(guarded.mediaPlayback)
        SourceOptionalContractValidator.check(original)
    }

    @Test fun newDirectoryAndPageGettersCannotRunAfterSourceRemoval() {
        var active = true
        val guarded = SourceGuardedEntry(source(listOf(track("one"))), SourceOperationLease { check(active) })
        val catalog = guarded.mediaPlayback as SourceQualityCatalog
        val pages = checkNotNull(guarded.supplementalPageProviderOrNull())
        assertEquals("default", catalog.defaultCatalogQualityId)
        active = false
        assertThrows(IllegalStateException::class.java) { catalog.qualityCatalog }
        assertThrows(IllegalStateException::class.java) { pages.supplementalPagePolicy(SourceSupplementalPage.CONTENT) }
    }
}
