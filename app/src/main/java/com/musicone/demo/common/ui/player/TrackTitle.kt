package com.musicone.demo

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            color = color,
            maxLines = 1,
            overflow = overflow,
        )
        if (showBadges) {
            OfflineTrackBadge(track, badgeOnDark)
            qqDisplayedAccessBadge(track)?.let { badge ->
                MusicAccessBadgeLabel(badge, badgeOnDark, Modifier.padding(start = 5.dp),
                    label = if (track.source == MusicSource.QQ && badge == MusicAccessBadge.VIP) "VIP" else badge.label)
            }
        }
    }
}

@Composable
private fun MusicAccessBadgeLabel(
    badge: MusicAccessBadge,
    onDark: Boolean,
    modifier: Modifier = Modifier,
    label: String = badge.label,
) {
    val accent = when (badge) {
        MusicAccessBadge.VIP -> Color(0xFFE79A16)
        MusicAccessBadge.PAID -> Color(0xFFE16055)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (onDark) Color.White.copy(alpha = .16f) else accent.copy(alpha = .14f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (onDark) Color.White.copy(alpha = .9f) else accent,
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}
