package com.musicone

import com.musicone.source.sdk.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 每轮目录只处理已组合的卡片；离屏取消后允许再次进入时重试。 */
internal class SupplementalCollectionArtworkLoader(private val source: SourceRemoteCollectionArtwork) {
    private data class Request(val generation: Long, val revision: Long, val identity: TrackIdentity)
    private val completed = mutableMapOf<Request, SourceArtwork?>()
    private val pending = mutableSetOf<Request>()
    private val permits = Semaphore(4)

    fun clear() { completed.clear() }

    suspend fun load(collection: SourceRemoteCollection, generation: Long, revision: Long): SourceArtwork? {
        val request = Request(generation, revision, collection.identity)
        if (request in completed) return completed[request]
        if (!pending.add(request)) return null
        try {
            val artwork = try {
                permits.withPermit { source.artwork(collection, SourceFetchPolicy.REFRESH) }
                    .takeIf { !it.url.isNullOrBlank() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            completed[request] = artwork
            return artwork
        } finally { pending.remove(request) }
    }
}

internal fun SupplementalState.withCollectionArtwork(identity: TrackIdentity, artwork: SourceArtwork, revision: Long): SupplementalState {
    if (directoryArtworkRevision != revision || artwork.url.isNullOrBlank()) return this
    return copy(collections = collections.map { if (it.identity == identity) it.copy(artwork = artwork) else it },
        artworkVersions = artworkVersions + (identity to revision))
}
