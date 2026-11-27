package com.musicone

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** 构造给系统媒体控制栏用的 Metadata；不拿预估时长冒充真实音频时长。 */
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

/** 歌曲详情补全后，只原地更新当前 MediaItem 的元数据，不影响正在播的音频和进度。 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun refreshSystemMediaMetadata(player: Player, track: MusicTrack): Boolean {
    val item = player.currentMediaItem ?: return false
    if (item.mediaId != track.id || !player.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) return false
    val metadata = systemMediaMetadata(track, item.mediaMetadata, player.duration.takeIf { it > 0L })
    player.replaceMediaItem(player.currentMediaItemIndex, item.buildUpon().setMediaMetadata(metadata).build())
    return true
}
