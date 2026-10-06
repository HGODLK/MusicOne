package com.musicone.demo

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

/** 在歌单数据准备阶段补全决定外层封面的首曲，避免以播放作为封面加载条件。 */
internal class QqPlaylistArtworkAliasResolver(
    private val api: QqApiClient,
    private val aliases: QqArtworkAliasStore,
) {
    suspend fun resolve(playlist: MusicPlaylist, cookie: String): MusicPlaylist {
        val aliased = aliases.apply(playlist)
        val first = aliased.tracks.firstOrNull()
        if (!aliased.needsQqFirstTrackArtworkAlias() || first == null) return aliased
        val resolved = runCatching { api.enrichTrackMetadata(first, cookie) }.getOrNull()
            ?.let { aliases.remember(first, it) }
            ?: return aliased
        return aliased.withResolvedTrackArtwork(
            aliased.tracks.mapIndexed { index, track -> if (index == 0) resolved else track },
        )
    }
}

/** 歌单没有独立封面时，在歌曲封面补全后同步派生首曲封面。 */
internal fun MusicPlaylist.withResolvedTrackArtwork(resolvedTracks: List<MusicTrack>): MusicPlaylist {
    val resolvedArtwork = artworkUrl?.takeIf(String::isNotBlank)
        ?: resolvedTracks.firstOrNull()?.artworkUrl?.takeIf(String::isNotBlank)
    return copy(tracks = resolvedTracks, artworkUrl = resolvedArtwork)
}

internal fun MusicPlaylist.needsQqFirstTrackArtworkAlias(): Boolean =
    source == MusicSource.QQ && artworkUrl.isNullOrBlank() &&
        tracks.firstOrNull()?.artworkUrl.isNullOrBlank()
