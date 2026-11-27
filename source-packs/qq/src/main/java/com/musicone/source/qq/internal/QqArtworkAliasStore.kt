package com.musicone.source.qq.internal

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 保存无专辑短版歌曲借用的正式发行封面，让搜索、收藏歌单和播放队列共用同一结果。 */
internal class QqArtworkAliasStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun apply(track: MusicTrack): MusicTrack {
        if (track.source != MusicSource.QQ || !track.artworkUrl.isNullOrBlank()) return track
        val artworkUrl = preferences.getString(artworkKey(track.id), null)
            ?.takeIf(String::isNotBlank)
            ?: return track
        val album = preferences.getString(albumKey(track.id), null).orEmpty()
        return track.copy(
            artworkUrl = artworkUrl,
            album = track.album.ifBlank { album },
        )
    }

    fun apply(playlist: MusicPlaylist): MusicPlaylist =
        playlist.withResolvedTrackArtwork(playlist.tracks.map(::apply))

    fun remember(tracks: List<MusicTrack>): List<MusicTrack> {
        val resolved = tracks.map(::apply)
        val changedTrackIds = linkedSetOf<String>()
        preferences.edit {
            resolved.forEach { track ->
                val artworkUrl = track.artworkUrl?.takeIf(String::isNotBlank) ?: return@forEach
                if (preferences.getString(artworkKey(track.id), null) != artworkUrl ||
                    (track.album.isNotBlank() && preferences.getString(albumKey(track.id), null) != track.album)
                ) {
                    changedTrackIds += track.id
                }
                putString(artworkKey(track.id), artworkUrl)
                if (track.album.isNotBlank()) putString(albumKey(track.id), track.album)
            }
        }
        changedTrackIds.forEach(mutableChanges::tryEmit)
        return resolved
    }

    fun remember(original: MusicTrack, resolved: MusicTrack): MusicTrack {
        val merged = if (resolved.artworkUrl.isNullOrBlank()) apply(resolved) else resolved
        if (original.source == MusicSource.QQ && !merged.artworkUrl.isNullOrBlank()) {
            remember(listOf(original.copy(
                artworkUrl = merged.artworkUrl,
                album = original.album.ifBlank { merged.album },
            )))
        }
        return merged
    }

    private fun artworkKey(trackId: String) = "artwork:$trackId"
    private fun albumKey(trackId: String) = "album:$trackId"

    companion object {
        private const val PREFERENCES_NAME = "qq_artwork_aliases"
        private val mutableChanges = MutableSharedFlow<String>(extraBufferCapacity = 32)
        val changes = mutableChanges.asSharedFlow()
    }
}

/** 提前补齐歌单第一首歌的封面，让歌单卡片还没播放时就能显示正确封面。 */
internal class QqPlaylistArtworkAliasResolver(
    private val api: QqApiClient,
    private val aliases: QqArtworkAliasStore,
) {
    suspend fun resolve(playlist: MusicPlaylist, cookie: String): MusicPlaylist {
        val aliased = aliases.apply(playlist)
        val first = aliased.tracks.firstOrNull()
        if (!aliased.needsQqFirstTrackArtworkAlias() || first == null) return aliased
        val resolved = runCatchingCancellable { api.enrichTrackMetadata(first, cookie) }.getOrNull()
            ?.let { aliases.remember(first, it) }
            ?: return aliased
        return aliased.withResolvedTrackArtwork(
            aliased.tracks.mapIndexed { index, track -> if (index == 0) resolved else track },
        )
    }
}

/** 歌单本身没封面时，用补全后的第一首歌封面顶上。 */
internal fun MusicPlaylist.withResolvedTrackArtwork(resolvedTracks: List<MusicTrack>): MusicPlaylist {
    if (source == MusicSource.QQ && id == QQ_FAVORITES_PLAYLIST_ID)
        return copy(tracks = resolvedTracks).withQqFavoriteArtwork()
    val resolvedArtwork = artworkUrl?.takeIf(String::isNotBlank)
        ?: resolvedTracks.firstOrNull()?.artworkUrl?.takeIf(String::isNotBlank)
    return copy(tracks = resolvedTracks, artworkUrl = resolvedArtwork)
}

internal fun MusicPlaylist.needsQqFirstTrackArtworkAlias(): Boolean =
    source == MusicSource.QQ && (id == QQ_FAVORITES_PLAYLIST_ID || artworkUrl.isNullOrBlank()) &&
        tracks.firstOrNull()?.artworkUrl.isNullOrBlank()
