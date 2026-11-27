package com.musicone

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
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
    val rawTitle = if (primaryTitleOnly) playerPrimaryTitle(track.title) else track.title
    val rtlTitle = isRtlStrongText(rawTitle)
    val resolvedStyle = style.copy(
        fontSize = fontSize ?: style.fontSize,
        fontWeight = fontWeight ?: style.fontWeight,
        textDirection = contentTextDirection(rawTitle),
    )
    val outerDirection = LocalLayoutDirection.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        // 保持外层贴左与飞行左缘锚点不变；超长 RTL 歌名仅在跑马灯内部切 RTL 方向，使滚动方向自然。
        val textDirection = if (marquee && rtlTitle) LayoutDirection.Rtl else outerDirection
        Box(Modifier.weight(1f, fill = false)) {
            CompositionLocalProvider(LocalLayoutDirection provides textDirection) {
                Text(
                    text = musicOneUiAnnotatedString(rawTitle),
                    modifier = if (marquee) Modifier.basicMarquee(iterations = if (marqueeRunning) Int.MAX_VALUE else 0) else Modifier,
                    style = resolvedStyle,
                    color = if (!displayed.playable)
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f) else color,
                    maxLines = 1,
                    overflow = overflow,
                )
            }
        }
        if (showBadges) {
            OfflineTrackBadge(track, badgeOnDark)
            if (LocalTrackAccessBadgesVisible.current) mainSourceDisplayedAccessBadge(track)?.let { badge ->
                SongAccessBadge(badge, badgeOnDark, Modifier.padding(start = 5.dp))
            }
        }
    }
}
