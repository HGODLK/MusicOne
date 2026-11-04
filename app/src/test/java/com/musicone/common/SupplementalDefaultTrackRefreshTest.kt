package com.musicone

import androidx.compose.runtime.Composable
import com.musicone.source.sdk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SupplementalDefaultTrackRefreshTest {
    private val sourceId = "test.supplemental"
    private fun track(id: String, title: String = id) = SourceTrackDetails(
        SourceTrack(TrackIdentity(sourceId, null, id), title), id)

    private inner class Entry(private val fresh: SourceContent, private val children: List<SourceChildItem> = emptyList(),
        private val converted: List<SourceTrackDetails> = emptyList()) : SourceEntry, SourceSupplementalPageProvider {
        var detailCalls = 0
        var importedSelection: List<SourceChildItem>? = null
        override val descriptor = SourceDescriptor(sourceId, "测试", SourceKind.SUPPLEMENTAL, 1, "1", emptySet())
        override val implementedCapabilities = emptySet<SourceCapability>()
        override fun initialize(environment: SourceEnvironment) = Unit
        override fun close() = Unit
        @Composable override fun Content(route: SourceRoute) = Unit
        @Composable override fun SupplementalPage(context: SourceSupplementalPageContext) = Unit
        override val contents = object : SourceContents {
            override suspend fun resolve(input: String) = fresh
            override suspend fun detail(content: SourceContent): SourceContent { detailCalls++; return fresh }
        }
        override val childItems = object : SourceChildItems {
            override suspend fun children(content: SourceContent, request: SourceCursorRequest) = SourcePage(children, null)
        }
        override val importing = object : SourceImporting {
            override suspend fun tracks(content: SourceContent, selectedChildren: List<SourceChildItem>): List<SourceTrackDetails> {
                importedSelection = selectedChildren
                return converted
            }
        }
    }

    @Test fun completeOldTrackStillConsumesUpdatedAuthoritativeDetails() = runBlocking {
        val old = track("song", "旧资料")
        val fresh = track("song", "重解析的新资料")
        val original = SourceContent(TrackIdentity(sourceId, null, "input"), "旧资料", playableTrack = old)
        val entry = Entry(original.copy(title = "重解析的新资料", playableTrack = fresh))
        assertEquals(listOf(fresh), supplementalDefaultTracks(entry, original))
        assertEquals(1, entry.detailCalls)
        assertNull(entry.importedSelection)
    }

    @Test fun selectedImportOfAnOldSingleTrackRefreshesDetailsExactlyOnce() = runBlocking {
        val original = SourceContent(TrackIdentity(sourceId, null, "input"), "旧单曲", playableTrack = track("song", "旧资料"))
        val fresh = track("song", "最新资料")
        val entry = Entry(original.copy(playableTrack = fresh))
        assertEquals(listOf(fresh), supplementalTracks(entry, original, emptyList()))
        assertEquals(1, entry.detailCalls)
    }

    @Test fun explicitChildSelectionUsesImportingWithoutAnotherDetailRequest() = runBlocking {
        val original = SourceContent(TrackIdentity(sourceId, null, "input"), "内容", playableTrack = track("old"))
        val selected = SourceChildItem(TrackIdentity(sourceId, null, "current"), 1, "所选项目")
        val fresh = track("current", "最新资料")
        val entry = Entry(original, listOf(selected), listOf(fresh))
        assertEquals(listOf(fresh), supplementalTracks(entry, original, listOf(selected)))
        assertEquals(0, entry.detailCalls)
        assertEquals(listOf(selected), entry.importedSelection)
    }

    @Test fun oldSingleTrackCanBecomeMultipleDefaultSelectedChildren() = runBlocking {
        val original = SourceContent(TrackIdentity(sourceId, null, "input"), "旧单曲", playableTrack = track("old"))
        val selected = SourceChildItem(TrackIdentity(sourceId, null, "first"), 1, "第一项", initiallySelected = true)
        val ignored = SourceChildItem(TrackIdentity(sourceId, null, "second"), 2, "第二项", initiallySelected = false)
        val fresh = track("first")
        val entry = Entry(original.copy(playableTrack = null), listOf(selected, ignored), listOf(fresh))
        assertEquals(listOf(fresh), supplementalDefaultTracks(entry, original))
        assertEquals(listOf(selected), entry.importedSelection)
    }

    @Test fun oldMultipleContentsCanBecomeOneCompleteTrackWithoutImporting() = runBlocking {
        val original = SourceContent(TrackIdentity(sourceId, null, "input"), "旧合集")
        val fresh = track("current")
        val entry = Entry(original.copy(playableTrack = fresh))
        assertEquals(listOf(fresh), supplementalDefaultTracks(entry, original))
        assertNull(entry.importedSelection)
    }
}
