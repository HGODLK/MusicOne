package com.musicone.demo

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** 系统歌曲信息与应用共用同一曲目身份，不把预期整曲时长当作实际音源时长。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun systemMediaMetadata(
    track: MusicTrack,
    previous: MediaMetadata? = null,
    durationMs: Long? = null,
): MediaMetadata {
    val artwork = track.artworkUrl?.takeIf(String::isNotBlank)?.let(Uri::parse) ?: previous?.artworkUri
    val extras = previous?.extras?.let(::Bundle) ?: Bundle()
    extras.putLong("musicone_duration", track.durationMs)
    VivoSystemMedia.addBasicCapabilities(extras)
    val builder = (previous?.buildUpon() ?: MediaMetadata.Builder())
        .setTitle(track.title)
        .setArtist(track.artists)
        .setAlbumTitle(track.album)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setDurationMs(durationMs?.takeIf { it >= 0L })
        .setArtworkUri(artwork)
        .setExtras(extras)
    if (artwork != previous?.artworkUri) builder.setArtworkData(null, null)
    return builder.build()
}

/** 后补信息只替换当前媒体项的 metadata，保留音源、缓存键和播放位置。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun refreshSystemMediaMetadata(player: Player, track: MusicTrack): Boolean {
    val item = player.currentMediaItem ?: return false
    if (item.mediaId != track.id || !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return false
    val metadata = systemMediaMetadata(track, item.mediaMetadata, player.duration.takeIf { it > 0L })
    player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(metadata).build())
    return true
}
