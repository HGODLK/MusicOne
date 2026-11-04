package com.musicone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicone.source.sdk.SourceTrackBadge
import com.musicone.source.sdk.TrackAccessBadge
import com.musicone.source.sdk.TrackAccessInfo

/** 缺少展示声明时保持通用权益角标；展示文字不参与播放权限判断。 */
internal fun resolvedSongAccessBadge(access: TrackAccessInfo): SourceTrackBadge? {
    val fallbackText = when (access.badge) {
        TrackAccessBadge.MEMBERSHIP -> "vip"
        TrackAccessBadge.PURCHASE -> "付费"
        null -> if (access.trialAvailable) "试听" else return access.displayBadge?.takeIf { it.text.isNotBlank() }
    }
    val accent = when (access.badge) {
        TrackAccessBadge.MEMBERSHIP -> 0xFFE79A16L
        TrackAccessBadge.PURCHASE -> 0xFFE16055L
        null -> 0xFF64748BL
    }
    val declared = access.displayBadge ?: SourceTrackBadge(fallbackText)
    if (declared.text.isBlank()) return null
    return declared.copy(
        lightTextArgb = declared.lightTextArgb ?: accent,
        lightBackgroundArgb = declared.lightBackgroundArgb ?: ((accent and 0x00FFFFFFL) or 0x24000000L),
        darkTextArgb = declared.darkTextArgb ?: 0xE6FFFFFFL,
        darkBackgroundArgb = declared.darkBackgroundArgb ?: 0x29FFFFFFL,
    )
}

@Composable
internal fun SongAccessBadge(badge: SourceTrackBadge, onDark: Boolean, modifier: Modifier = Modifier) {
    val dark = onDark || MaterialTheme.colorScheme.surface.luminance() < .5f
    val foreground = if (dark) badge.darkTextArgb else badge.lightTextArgb
    val background = if (dark) badge.darkBackgroundArgb else badge.lightBackgroundArgb
    Box(modifier.clip(RoundedCornerShape(4.dp))
        .background(Color(background ?: if (dark) 0x29FFFFFFL else 0x2464748BL))
        .padding(horizontal = 4.dp, vertical = 1.dp), contentAlignment = Alignment.Center) {
        Text(text = badge.text, color = Color(foreground ?: if (dark) 0xE6FFFFFFL else 0xFF64748BL),
            fontSize = 9.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}
