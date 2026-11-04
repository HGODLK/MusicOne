package com.musicone.source.runtime

import com.musicone.source.sdk.*
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect

/** 新增内容能力沿用实例租约与账号检查，旧音源继续使用原接口。 */
internal fun guardSourceSearch(service: SourceSearch, operations: SourceOperationLease): SourceSearch =
    object : SourceSearch, SourceHotSearch {
        override val supportsSuggestions get() = operations.read { service.supportsSuggestions }
        override val kinds get() = operations.read { service.kinds }
        override suspend fun search(query: String, kind: SourceSearchKind, request: SourceCursorRequest) = operations.call { service.search(query, kind, request) }
        override suspend fun suggestions(query: String) = operations.call { service.suggestions(query) }
        override val supportsHotSearch get() = operations.read { (service as? SourceHotSearch)?.supportsHotSearch == true }
        override suspend fun hotSearches() = operations.call { (service as? SourceHotSearch)?.hotSearches().orEmpty() }
    }

internal fun guardSourceEntities(service: SourceEntities, operations: SourceOperationLease): SourceEntities =
    object : SourceEntities, SourceEntityArtwork, SourceEntityFollowing {
        override suspend fun artwork(reference: SourceEntityRef) = operations.call { service.artwork(reference) }
        override val kinds get() = operations.read { service.kinds }
        override fun supportsTrackSearch(reference: SourceEntityRef) = operations.read { service.supportsTrackSearch(reference) }
        override fun trackSortOptions(reference: SourceEntityRef) = operations.read { service.trackSortOptions(reference) }
        override suspend fun searchTracks(reference: SourceEntityRef, query: String, sortId: String?, request: SourceCursorRequest) = operations.call { service.searchTracks(reference, query, sortId, request) }
        override suspend fun entity(reference: SourceEntityRef) = operations.call { service.entity(reference) }
        override suspend fun tracks(reference: SourceEntityRef, request: SourceCursorRequest) = operations.call { service.tracks(reference, request) }
        override suspend fun collections(reference: SourceEntityRef, request: SourceCursorRequest) = operations.call { service.collections(reference, request) }
        override val supportsFollowing get() = operations.read { (service as? SourceEntityFollowing)?.supportsFollowing == true }
        override suspend fun isFollowing(reference: SourceEntityRef) = operations.call { requireNotNull(service as? SourceEntityFollowing).isFollowing(reference) }
        override suspend fun setFollowing(reference: SourceEntityRef, following: Boolean) = operations.call { requireNotNull(service as? SourceEntityFollowing).setFollowing(reference, following) }
        override suspend fun followed(request: SourceCursorRequest) = operations.call { requireNotNull(service as? SourceEntityFollowing).followed(request) }
    }

internal fun guardSourceCollections(service: SourceCollections, operations: SourceOperationLease): SourceCollections =
    object : SourceCollections, SourceCollectionCachePolicy, SourceCollectionReturnBehavior, SourceLibraryChanges {
        override val refreshLibraryAfterAnyReturn get() = operations.read { (service as? SourceCollectionReturnBehavior)?.refreshLibraryAfterAnyReturn ?: false }
        override fun includesInCacheLibrary(collection: SourceCollection) = operations.read { (service as? SourceCollectionCachePolicy)?.includesInCacheLibrary(collection) ?: true }
        override fun sortOptions(collection: SourceCollection) = operations.read { service.sortOptions(collection) }
        override fun selectedSort(collection: SourceCollection) = operations.read { service.selectedSort(collection) }
        override fun selectSort(collection: SourceCollection, optionId: String) = operations.read { service.selectSort(collection, optionId) }
        override val librarySections get() = operations.read { service.librarySections }
        override suspend fun library(section: SourceLibrarySection, request: SourceCursorRequest) = operations.call { service.library(section, request) }
        override suspend fun detail(collection: SourceCollection) = operations.call { service.detail(collection) }
        override suspend fun tracks(collection: SourceCollection, request: SourceCursorRequest) = operations.call { service.tracks(collection, request) }
        override val libraryChanges get() = channelFlow {
            operations.call {
                val check = operations.deferredCheck()
                (service as? SourceLibraryChanges)?.libraryChanges?.collect { change -> check(); send(change) }
            }
        }
    }

internal fun guardSourceFavorites(service: SourceFavorites, operations: SourceOperationLease): SourceFavorites =
    object : SourceFavorites, SourceCollectionFavoritePolicy {
        override val supportsCollections get() = operations.read { service.supportsCollections }
        override suspend fun favoriteTrackIds() = operations.call { service.favoriteTrackIds() }
        override suspend fun setTracksFavorite(tracks: List<SourceTrackDetails>, favorite: Boolean) = operations.call { service.setTracksFavorite(tracks, favorite) }
        override suspend fun isTrackFavorite(track: SourceTrackDetails) = operations.call { service.isTrackFavorite(track) }
        override suspend fun setTrackFavorite(track: SourceTrackDetails, favorite: Boolean) = operations.call { service.setTrackFavorite(track, favorite) }
        override suspend fun setCollectionFavorite(collection: SourceCollection, favorite: Boolean) = operations.call { service.setCollectionFavorite(collection, favorite) }
        override fun canFavorite(collection: SourceCollection) = operations.read { (service as? SourceCollectionFavoritePolicy)?.canFavorite(collection) ?: true }
        override fun favoriteIdentityKeys(collection: SourceCollection) = operations.read {
            (service as? SourceCollectionFavoritePolicy)?.favoriteIdentityKeys(collection) ?: setOf(collection.stableId)
        }
    }

internal fun guardSourceCollectionEditing(service: SourceCollectionEditing, operations: SourceOperationLease): SourceCollectionEditing =
    object : SourceCollectionEditing, SourceCollectionDetailsEditing, SourceCollectionRemoteOrder {
        override val supportedOperations get() = operations.read { service.supportedOperations }
        override suspend fun writableCollections() = operations.call { service.writableCollections() }
        override suspend fun create(edit: SourceCollectionEdit) = operations.call { service.create(edit) }
        override suspend fun edit(collection: SourceCollection, edit: SourceCollectionEdit) = operations.call { service.edit(collection, edit) }
        override suspend fun delete(collection: SourceCollection) = operations.call { service.delete(collection) }
        override suspend fun changeTracks(collection: SourceCollection, tracks: List<SourceTrackDetails>, add: Boolean) = operations.call { service.changeTracks(collection, tracks, add) }
        override suspend fun reorder(collection: SourceCollection, orderedTrackIds: List<String>) = operations.call { service.reorder(collection, orderedTrackIds) }
        override val editableFields get() = operations.read { (service as? SourceCollectionDetailsEditing)?.editableFields.orEmpty() }
        override suspend fun createDetails(edit: SourceCollectionDetailsEdit) = operations.call { requireNotNull(service as? SourceCollectionDetailsEditing).createDetails(edit) }
        override suspend fun editDetails(collection: SourceCollection, edit: SourceCollectionDetailsEdit) = operations.call { requireNotNull(service as? SourceCollectionDetailsEditing).editDetails(collection, edit) }
        override fun supportsRemoteOrder(collection: SourceCollection) = operations.read { (service as? SourceCollectionRemoteOrder)?.supportsRemoteOrder(collection) == true }
        override suspend fun orderedTracks(collection: SourceCollection, request: SourceCursorRequest) = operations.call { requireNotNull(service as? SourceCollectionRemoteOrder).orderedTracks(collection, request) }
    }
