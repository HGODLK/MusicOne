package com.musicone.source.runtime

import com.musicone.source.sdk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class SourceLibraryFeaturesLeaseTest {
    private val collection = SourceCollection(TrackIdentity("test", "42", "7"), "7", "歌单")

    @Test fun oldServicesExposeDisabledCapabilitiesAndRetainBasicOperations() = runBlocking {
        val lease = SourceOperationLease {}
        try {
            val guarded = guardSourceCollectionEditing(object : SourceCollectionEditing {
                override suspend fun writableCollections() = listOf(collection)
                override suspend fun create(edit: SourceCollectionEdit) = collection.copy(title = edit.title)
            }, lease)
            assertEquals("新歌单", guarded.create(SourceCollectionEdit("新歌单")).title)
            assertTrue((guarded as SourceCollectionDetailsEditing).editableFields.isEmpty())
            assertFalse((guarded as SourceCollectionRemoteOrder).supportsRemoteOrder(collection))
            val membership = guarded as SourceCollectionMembership
            assertFalse(membership.supportsMembership)
            assertNull(membership.cachedWritableCollections())
            assertNull(membership.containedTracks(collection, emptyList(), SourceFetchPolicy.REFRESH))
            val search = guardSourceSearch(object : SourceSearch {
                override val kinds = setOf(SourceSearchKind.TRACK)
                override suspend fun search(query: String, kind: SourceSearchKind, request: SourceCursorRequest) = SourceSearchResult()
            }, lease) as SourceHotSearch
            assertFalse(search.supportsHotSearch)
        } finally { lease.close() }
    }

    @Test fun membershipPassesPolicyAndStopsAfterRemoval() = runBlocking {
        var active = true
        val policies = mutableListOf<SourceFetchPolicy>()
        val service = object : SourceCollectionEditing, SourceCollectionMembership {
            override suspend fun writableCollections() = listOf(collection)
            override suspend fun cachedWritableCollections() = listOf(collection)
            override suspend fun containedTracks(collection: SourceCollection, tracks: List<SourceTrackDetails>, policy: SourceFetchPolicy): Set<String>? {
                policies += policy
                return if (policy == SourceFetchPolicy.CACHE_FIRST) null else tracks.mapTo(hashSetOf()) { it.stableId }
            }
        }
        val lease = SourceOperationLease { check(active) }
        try {
            val guarded = guardSourceCollectionEditing(service, lease) as SourceCollectionMembership
            val track = SourceTrackDetails(SourceTrack(TrackIdentity("test", "42", "1"), "歌"), "s1")
            assertTrue(guarded.supportsMembership)
            assertEquals(listOf(collection), guarded.cachedWritableCollections())
            assertNull(guarded.containedTracks(collection, listOf(track), SourceFetchPolicy.CACHE_FIRST))
            assertEquals(setOf("s1"), guarded.containedTracks(collection, listOf(track), SourceFetchPolicy.REFRESH))
            assertEquals(listOf(SourceFetchPolicy.CACHE_FIRST, SourceFetchPolicy.REFRESH), policies)
            active = false
            assertTrue(runCatching { guarded.containedTracks(collection, listOf(track), SourceFetchPolicy.REFRESH) }.exceptionOrNull() is IllegalStateException)
        } finally { lease.close() }
    }

    @Test fun detailsPreserveNullAndEmptyAndRejectRemovedInstance() = runBlocking {
        var active = true
        val edits = mutableListOf<SourceCollectionDetailsEdit>()
        val service = object : SourceCollectionEditing, SourceCollectionDetailsEditing {
            override val editableFields = SourceCollectionEditField.entries.toSet()
            override suspend fun writableCollections() = listOf(collection)
            override suspend fun createDetails(edit: SourceCollectionDetailsEdit): SourceCollection { edits += edit; return collection }
            override suspend fun editDetails(collection: SourceCollection, edit: SourceCollectionDetailsEdit) = createDetails(edit)
        }
        val lease = SourceOperationLease { check(active) }
        try {
            val guarded = guardSourceCollectionEditing(service, lease) as SourceCollectionDetailsEditing
            guarded.createDetails(SourceCollectionDetailsEdit("歌单"))
            guarded.editDetails(collection, SourceCollectionDetailsEdit("歌单", "", "content://cover"))
            assertNull(edits[0].description)
            assertEquals("", edits[1].description)
            assertEquals("content://cover", edits[1].artworkUri)
            active = false
            assertTrue(runCatching { guarded.createDetails(SourceCollectionDetailsEdit("新歌单")) }.exceptionOrNull() is IllegalStateException)
            assertEquals(2, edits.size)
        } finally { lease.close() }
    }

    @Test fun notificationFlowStopsAtAccountChangeAndDoesNotDeliverOldEvents() = runBlocking {
        var account = "42"
        val changes = MutableSharedFlow<SourceLibraryChange>()
        val service = object : SourceCollections, SourceLibraryChanges {
            override val libraryChanges = changes
            override suspend fun detail(collection: SourceCollection) = collection
            override suspend fun tracks(collection: SourceCollection, request: SourceCursorRequest) = SourcePage<SourceTrackDetails>(emptyList(), null)
        }
        val lease = SourceOperationLease {}.apply { accountVersion = { account } }
        val delivered = mutableListOf<SourceLibraryChange>()
        try {
            val guarded = guardSourceCollections(service, lease) as SourceLibraryChanges
            val job = launch { guarded.libraryChanges.collect { delivered += it } }
            withTimeout(2_000) { changes.subscriptionCount.first { it > 0 } }
            val first = SourceLibraryChange("42", setOf(SourceLibrarySection.CREATED_COLLECTIONS))
            changes.emit(first)
            withTimeout(2_000) { while (delivered.isEmpty()) yield() }
            account = "77"
            changes.emit(first)
            withTimeout(2_000) { job.join() }
            assertEquals(listOf(first), delivered)
            assertTrue(job.isCancelled)
        } finally { lease.close() }
    }
}
