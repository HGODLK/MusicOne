package com.musicone.sourceintegration

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import com.musicone.*
import com.musicone.source.sdk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal class SourceLibraryService(private val context: Context) : SourceLibraryHost {
    private val store = MusicLibraryStore.get(context)
    private val subscriptions = SourceSubscriptionPersistence(store)
    override suspend fun createCollection(title: String) = SourceLocalCollection(store.createPlaylist(title), title.trim(), true)
    override suspend fun destinations(): List<SourceLocalCollection> {
        store.refresh()
        val subscribed = store.state.value.subscriptions.mapTo(hashSetOf()) { it.playlistId }
        return store.state.value.playlists.filter { !it.isLocalLibraryMirror }.map {
            SourceLocalCollection(it.id, it.title, it.id !in subscribed)
        }
    }
    override suspend fun importTracks(tracks: List<SourceTrackDetails>, destinationId: String) {
        store.addTracks(destinationId, tracks.map { it.toMusicTrack() })
    }
    override suspend fun editMetadata(track: SourceTrackDetails, edit: SourceTrackMetadataEdit) {
        if (edit.title == null && edit.artists == null && edit.album == null && edit.artworkUri == null) return
        store.saveTrack(track.toMusicTrack())
        store.importTransaction(onCommitted = {}) { db ->
            db.update("songs", ContentValues().apply {
                edit.title?.let { put("title_override", it.takeIf(String::isNotBlank)) }
                edit.artists?.let { put("artist_override", it.takeIf(String::isNotBlank)) }
                edit.album?.let { put("album_override", it.takeIf(String::isNotBlank)) }
                edit.artworkUri?.let { put("artwork_override", it.takeIf(String::isNotBlank)) }
            }, "id=?", arrayOf(track.storageId))
        }
    }
    override suspend fun setManualLyrics(track: SourceTrackDetails, documentUri: String?) {
        store.saveTrack(track.toMusicTrack())
        if (documentUri == null) { store.setLyrics(track.storageId, null); return }
        withContext(Dispatchers.IO) {
            val uri = Uri.parse(documentUri)
            val extension = readLocalLyricExtension(context, uri)
            val file = File(store.assets, "lyrics/${UUID.randomUUID()}.$extension")
            var committed = false
            try {
                file.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: error("无法读取歌词文件")
                require(parseLibraryLyrics(file.readText(), extension).isNotEmpty()) { "未找到有效歌词时间轴" }
                store.setLyrics(track.storageId, file.absolutePath) { committed = true }
            } finally { if (!committed) file.delete() }
        }
    }
    override suspend fun setLineOffset(track: SourceTrackDetails, offsetMs: Long) {
        store.saveTrack(track.toMusicTrack()); store.setLyricOffset(track.storageId, offsetMs)
    }
    override suspend fun lineOffset(track: SourceTrackDetails) = withContext(Dispatchers.IO) { store.lyricAsset(track.storageId).second }
    override suspend fun subscriptionRevision(identity: SourceSubscriptionIdentity) = withContext(Dispatchers.IO) { subscriptions.revision(identity) }
    override suspend fun commitSubscription(identity: SourceSubscriptionIdentity, expectedRevision: Long, items: List<SourceSubscriptionItem>) = subscriptions.commit(identity, expectedRevision, items)
    override suspend fun removeSubscription(identity: SourceSubscriptionIdentity, keepTracks: Boolean) = subscriptions.remove(identity, keepTracks)
}

private val SourceTrackDetails.storageId get() = sourceStorageId(track.identity.sourceId, track.identity.accountId, stableId)
