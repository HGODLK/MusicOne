package com.musicone

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

@Composable
internal fun TrackTitle(
    track: MusicTrack,
    modifier: Modifier = Modifier,
    fontSize: TextUnit? = null,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    marquee: Boolean = false,
    marqueeRunning: Boolean = true,
    badgeOnDark: Boolean = false,
    showBadges: Boolean = true,
    primaryTitleOnly: Boolean = false,
    style: TextStyle = MaterialTheme.typography.titleSmall,
) {
    val displayed = displayedPlaybackTrack(track)
    val resolvedStyle = style.copy(
        fontSize = fontSize ?: style.fontSize,
        fontWeight = fontWeight ?: style.fontWeight,
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = musicOneUiAnnotatedString(
                if (primaryTitleOnly) playerPrimaryTitle(track.title) else track.title,
            ),
            modifier = Modifier.weight(1f, fill = false)
                .then(if (marquee) Modifier.basicMarquee(iterations = if (marqueeRunning) Int.MAX_VALUE else 0) else Modifier),
            style = resolvedStyle,
            color = if (!displayed.playable)
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f) else color,
            maxLines = 1,
            overflow = overflow,
        )
        if (showBadges) {
            OfflineTrackBadge(track, badgeOnDark)
            if (LocalTrackAccessBadgesVisible.current) mainSourceDisplayedAccessBadge(track)?.let { badge ->
                SongAccessBadge(badge, badgeOnDark, Modifier.padding(start = 5.dp))
            }
        }
    }
}
