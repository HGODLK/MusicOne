package com.musicone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

@Composable
internal fun trackPlaybackColor(track: MusicTrack?, background: Color = MaterialTheme.colorScheme.background,
    fallbackSource: MusicSource? = track?.source): Color {
    val theme = LocalCurrentTrackTheme.current
    val declared = track?.source?.id?.let(theme.platformColor) ?: theme.argb.takeIf {
        track != null && ((theme.identity == track.trackThemeIdentity() && theme.isCurrent()) ||
            (theme.identity.sharesThemeOwner(track.trackThemeIdentity()) && theme.ownerCurrent()))
    }
    return Color(declared ?: fallbackTrackThemeArgb(fallbackSource == MusicSource.LOCAL, background.luminance() < .5f))
}

@Composable
internal fun TrackPlaybackArtworkIndicator(
    current: Boolean,
    playing: Boolean,
    shape: Shape,
    iconSize: Dp,
    modifier: Modifier = Modifier,
) {
    if (!current) return
    Box(
        modifier = modifier.fillMaxSize().clip(shape).background(Color.Black.copy(alpha = .32f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (playing) "正在播放" else "已暂停",
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}
