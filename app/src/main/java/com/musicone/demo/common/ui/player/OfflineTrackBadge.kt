package com.musicone.demo

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun OfflineTrackBadge(track: MusicTrack, onDark: Boolean) {
    val context = LocalContext.current
    val availability = remember(context) { OfflinePlaybackAvailability.get(context) }
    val state by availability.state.collectAsStateWithLifecycle()
    if (!state.showsBadge(track.id)) return
    val accent = if (onDark) Color.White.copy(alpha = .9f) else MaterialTheme.colorScheme.primary
    Text("√", fontSize = 10.sp, lineHeight = 11.sp, color = accent,
        modifier = Modifier.padding(start = 5.dp).clip(RoundedCornerShape(4.dp))
            .background(accent.copy(alpha = .14f)).padding(horizontal = 4.dp, vertical = 1.dp)
            .semantics { contentDescription = "已缓存，可离线播放" })
}
