package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 卡片只观察当前曲目和播放状态，进度更新不牵动聊天卡片。 */
internal data class AiCardPlayback(val trackId: String? = null, val source: MusicSource? = null, val playing: Boolean = false)

internal val LocalAiCardPlayback = compositionLocalOf { AiCardPlayback() }

/** 与首页推荐卡共用封面加载、双色缓存和取色过渡，只调整为聊天横向布局。 */
@Composable
internal fun AiTrackCard(track: MusicTrack, onPlayTrack: (MusicTrack) -> Unit) {
    val playback = LocalAiCardPlayback.current
    val current = playback.trackId == track.id && playback.source == track.source
    val playing = current && playback.playing
    val playbackColor = trackPlaybackColor(track)
    val artwork by rememberArtworkBitmap(track.artworkUrl,
        maxSide = LocalMainSourceFeedArtworkSizes.current.thumbnail)
    Surface(onClick = { onPlayTrack(track) }, enabled = track.playable, shape = RoundedCornerShape(16.dp),
        color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth()) {
        MainSourceHomeArtworkGradient(artwork, track.artworkUrl ?: track.id, Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CrossfadingArtworkBitmapOrPlaceholder(artwork, artwork == null && !track.artworkUrl.isNullOrBlank(),
                    track.artworkStart, track.artworkEnd, track.artworkMark, Modifier.size(56.dp), 22.sp,
                    RoundedCornerShape(12.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(track.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                        color = if (current) playbackColor else MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (LocalTrackAccessBadgesVisible.current) mainSourceDisplayedAccessBadge(track)?.let {
                            SongAccessBadge(it, onDark = false)
                        }
                        OfflineTrackBadge(track, onDark = false)
                    }
                    Text(track.artists, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!track.playable) Text(track.unavailableReason ?: "暂不可播放", style = MaterialTheme.typography.labelSmall)
                }
                IconButton(onClick = { onPlayTrack(track) }, enabled = track.playable, modifier = Modifier.size(48.dp)) {
                    if (current) QueuePlayingIndicator(playing, playbackColor)
                    else Icon(Icons.Rounded.PlayArrow, contentDescription = "播放${track.title}", modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
